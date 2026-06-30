package io.mo.glassmic.data.audio

import io.mo.glassmic.audio.FileAudioSource
import io.mo.glassmic.audio.SharedPcmPublisher
import io.mo.glassmic.audio.SilenceSource
import io.mo.glassmic.core.model.SourceType
import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.data.db.AudioClipEntity
import io.mo.glassmic.data.db.AudioDao
import io.mo.glassmic.data.runtime.RuntimeStateHolder
import io.mo.glassmic.log.GlassLog
import io.mo.glassmic.service.FloatingWindowConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把 UI 的"设为当前音源"动作衔接到音频管线。
 *
 * - 修改 RuntimeStateHolder 当前音源
 * - 通知 SharedPcmPublisher 切换 source
 * - 持久化当前选中 ID 到 ConfigStore（用于重启后恢复显示）
 *
 * 不负责 BootGate / SafeMode 判断——这些在 EffectiveSourceResolver 里。
 */
@Singleton
class PlaybackController @Inject constructor(
    private val publisher: SharedPcmPublisher,
    private val resolver: AudioFileResolver,
    private val configStore: ConfigStore,
    private val runtime: RuntimeStateHolder,
    private val dao: AudioDao
) {

    /** 设置某个片段为当前虚拟麦克风音源。文件不存在则自动清掉数据库记录并返回 false。 */
    suspend fun setCurrentClip(clipId: String, startPaused: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val clip = dao.findClip(clipId) ?: return@withContext false
        val file = resolver.fileFor(clip.relativePath)
        if (!file.exists()) {
            GlassLog.b("Playback") { "音频文件丢失，清理记录: ${clip.relativePath}" }
            dao.deleteClip(clipId)
            return@withContext false
        }
        val src = runCatching { FileAudioSource(clip.toModel(), file.absolutePath) }
            .onFailure { GlassLog.b("Playback") { "FileAudioSource 创建失败: ${it.message}" } }
            .getOrNull() ?: return@withContext false

        publisher.setSource(src, groupId = clip.groupId, audioId = clip.id)
        publisher.setPaused(startPaused)
        configStore.update {
            it.setCurrentGroupId(clip.groupId)
            it.setCurrentAudioId(clip.id)
        }
        applyLocalMonitorPreference()
        applyRealMicMixPreference()
        true
    }

    /** 切回真实麦克风：释放当前 file source，运行态置 REAL_MIC，清空持久化选中。 */
    suspend fun setRealMic() = withContext(Dispatchers.IO) {
        runtime.setRealMicPassThrough(false)
        runtime.setRealMicMixWithDucking(false)
        publisher.setLocalMonitor(false)
        publisher.setPaused(false)
        publisher.setSource(SilenceSource)
        runtime.setSource(SourceType.REAL_MIC, groupId = null, audioId = null, durationMs = 0L)
        configStore.update {
            it.setCurrentGroupId("")
            it.setCurrentAudioId("")
        }
    }

    /**
     * 暂停虚拟音频并透传真实麦克风，保留当前音源与播放进度。
     * 悬浮窗/主页的播放暂停按钮与此等效；保留供程序化调用。
     */
    fun enableRealMicPassThrough(): Boolean {
        val rt = runtime.value
        if (!rt.enabled || rt.safeMode || rt.currentSourceType != SourceType.FILE) return false
        runtime.setRealMicMixWithDucking(false)
        publisher.setLocalMonitor(false)
        publisher.setPaused(true)
        runtime.setRealMicPassThrough(true)
        GlassLog.b("Playback") { "真实麦透传已开启" }
        return true
    }

    /** 关闭真实麦透传并恢复虚拟音频播放。 */
    suspend fun disableRealMicPassThrough() = withContext(Dispatchers.IO) {
        runtime.setRealMicPassThrough(false)
        publisher.setPaused(false)
        applyLocalMonitorPreference()
        applyRealMicMixPreference()
        GlassLog.b("Playback") { "真实麦透传已关闭，恢复虚拟音频" }
    }

    /** 开启/关闭麦声闪避（独立开关，不影响播放/暂停状态）。成功时返回 true。 */
    suspend fun setRealMicMixEnabled(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (enabled) {
            val rt = runtime.value
            if (!rt.enabled || rt.safeMode || rt.currentSourceType != SourceType.FILE) {
                return@withContext false
            }
            configStore.update {
                it.setFloatingWindow(it.floatingWindow.toBuilder().setRealMicMix(true))
            }
            runtime.setRealMicPassThrough(false)
            runtime.setRealMicMixWithDucking(true)
            GlassLog.b("Playback") { "真实麦混音闪避已开启" }
            true
        } else {
            configStore.update {
                it.setFloatingWindow(it.floatingWindow.toBuilder().setRealMicMix(false))
            }
            runtime.setRealMicMixWithDucking(false)
            GlassLog.b("Playback") { "真实麦混音闪避已关闭" }
            true
        }
    }

    /** 根据持久化偏好应用混音闪避状态。 */
    suspend fun applyRealMicMixPreference() = withContext(Dispatchers.IO) {
        applyRealMicMixRuntime(configStore.current().floatingWindow.realMicMix)
    }

    private fun applyRealMicMixRuntime(preferred: Boolean) {
        val rt = runtime.value
        val activeFile = rt.enabled && !rt.safeMode && rt.currentSourceType == SourceType.FILE && !rt.paused
        if (preferred && activeFile && !rt.realMicPassThrough) {
            runtime.setRealMicMixWithDucking(true)
        } else {
            runtime.setRealMicMixWithDucking(false)
        }
    }

    suspend fun isRealMicMixPreferred(): Boolean = withContext(Dispatchers.IO) {
        configStore.current().floatingWindow.realMicMix
    }

    suspend fun restorePersistedClip(): Boolean {
        val clipId = configStore.current().currentAudioId.takeIf { it.isNotBlank() } ?: return false
        return setCurrentClip(clipId, startPaused = true)
    }

    suspend fun listGroups() = withContext(Dispatchers.IO) {
        dao.observeGroups().first()
    }

    suspend fun listClips(): List<AudioClipEntity> = withContext(Dispatchers.IO) {
        dao.observeAllClips().first()
    }

    suspend fun setOutputVolume(volume: Float) = withContext(Dispatchers.IO) {
        val clamped = volume.coerceIn(
            FloatingWindowConstants.OUTPUT_VOLUME_MIN,
            FloatingWindowConstants.OUTPUT_VOLUME_MAX
        )
        publisher.setOutputVolumeRuntime(clamped)
        configStore.update {
            it.setFloatingWindow(it.floatingWindow.toBuilder().setOutputVolume(clamped))
        }
    }

    /** 拖动音量条时立即生效（不写盘）。 */
    fun setOutputVolumeRuntime(volume: Float) {
        publisher.setOutputVolumeRuntime(
            volume.coerceIn(
                FloatingWindowConstants.OUTPUT_VOLUME_MIN,
                FloatingWindowConstants.OUTPUT_VOLUME_MAX
            )
        )
    }

    fun outputVolumeBlocking(): Float = publisher.outputVolumeBlocking()

    suspend fun getClipDisplayName(clipId: String): String? = withContext(Dispatchers.IO) {
        dao.findClip(clipId)?.displayName
    }

    val isPaused: Boolean get() = publisher.isPaused

    val isLocalMonitorEnabled: Boolean get() = publisher.isLocalMonitorEnabled

    /** 仅切换运行时监听，不修改持久化偏好（如切回真实麦、关闭主开关）。 */
    fun setLocalMonitorRuntime(enabled: Boolean) {
        publisher.setLocalMonitor(enabled)
    }

    /** 持久化本地监听偏好，并按当前音源状态应用。 */
    suspend fun setLocalMonitorEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        configStore.update {
            it.setFloatingWindow(it.floatingWindow.toBuilder().setLocalMonitor(enabled))
        }
        applyLocalMonitorPreference()
    }

    /** 根据持久化偏好与当前运行态，决定是否开启本地监听。 */
    suspend fun applyLocalMonitorPreference() = withContext(Dispatchers.IO) {
        applyLocalMonitorPreferenceBlocking()
    }

    private fun applyLocalMonitorPreferenceBlocking() {
        val preferred = configStore.currentBlocking().floatingWindow.localMonitor
        val rt = runtime.value
        val activeFile = rt.enabled && !rt.safeMode && rt.currentSourceType == SourceType.FILE
        publisher.setLocalMonitor(preferred && activeFile)
    }

    suspend fun isLocalMonitorPreferred(): Boolean = withContext(Dispatchers.IO) {
        configStore.current().floatingWindow.localMonitor
    }

    fun pause() {
        publisher.setPaused(true)
        GlassLog.b("Playback") { "暂停" }
    }

    fun resume() {
        runtime.setRealMicPassThrough(false)
        publisher.setPaused(false)
        applyRealMicMixRuntime(configStore.currentBlocking().floatingWindow.realMicMix)
        applyLocalMonitorPreferenceBlocking()
        GlassLog.b("Playback") { "恢复" }
    }

    fun togglePause() {
        if (publisher.isPaused) resume() else pause()
    }

    suspend fun seekTo(positionMs: Long) = withContext(Dispatchers.IO) {
        publisher.seekCurrent(positionMs)
    }
}
