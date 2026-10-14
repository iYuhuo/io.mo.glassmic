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
 */
object XBridge {

    private const val CACHE_TTL_MS = 0L
    // Provider 被禁用/不可达（= 前台服务未运行）时的回退缓存时长。
    // 拉长它，避免服务关闭期间某个 App 仍在录音时，每帧都对已禁用的 Provider 空查询一次。
    private const val UNREACHABLE_TTL_MS = 2000L

    private var cachedSource: SourceType? = null
    private var cachedResolveReason: String = "unknown"
    private var cachedAt: Long = 0L
    private var cachedPkg: String = ""
    // 上次查询 Provider 是否可达（false = 服务未运行 / Provider 被禁用）
    @Volatile private var lastReachable: Boolean = true

    @Volatile var currentPackage: String = ""

    /** 注入入口设置：101 = libxposed API 101，82 = legacy。仅用于诊断状态展示。 */
    @Volatile var apiVersion: Int = 101

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

    private const val REPORT_EVERY_READS = 50
    private const val REPORT_EVERY_MS = 1500L
    private val pendingReads = AtomicInteger(0)
    private val pendingBytes = AtomicLong(0L)
    @Volatile private var lastReportMs: Long = 0L

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

    private fun refreshCache(ctx: Context, callerPackage: String) {
        val now = System.currentTimeMillis()
        val ttl = if (lastReachable) CACHE_TTL_MS else UNREACHABLE_TTL_MS
        if (cachedPkg == callerPackage && cachedSource != null && now - cachedAt < ttl) {
            return
        }
        val result = queryProvider(ctx.contentResolver, callerPackage)
        cachedSource = result.source
        cachedResolveReason = result.reason
        cachedAt = now
        cachedPkg = callerPackage
        MicForceRelease.applyRemoteState(
            token = result.forceReleaseToken,
            scoped = result.inScope,
            blockUntil = result.blockUntilMs,
            reason = "resolve",
        )
    }

    private data class ResolveRow(
        val source: SourceType,
        val reason: String,
        val forceReleaseToken: Long,
        val inScope: Boolean,
        val blockUntilMs: Long,
    )

    private fun queryProvider(cr: ContentResolver, pkg: String): ResolveRow {
        val uri = Uri.parse("content://${Constants.PROVIDER_RUNTIME}/resolve")
        // selectionArgs[1] 带上 api 版本，供 RuntimeProvider 顺手更新「模块已激活」诊断状态，
        // 免去在注入进程 attach 阶段单独 ping（那是复活的主要来源）。
        return runCatching {
            val c = cr.query(uri, null, null, arrayOf(pkg, apiVersion.toString()), null)
            if (c == null) {
                // Provider 被禁用（服务未运行）→ AMS 不解析、不拉起本进程，直接走真麦
                lastReachable = false
                return ResolveRow(SourceType.REAL_MIC, "provider_unreachable", 0L, false, 0L)
            }
            c.use {
                lastReachable = true
                if (!it.moveToFirst()) {
                    return@use ResolveRow(SourceType.REAL_MIC, "query_empty", 0L, false, 0L)
                }
                val src = SourceType.valueOf(it.getString(0))
                val reason = if (it.columnCount > 5) it.getString(5) ?: "unknown" else "unknown"
                fun longAt(i: Int): Long = when {
                    it.columnCount <= i -> 0L
                    it.getType(i) == android.database.Cursor.FIELD_TYPE_INTEGER -> it.getLong(i)
                    it.getType(i) == android.database.Cursor.FIELD_TYPE_STRING ->
                        it.getString(i)?.toLongOrNull() ?: 0L
                    else -> it.getString(i)?.toLongOrNull() ?: 0L
                }
                fun intAt(i: Int): Int = when {
                    it.columnCount <= i -> 0
                    it.getType(i) == android.database.Cursor.FIELD_TYPE_INTEGER -> it.getInt(i)
                    it.getType(i) == android.database.Cursor.FIELD_TYPE_STRING ->
                        it.getString(i)?.toIntOrNull() ?: 0
                    else -> 0
                }
                ResolveRow(
                    source = src,
                    reason = reason,
                    forceReleaseToken = longAt(6),
                    inScope = intAt(7) != 0,
                    blockUntilMs = longAt(8),
                )
            }
        }.getOrElse {
            lastReachable = false
            ResolveRow(SourceType.REAL_MIC, "query_error", 0L, false, 0L)
        }
    }
}
