package io.mo.glassmic.xposed

import android.media.MediaRecorder
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * 跟踪 [MediaRecorder] 录音会话，供一键解除麦克风占用；
 * 封锁窗内拒绝 [MediaRecorder.start]。
 */
object MediaRecorderHook {

    private const val TAG = "Yuhuo-MediaRec"

    private val live = Collections.synchronizedMap(WeakHashMap<MediaRecorder, Boolean>())

    @Volatile private var installed = false
    @Volatile private var callerPkg: String = ""

    fun install(api: XposedInterface?, callerPkg: String) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            installed = true
            this.callerPkg = callerPkg
        }
        if (api != null) {
            installLibxposed(api, callerPkg)
        } else {
            installLegacy(callerPkg)
        }
        Log.i(TAG, "installed in $callerPkg")
    }

    private fun installLibxposed(api: XposedInterface, callerPkg: String) {
        fun hookNamed(name: String, blockStart: Boolean = false) {
            val method = findMethod(name) ?: return
            api.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    (chain.getThisObject() as? MediaRecorder)?.let { track(it) }
                    if (blockStart) {
                        // 刷新封锁状态
                        runCatching {
                            val ctx = chain.getThisObject()?.let {
                                // MediaRecorder 无 Context；用当前包向 bridge 轮询由 poller 负责
                            }
                        }
                        if (MicForceRelease.shouldBlockRecording()) {
                            throw IllegalStateException("yuhuo: recording blocked briefly after mic release")
                        }
                    }
                    chain.proceed()
                }
        }
        hookNamed("start", blockStart = true)
        hookNamed("prepare")
        hookNamed("release")
        api.log(Log.INFO, TAG, "libxposed MediaRecorder hooks in $callerPkg")
    }

    private fun installLegacy(callerPkg: String) {
        val trackCb = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                (param.thisObject as? MediaRecorder)?.let { track(it) }
            }
        }
        val startCb = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                (param.thisObject as? MediaRecorder)?.let { track(it) }
                if (MicForceRelease.shouldBlockRecording()) {
                    param.throwable =
                        IllegalStateException("yuhuo: recording blocked briefly after mic release")
                }
            }
        }
        runCatching {
            XposedBridge.hookAllConstructors(MediaRecorder::class.java, trackCb)
        }
        runCatching {
            XposedHelpers.findAndHookMethod(MediaRecorder::class.java, "start", startCb)
        }.onFailure {
            XposedBridge.log("$TAG: hook start failed: ${it.message}")
        }
        for (name in listOf("prepare", "release")) {
            runCatching {
                XposedHelpers.findAndHookMethod(MediaRecorder::class.java, name, trackCb)
            }.onFailure {
                XposedBridge.log("$TAG: hook $name failed: ${it.message}")
            }
        }
        XposedBridge.log("$TAG: legacy MediaRecorder hooks in $callerPkg")
    }

    private fun findMethod(name: String): Method? =
        runCatching { MediaRecorder::class.java.getMethod(name) }
            .recoverCatching { MediaRecorder::class.java.getDeclaredMethod(name) }
            .getOrNull()

    private fun track(rec: MediaRecorder) {
        live[rec] = java.lang.Boolean.TRUE
    }

    fun forceReleaseAll() {
        val snapshot = synchronized(live) { live.keys.toList() }
        var n = 0
        for (rec in snapshot) {
            runCatching {
                runCatching { rec.stop() }
                runCatching { rec.reset() }
                runCatching { rec.release() }
                n++
            }.onFailure {
                Log.w(TAG, "release one MediaRecorder failed: ${it.message}")
            }
            live.remove(rec)
        }
        Log.i(TAG, "forceReleaseAll released=$n / ${snapshot.size}")
    }
}
