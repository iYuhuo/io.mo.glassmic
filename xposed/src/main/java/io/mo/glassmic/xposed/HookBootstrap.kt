package io.mo.glassmic.xposed

import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedInterface
import kotlin.concurrent.thread

/**
 * 进程 attach 后 **始终** 安装 hook（LSPosed 规范做法）。
 *
 * 是否拦截麦克风由运行时 [XBridge.resolveSource] 决定，不在安装阶段 gate。
 * 这样微信/QQ 先启动、后开 yuhuo 主开关时，只要目标 App 仍在运行，
 * poller 即可在 decision=FILE 时接上 PCM pipe，无需强制重启。
 *
 * 若 yuhuo 服务完全未运行，decision 保持 REAL_MIC，hook 存在但不改写数据。
 */
object HookBootstrap {

    private const val TAG = "Yuhuo-Bootstrap"

    private val initLock = Any()
    @Volatile private var started = false
    @Volatile private var hooksInstalled = false

    fun start(ctx: Context, pkg: String, api: XposedInterface? = null) {
        if (XposedHookGate.shouldSkipPackage(pkg)) return
        synchronized(initLock) {
            if (started) return
            started = true
        }
        val appCtx = ctx.applicationContext ?: ctx
        thread(name = "Yuhuo-Bootstrap-$pkg", isDaemon = true, priority = Thread.MIN_PRIORITY) {
            try {
                XBridge.currentPackage = pkg
                installHooksOnce(appCtx, pkg, api)
            } catch (t: Throwable) {
                Log.w(TAG, "bootstrap failed for $pkg: ${t.message}")
            }
        }
    }

    private fun installHooksOnce(appCtx: Context, pkg: String, api: XposedInterface?) {
        if (hooksInstalled) return
        synchronized(initLock) {
            if (hooksInstalled) return
            hooksInstalled = true
        }

        val explained = runCatching { XBridge.queryResolveExplanation(appCtx, pkg) }
            .getOrDefault(io.mo.glassmic.core.model.SourceType.REAL_MIC to "bootstrap_query_error")
        Log.i(TAG, "installing hooks for $pkg resolve=${explained.first} reason=${explained.second}")

        runCatching {
            if (api != null) {
                AudioRecordHook.install(api, appCtx, pkg)
            } else {
                LegacyAudioRecordHook.install(appCtx, pkg)
            }
            NativeAAudioHook.install(appCtx, pkg)
            val nativeOk = runCatching { NativeAAudioHook.isReady() }.getOrDefault(false)
            val audiorecordHooked = runCatching { NativeAAudioHook.isAudiorecordHooked() }.getOrDefault(false)
            val latest = runCatching { XBridge.queryResolveExplanation(appCtx, pkg) }
                .getOrDefault(explained)
            XBridge.pingModuleLoaded(
                appCtx,
                pkg,
                api = if (api != null) 101 else 82,
                nativeOk = nativeOk,
                audiorecordHooked = audiorecordHooked,
                resolvedSource = latest.first,
                resolveReason = latest.second
            )
            Log.i(
                TAG,
                "hooks ready in $pkg resolve=${latest.first} reason=${latest.second} " +
                    "native=$nativeOk audiorecord=$audiorecordHooked"
            )
        }.onFailure {
            Log.w(TAG, "install hooks in $pkg failed: ${it.message}")
            synchronized(initLock) { hooksInstalled = false }
        }
    }
}
