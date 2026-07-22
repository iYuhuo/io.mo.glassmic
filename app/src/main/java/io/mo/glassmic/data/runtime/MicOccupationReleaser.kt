package io.mo.glassmic.data.runtime

import android.content.Context
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.core.Constants
import io.mo.glassmic.log.GlassLog
import io.mo.glassmic.provider.RuntimeProvider
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 悬浮窗「解除占用」：只针对作用域内 App。
 *
 * - 递增 forceReleaseToken + 设置数秒录音封锁窗
 * - 通知 hook 进程 stop/release，并在封锁窗内拒绝 startRecording
 * - **不再** kill audioserver / force-stop（避免系统崩溃或杀 App）
 */
@Singleton
class MicOccupationReleaser @Inject constructor(
    @ApplicationContext private val context: Context,
    private val runtime: RuntimeStateHolder,
) {

    data class Result(
        val ok: Boolean,
        val message: String,
    )

    private val inFlight = AtomicBoolean(false)
    @Volatile private var lastAttemptElapsedMs: Long = 0L

    fun releaseBlocking(onProceed: (() -> Unit)? = null): Result {
        val now = SystemClock.elapsedRealtime()
        val since = now - lastAttemptElapsedMs
        if (lastAttemptElapsedMs > 0L && since < COOLDOWN_MS) {
            val leftSec = ((COOLDOWN_MS - since + 999) / 1000).toInt().coerceAtLeast(1)
            return Result(
                ok = false,
                message = context.getString(io.mo.glassmic.R.string.float_release_mic_cooldown, leftSec),
            )
        }
        if (!inFlight.compareAndSet(false, true)) {
            return Result(
                ok = false,
                message = context.getString(io.mo.glassmic.R.string.float_release_mic_busy),
            )
        }
        lastAttemptElapsedMs = now
        return try {
            val token = runtime.armMicRelease(Constants.MIC_RELEASE_BLOCK_MS)
            runCatching { onProceed?.invoke() }
            RuntimeProvider.notifyForceReleaseChanged(context)
            val sec = (Constants.MIC_RELEASE_BLOCK_MS / 1000L).toInt()
            GlassLog.b("MicRelease") { "armed scope mic-release token=$token block=${sec}s" }
            Result(
                ok = true,
                message = context.getString(io.mo.glassmic.R.string.float_release_mic_scoped_blocked, sec),
            )
        } finally {
            inFlight.set(false)
        }
    }

    private companion object {
        const val COOLDOWN_MS = 3_000L
    }
}
