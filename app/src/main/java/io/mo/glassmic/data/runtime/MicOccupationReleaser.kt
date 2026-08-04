package io.mo.glassmic.data.runtime

import android.content.Context
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.log.GlassLog
import io.mo.glassmic.provider.RuntimeProvider
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 悬浮窗「解除占用 / 持续禁录」开关：只针对作用域内 App。
 *
 * - 开启：递增 forceReleaseToken + 设置持续禁录；hook 打断当前录音并拒绝重开
 * - 关闭：清掉禁录窗，允许重新开录
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
        /** 切换后是否处于持续禁录态。 */
        val blocked: Boolean = false,
    )

    private val inFlight = AtomicBoolean(false)
    @Volatile private var lastAttemptElapsedMs: Long = 0L

    /**
     * 切换持续禁录。已禁录时再次调用即恢复；未禁录时打断并持续禁止录音。
     */
    fun toggleBlocking(onProceed: (() -> Unit)? = null): Result {
        val now = SystemClock.elapsedRealtime()
        val since = now - lastAttemptElapsedMs
        if (lastAttemptElapsedMs > 0L && since < COOLDOWN_MS) {
            val leftSec = ((COOLDOWN_MS - since + 999) / 1000).toInt().coerceAtLeast(1)
            return Result(
                ok = false,
                message = context.getString(io.mo.glassmic.R.string.float_release_mic_cooldown, leftSec),
                blocked = runtime.isRecordingBlocked(),
            )
        }
        if (!inFlight.compareAndSet(false, true)) {
            return Result(
                ok = false,
                message = context.getString(io.mo.glassmic.R.string.float_release_mic_busy),
                blocked = runtime.isRecordingBlocked(),
            )
        }
        lastAttemptElapsedMs = now
        return try {
            if (runtime.isRecordingBlocked()) {
                runtime.clearMicRelease()
                RuntimeProvider.notifyForceReleaseChanged(context)
                GlassLog.b("MicRelease") { "cleared scope mic-block" }
                Result(
                    ok = true,
                    message = context.getString(io.mo.glassmic.R.string.float_release_mic_restored),
                    blocked = false,
                )
            } else {
                val token = runtime.armMicRelease()
                runCatching { onProceed?.invoke() }
                RuntimeProvider.notifyForceReleaseChanged(context)
                GlassLog.b("MicRelease") { "armed scope mic-block token=$token (indefinite)" }
                Result(
                    ok = true,
                    message = context.getString(io.mo.glassmic.R.string.float_release_mic_scoped_blocked),
                    blocked = true,
                )
            }
        } finally {
            inFlight.set(false)
        }
    }

    private companion object {
        /** 防连点；切换操作本身很快，冷却比原先 3s 短。 */
        const val COOLDOWN_MS = 800L
    }
}
