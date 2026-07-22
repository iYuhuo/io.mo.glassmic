package io.mo.glassmic.data.runtime

import io.mo.glassmic.core.model.PlaybackPolicy
import io.mo.glassmic.core.model.RuntimeState
import io.mo.glassmic.core.model.SourceType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 运行态——进程内单例。
 *
 * 重要：当用户开启虚拟麦克风时，外部应当：
 * 1. 先校验 onboarding / safe_mode / 权限
 * 2. 调用 BootGate.markEnabledForThisBoot()
 * 3. 启动 GlassForegroundService
 * 4. 最后才调用 [setEnabled]
 */
@Singleton
class RuntimeStateHolder @Inject constructor() {

    private val _flow = MutableStateFlow(RuntimeState())
    val flow: StateFlow<RuntimeState> = _flow.asStateFlow()
    val value: RuntimeState get() = _flow.value

    fun setEnabled(enabled: Boolean) = _flow.update {
        if (enabled) {
            it.copy(enabled = true)
        } else {
            it.copy(
                enabled = false,
                realMicPassThrough = false
            )
        }
    }

    fun setSafeMode(active: Boolean) = _flow.update { it.copy(safeMode = active) }

    fun setSource(
        type: SourceType,
        groupId: String? = null,
        audioId: String? = null,
        durationMs: Long = 0L
    ) = _flow.update {
        it.copy(
            currentSourceType = type,
            currentGroupId = groupId,
            currentAudioId = audioId,
            durationMs = durationMs,
            positionMs = 0L,
            realMicPassThrough = if (type == SourceType.FILE || type == SourceType.TTS) {
                it.realMicPassThrough
            } else {
                false
            }
        )
    }

    fun setRealMicPassThrough(active: Boolean) =
        _flow.update { it.copy(realMicPassThrough = active) }

    fun setPosition(positionMs: Long) =
        _flow.update { it.copy(positionMs = positionMs) }

    fun setPaused(paused: Boolean) =
        _flow.update { it.copy(paused = paused) }

    fun setPolicy(policy: PlaybackPolicy) =
        _flow.update { it.copy(playbackPolicy = policy) }

    fun setFloatingVisible(visible: Boolean) =
        _flow.update { it.copy(floatingWindowVisible = visible) }

    fun setError(msg: String?) = _flow.update { it.copy(lastError = msg) }

    /**
     * 通知作用域内进程：立刻打断录音，并在 [blockDurationMs] 内禁止重开录音。
     * @return 新的 forceReleaseToken
     */
    fun armMicRelease(blockDurationMs: Long = 5_000L): Long {
        var next = 0L
        val until = System.currentTimeMillis() + blockDurationMs.coerceIn(1_000L, 60_000L)
        _flow.update {
            next = it.forceReleaseToken + 1L
            it.copy(
                forceReleaseToken = next,
                recordingBlockUntilMs = until,
            )
        }
        return next
    }

    fun recordingBlockUntilMs(): Long = value.recordingBlockUntilMs

    fun reset() {
        _flow.value = RuntimeState()
    }
}
