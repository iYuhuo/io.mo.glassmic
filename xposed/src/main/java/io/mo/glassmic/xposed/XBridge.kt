package io.mo.glassmic.xposed

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import io.mo.glassmic.core.Constants
import io.mo.glassmic.core.model.SourceType
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 注入到目标 App 进程后，只通过 yuhuo 自己的 ContentProvider 读取实时决策。
 *
 * API 101 不再依赖旧版 XSharedPreferences；黑白名单、首次启动、BootGate、运行态等
 * 统一由 app 进程里的 EffectiveSourceResolver 计算，Xposed 侧只缓存短时间结果。
 */
object XBridge {

    private const val CACHE_TTL_MS = 200L

    private var cachedSource: SourceType? = null
    private var cachedMix = false
    private var cachedResolveReason: String = "unknown"
    private var cachedAt: Long = 0L
    private var cachedPkg: String = ""

    @Volatile var currentPackage: String = ""

    fun pingModuleLoaded(
        ctx: Context,
        callerPackage: String,
        api: Int = 101,
        nativeOk: Boolean? = null,
        audiorecordHooked: Boolean? = null,
        resolvedSource: SourceType? = null,
        resolveReason: String? = null
    ) {
        runCatching {
            val extras = Bundle().apply {
                putString("package", callerPackage)
                putLong("time", System.currentTimeMillis())
                putInt("api", api)
                if (nativeOk != null) putInt("native_ok", if (nativeOk) 1 else 0)
                if (audiorecordHooked != null) {
                    putInt("audiorecord_hooked", if (audiorecordHooked) 1 else 0)
                }
                if (resolvedSource != null) putString("resolve", resolvedSource.name)
                if (resolveReason != null) putString("resolve_reason", resolveReason)
            }
            ctx.contentResolver.call(
                Uri.parse("content://${Constants.PROVIDER_RUNTIME}"),
                Constants.METHOD_XPOSED_PING,
                callerPackage,
                extras
            )
        }
    }

    // ============== 拦截统计累计 + 节流上报 ==============
    private const val REPORT_EVERY_READS = 50
    private const val REPORT_EVERY_MS = 1500L
    private val pendingReads = AtomicInteger(0)
    private val pendingBytes = AtomicLong(0L)
    @Volatile private var lastReportMs: Long = 0L

    /**
     * 任意一个 hook 拦截成功时调用——累计到内存计数器，
     * 达到阈值或超过节流时间后批量写到 RuntimeProvider。
     */
    fun recordInterception(ctx: Context, pkg: String, bytes: Int, sampleRate: Int, channels: Int) {
        if (bytes <= 0) return
        pendingReads.incrementAndGet()
        pendingBytes.addAndGet(bytes.toLong())
        val now = System.currentTimeMillis()
        if (pendingReads.get() >= REPORT_EVERY_READS || now - lastReportMs >= REPORT_EVERY_MS) {
            val r = pendingReads.getAndSet(0)
            val b = pendingBytes.getAndSet(0L)
            lastReportMs = now
            if (r > 0) reportInterceptStats(ctx, pkg, r, b, sampleRate, channels)
        }
    }

    /**
     * 直接批量上报——给 native AAudio hook 用：它已经在 native 侧聚合好了 deltaReads/deltaBytes，
     * 这里跳过 Java 端的节流，直接写到 ContentProvider。
     */
    fun reportInterceptBatch(
        ctx: Context,
        callerPackage: String,
        deltaReads: Int,
        deltaBytes: Long,
        sampleRate: Int,
        channels: Int
    ) {
        if (deltaReads <= 0 || deltaBytes <= 0) return
        reportInterceptStats(ctx, callerPackage, deltaReads, deltaBytes, sampleRate, channels)
    }

    private fun reportInterceptStats(
        ctx: Context,
        callerPackage: String,
        deltaReads: Int,
        deltaBytes: Long,
        sampleRate: Int,
        channels: Int
    ) {
        runCatching {
            val extras = Bundle().apply {
                putString("package", callerPackage)
                putLong("time", System.currentTimeMillis())
                putInt("delta_reads", deltaReads)
                putLong("delta_bytes", deltaBytes)
                putInt("sample_rate", sampleRate)
                putInt("channels", channels)
            }
            ctx.contentResolver.call(
                Uri.parse("content://${Constants.PROVIDER_RUNTIME}"),
                Constants.METHOD_AUDIO_INTERCEPT,
                callerPackage,
                extras
            )
        }
    }

    fun resolveSource(ctx: Context, callerPackage: String): SourceType {
        refreshCache(ctx, callerPackage)
        return cachedSource ?: SourceType.REAL_MIC
    }

    fun queryResolveExplanation(ctx: Context, callerPackage: String): Pair<SourceType, String> {
        refreshCache(ctx, callerPackage)
        return (cachedSource ?: SourceType.REAL_MIC) to cachedResolveReason
    }

    fun isRealMicMixEnabled(ctx: Context, callerPackage: String): Boolean {
        refreshCache(ctx, callerPackage)
        return cachedMix
    }

    private fun refreshCache(ctx: Context, callerPackage: String) {
        val now = System.currentTimeMillis()
        if (cachedPkg == callerPackage && cachedSource != null && now - cachedAt < CACHE_TTL_MS) {
            return
        }
        val result = queryProvider(ctx.contentResolver, callerPackage)
        cachedSource = result.first
        cachedMix = result.second
        cachedResolveReason = result.third
        cachedAt = now
        cachedPkg = callerPackage
    }

    private fun queryProvider(cr: ContentResolver, pkg: String): Triple<SourceType, Boolean, String> {
        val uri = Uri.parse("content://${Constants.PROVIDER_RUNTIME}/resolve")
        return runCatching {
            cr.query(uri, null, null, arrayOf(pkg), null)?.use { c ->
                if (!c.moveToFirst()) return@use Triple(SourceType.REAL_MIC, false, "query_empty")
                val src = SourceType.valueOf(c.getString(0))
                val mix = c.columnCount > 4 && c.getInt(4) == 1
                val reason = if (c.columnCount > 5) c.getString(5) ?: "unknown" else "unknown"
                Triple(src, mix, reason)
            } ?: Triple(SourceType.REAL_MIC, false, "query_null")
        }.getOrDefault(Triple(SourceType.REAL_MIC, false, "query_error"))
    }
}
