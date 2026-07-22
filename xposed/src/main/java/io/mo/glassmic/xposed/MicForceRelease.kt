package io.mo.glassmic.xposed

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.mo.glassmic.core.Constants
import kotlin.concurrent.thread

/**
 * 作用域内 App：打断已跟踪录音会话，并在封锁窗内禁止重开录音。
 *
 * - 非作用域进程：忽略 token / block（不碰系统其它 App）
 * - 释放必须异步，避免在 read() 钩子栈内同步 release
 */
object MicForceRelease {

    private const val TAG = "Yuhuo-MicRelease"
    private const val POLL_MS = 200L
    private const val RELEASE_DELAY_MS = 30L

    @Volatile private var lastHandledToken: Long = 0L
    @Volatile private var blockUntilMs: Long = 0L
    @Volatile private var inScope: Boolean = false
    @Volatile private var started = false
    @Volatile private var callerPkg: String = ""
    @Volatile private var lastContinuousReleaseMs: Long = 0L

    private val workerThread = HandlerThread("Yuhuo-MicRelease").apply { start() }
    private val worker = Handler(workerThread.looper)

    fun startWatching(ctx: Context, pkg: String) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            callerPkg = pkg
        }
        val appCtx = ctx.applicationContext ?: ctx
        val uri = Uri.parse("content://${Constants.PROVIDER_RUNTIME}/resolve")
        runCatching {
            appCtx.contentResolver.registerContentObserver(
                uri,
                true,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        checkNow(appCtx, pkg, "observer")
                    }
                }
            )
            Log.i(TAG, "ContentObserver registered in $pkg")
        }.onFailure {
            Log.w(TAG, "registerContentObserver failed: ${it.message}")
        }
        thread(name = "Yuhuo-MicReleasePoll-$pkg", isDaemon = true, priority = Thread.MIN_PRIORITY) {
            while (true) {
                try {
                    checkNow(appCtx, pkg, "poll")
                } catch (t: Throwable) {
                    Log.w(TAG, "poll error: ${t.message}")
                }
                try {
                    Thread.sleep(POLL_MS)
                } catch (_: InterruptedException) {
                    return@thread
                }
            }
        }
    }

    /** 当前进程是否处于「作用域内 + 录音封锁窗」。供 startRecording 钩子拒绝开录。 */
    fun shouldBlockRecording(): Boolean {
        if (!inScope) return false
        return System.currentTimeMillis() < blockUntilMs
    }

    fun applyRemoteState(
        token: Long,
        scoped: Boolean,
        blockUntil: Long,
        reason: String = "resolve",
    ) {
        inScope = scoped
        blockUntilMs = blockUntil
        if (!scoped) return
        val blocking = System.currentTimeMillis() < blockUntil
        if (token > lastHandledToken) {
            lastHandledToken = token
            Log.e(TAG, "schedule force-release token=$token blockUntil=$blockUntil reason=$reason")
            worker.postDelayed({
                runReleasePass(token)
                worker.postDelayed({ runReleasePass(token) }, 200L)
            }, RELEASE_DELAY_MS)
        } else if (blocking) {
            // 封锁窗内持续掐断，防止秒重连（节流，避免刷爆）
            val nowUp = SystemClock.uptimeMillis()
            if (nowUp - lastContinuousReleaseMs >= 400L) {
                lastContinuousReleaseMs = nowUp
                worker.post { runReleasePass(token) }
            }
        }
    }

    fun checkNow(ctx: Context, pkg: String, reason: String) {
        val st = queryState(ctx, pkg)
        applyRemoteState(st.token, st.inScope, st.blockUntilMs, reason)
    }

    private data class RemoteState(
        val token: Long,
        val inScope: Boolean,
        val blockUntilMs: Long,
    )

    private fun queryState(ctx: Context, pkg: String): RemoteState {
        return runCatching {
            val extras = ctx.contentResolver.call(
                Constants.PROVIDER_RUNTIME,
                Constants.METHOD_FORCE_RELEASE_TOKEN,
                pkg,
                null
            ) ?: ctx.contentResolver.call(
                Uri.parse("content://${Constants.PROVIDER_RUNTIME}"),
                Constants.METHOD_FORCE_RELEASE_TOKEN,
                pkg,
                null
            )
            RemoteState(
                token = extras?.getLong(Constants.KEY_FORCE_RELEASE_TOKEN, 0L) ?: 0L,
                inScope = extras?.getBoolean(Constants.KEY_IN_SCOPE, false) ?: false,
                blockUntilMs = extras?.getLong(Constants.KEY_RECORDING_BLOCK_UNTIL, 0L) ?: 0L,
            )
        }.onFailure {
            Log.e(TAG, "queryState failed: ${it.message}")
        }.getOrDefault(RemoteState(0L, false, 0L))
    }

    private fun runReleasePass(token: Long) {
        if (!inScope) return
        val t0 = SystemClock.uptimeMillis()
        Log.e(TAG, "force-release execute token=$token pkg=$callerPkg")
        runCatching { AudioRecordHook.forceReleaseAll() }
            .onFailure { Log.e(TAG, "AudioRecordHook release failed: ${it.message}") }
        runCatching { LegacyAudioRecordHook.forceReleaseAll() }
            .onFailure { Log.e(TAG, "LegacyAudioRecordHook release failed: ${it.message}") }
        runCatching { MediaRecorderHook.forceReleaseAll() }
            .onFailure { Log.e(TAG, "MediaRecorderHook release failed: ${it.message}") }
        runCatching { NativeAAudioHook.forceReleaseAll() }
            .onFailure { Log.e(TAG, "NativeAAudioHook release failed: ${it.message}") }
        Log.e(TAG, "force-release done token=$token in ${SystemClock.uptimeMillis() - t0}ms")
    }

    /** @deprecated 用 [applyRemoteState] */
    fun consumeIfNeeded(token: Long, reason: String = "resolve"): Boolean {
        applyRemoteState(token, scoped = true, blockUntil = blockUntilMs, reason = reason)
        return true
    }
}
