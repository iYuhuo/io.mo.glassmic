package io.mo.glassmic.xposed

import android.content.Context
import android.net.Uri
import android.os.Build
import com.bytedance.shadowhook.ShadowHook
import io.mo.glassmic.core.Constants
import io.mo.glassmic.core.model.SourceType
import kotlin.concurrent.thread

/**
 * AAudio / OpenSL / native AudioRecord 原生 hook。
 *
 * 由 [HookBootstrap] 在 yuhuo 判定 FILE/SILENCE 后调用 [install]；
 * 不在 Application.attach 主线程加载 ShadowHook。
 */
object NativeAAudioHook {

    private const val TAG = "Yuhuo-NativeAAudio"
    private const val FD_REFRESH_INTERVAL_MS = 10_000L
    private const val DECISION_LOG_INTERVAL_MS = 30_000L
    private const val HOOK_RETRY_INTERVAL_MS = 2_000L
    private const val POLL_INTERVAL_FILE_MS = 250L
    private const val POLL_INTERVAL_IDLE_MS = 15_000L

    private val initLock = Any()
    @Volatile private var libLoaded = false
    @Volatile private var hooksReady = false
    @Volatile private var audiorecordHooked = false
    @Volatile private var pollerStarted = false
    @Volatile private var lastHookRetryMs: Long = 0L
    @Volatile private var pushedFdSr: Int = 0
    @Volatile private var pushedFdCh: Int = 0
    @Volatile private var hasPushedFd: Boolean = false
    @Volatile private var lastFdOpenMs: Long = 0L
    @Volatile private var lastDecisionLogMs: Long = 0L

    fun install(ctx: Context, callerPackage: String): Boolean {
        synchronized(initLock) {
            if (!ensureNativeLibLoaded()) return false
            retryNativeHooks(forceLog = true)
            startPoller(ctx.applicationContext ?: ctx, callerPackage)
            android.util.Log.i(
                TAG,
                "native layer ready in $callerPackage hooksReady=$hooksReady audiorecord=$audiorecordHooked"
            )
            return hooksReady
        }
    }

    fun isReady(): Boolean = hooksReady

    fun isAudiorecordHooked(): Boolean = audiorecordHooked

    private fun ensureNativeLibLoaded(): Boolean {
        if (libLoaded) return true

        val initOk = runCatching {
            ShadowHook.init(
                ShadowHook.ConfigBuilder()
                    .setMode(ShadowHook.Mode.UNIQUE)
                    .setDebuggable(false)
                    .build()
            )
            true
        }.onFailure { android.util.Log.e(TAG, "ShadowHook.init failed: ${it.message}", it) }
            .getOrDefault(false)
        if (!initOk) return false

        val libOk = runCatching { System.loadLibrary("glassmic_native") }
            .onFailure { android.util.Log.e(TAG, "loadLibrary glassmic_native failed: ${it.message}", it) }
            .isSuccess
        if (!libOk) return false

        libLoaded = true
        return true
    }

    private fun retryNativeHooks(forceLog: Boolean = false): Boolean {
        if (audiorecordHooked && hooksReady) return true

        val now = System.currentTimeMillis()
        if (!forceLog && now - lastHookRetryMs < HOOK_RETRY_INTERVAL_MS) {
            return hooksReady
        }
        lastHookRetryMs = now

        val rc = runCatching { nativeInstall() }
            .onFailure { android.util.Log.e(TAG, "nativeInstall throw: ${it.message}", it) }
            .getOrDefault(-1)
        val wasReady = hooksReady
        hooksReady = rc == 0
        audiorecordHooked = runCatching { nativeIsAudiorecordHooked() }.getOrDefault(false)

        if (hooksReady && !wasReady) {
            android.util.Log.i(TAG, "native hooks installed (audiorecord=$audiorecordHooked)")
        } else if (!audiorecordHooked && forceLog) {
            android.util.Log.w(TAG, "AudioRecord::read hook pending; will retry later")
        }
        return hooksReady
    }

    private fun startPoller(ctx: Context, callerPackage: String) {
        if (pollerStarted) return
        pollerStarted = true
        thread(name = "Yuhuo-AAudioPoller", isDaemon = true, priority = Thread.MIN_PRIORITY) {
            while (true) {
                var sleepMs = POLL_INTERVAL_IDLE_MS
                try {
                    if (!audiorecordHooked || !hooksReady) {
                        retryNativeHooks()
                    }

                    val now = System.currentTimeMillis()
                    val src = XBridge.resolveSource(ctx, callerPackage)
                    val decisionCode = when (src) {
                        SourceType.REAL_MIC -> 0
                        SourceType.FILE     -> 1
                        SourceType.SILENCE  -> 2
                    }
                    nativeSetDecision(decisionCode)
                    nativeSetMixEnabled(XBridge.isRealMicMixEnabled(ctx, callerPackage))

                    if (src == SourceType.FILE) {
                        sleepMs = POLL_INTERVAL_FILE_MS
                        val forceRefresh = hasPushedFd &&
                            now - lastFdOpenMs >= FD_REFRESH_INTERVAL_MS
                        ensurePcmFd(ctx, force = forceRefresh)
                    } else {
                        if (hasPushedFd) {
                            nativeSetPcmFd(-1, 0, 0)
                            hasPushedFd = false
                            pushedFdSr = 0
                            pushedFdCh = 0
                        }
                        if (now - lastDecisionLogMs >= DECISION_LOG_INTERVAL_MS) {
                            lastDecisionLogMs = now
                            android.util.Log.d(TAG, "decision=$src pkg=$callerPackage (idle poll)")
                        }
                    }

                    val stats = nativeDrainStats()
                    if (stats != null && stats.size >= 4) {
                        val reads = stats[0].toInt()
                        val bytes = stats[1]
                        val sr = stats[2].toInt()
                        val ch = stats[3].toInt()
                        if (reads > 0 && bytes > 0) {
                            XBridge.reportInterceptBatch(
                                ctx, callerPackage,
                                deltaReads = reads,
                                deltaBytes = bytes,
                                sampleRate = sr, channels = ch
                            )
                        }
                    }
                } catch (t: Throwable) {
                    android.util.Log.w(TAG, "poller iter error: ${t.message}")
                }

                try {
                    Thread.sleep(sleepMs)
                } catch (_: InterruptedException) {
                    return@thread
                }
            }
        }
    }

    private fun ensurePcmFd(ctx: Context, force: Boolean = false) {
        val wantSr = 48000
        val wantCh = 1
        if (!force && hasPushedFd && pushedFdSr == wantSr && pushedFdCh == wantCh) return

        if (hasPushedFd) {
            nativeSetPcmFd(-1, 0, 0)
            hasPushedFd = false
            pushedFdSr = 0
            pushedFdCh = 0
        }

        val uri = Uri.parse("content://${Constants.PROVIDER_PCM}/stream?sr=$wantSr&ch=$wantCh")
        val pfd = runCatching {
            ctx.contentResolver.openFileDescriptor(uri, "r")
        }.onFailure {
            android.util.Log.w(TAG, "open pcm pipe failed: ${it.message}")
        }.getOrNull() ?: run {
            nativeSetPcmFd(-1, 0, 0)
            hasPushedFd = false
            return
        }

        val fd = if (Build.VERSION.SDK_INT >= 33) {
            runCatching { pfd.detachFd() }.getOrDefault(-1)
        } else {
            runCatching {
                val rawFd = pfd.fileDescriptor
                nativeDupFd(rawFd)
            }.getOrDefault(-1)
        }
        runCatching { pfd.close() }
        if (fd < 0) {
            nativeSetPcmFd(-1, 0, 0)
            hasPushedFd = false
            return
        }
        nativeSetPcmFd(fd, wantSr, wantCh)
        hasPushedFd = true
        pushedFdSr = wantSr
        pushedFdCh = wantCh
        lastFdOpenMs = System.currentTimeMillis()
        android.util.Log.i(TAG, "pcm fd opened: fd=$fd sr=$wantSr ch=$wantCh force=$force")
    }

    @JvmStatic private external fun nativeInstall(): Int
    @JvmStatic private external fun nativeIsAudiorecordHooked(): Boolean
    @JvmStatic private external fun nativeSetDecision(decision: Int)
    @JvmStatic private external fun nativeSetMixEnabled(enabled: Boolean)
    @JvmStatic private external fun nativeSetPcmFd(fd: Int, sampleRate: Int, channels: Int)
    @JvmStatic private external fun nativeDrainStats(): LongArray?
    @JvmStatic private external fun nativeDupFd(fd: java.io.FileDescriptor): Int
}
