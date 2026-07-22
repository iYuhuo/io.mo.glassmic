package io.mo.glassmic.data.audio

import io.mo.glassmic.audio.BufferedPcmSource
import io.mo.glassmic.audio.FileAudioSource
import io.mo.glassmic.audio.Pcm16Converter
import io.mo.glassmic.audio.SharedPcmPublisher
import io.mo.glassmic.audio.tts.PcmSink
import io.mo.glassmic.audio.tts.TtsRequest
import io.mo.glassmic.audio.tts.TtsSynthesizerFactory
import io.mo.glassmic.core.model.SourceType
import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.data.db.AudioDao
import io.mo.glassmic.data.runtime.RuntimeStateHolder
import io.mo.glassmic.log.GlassLog
import io.mo.glassmic.service.FloatingWindowConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
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
    private val dao: AudioDao,
    private val ttsFactory: TtsSynthesizerFactory
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

        // 排空旧帧/转换器，避免切歌时闪出上一段尾巴（不必拆 pipe）
        publisher.drainForSourceSwitch()
        publisher.setSource(src, groupId = clip.groupId, audioId = clip.id)
        publisher.setPaused(startPaused)
        configStore.update {
            it.setCurrentGroupId(clip.groupId)
            it.setCurrentAudioId(clip.id)
        }
        applyLocalMonitorPreference()
        true
    }

    // ============ 文字转语音（先生成后播放） ============
    enum class TtsGen { IDLE, GENERATING, READY, FAILED }

    private val _ttsGen = MutableStateFlow(TtsGen.IDLE)
    /** 生成状态：供悬浮窗决定「播放」按钮是否可用。 */
    val ttsGen: StateFlow<TtsGen> = _ttsGen.asStateFlow()

    @Volatile private var generatedTtsPcm: ByteArray? = null
    private val _generatedTtsText = MutableStateFlow<String?>(null)
    /** 与缓存 PCM 对应的原文；UI 用来判断草稿是否已过期。 */
    val generatedTtsText: StateFlow<String?> = _generatedTtsText.asStateFlow()

    /** 缓存的 PCM 是否就是 [text]（trim 后比较）。 */
    fun hasGeneratedFor(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.isNotEmpty() &&
            generatedTtsPcm != null &&
            _generatedTtsText.value == trimmed
    }

    /**
     * 生成 [text] 的语音并缓存为主格式 PCM（不立即播放）。成功返回 true。
     * 之后调用 [playGeneratedTts] 才真正喂给目标 App，可重复播放。
     */
    suspend fun generateTts(text: String): Boolean = withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return@withContext false
        _ttsGen.value = TtsGen.GENERATING
        generatedTtsPcm = null
        _generatedTtsText.value = null
        val ttsCfg = configStore.current().tts
        val synth = ttsFactory.current()
        val req = TtsRequest(
            text = trimmed,
            rate = ttsCfg.speechRate.takeIf { it > 0f } ?: 1f,
            pitch = ttsCfg.pitch.takeIf { it > 0f } ?: 1f,
            voice = ttsCfg.voice
        )
        try {
            val pcm = withTimeoutOrNull(30_000L) { synthesizeToMasterPcm(synth, req) }
            if (pcm != null && pcm.isNotEmpty()) {
                generatedTtsPcm = pcm
                _generatedTtsText.value = trimmed
                _ttsGen.value = TtsGen.READY
                GlassLog.b("Playback") { "TTS 已生成: ${trimmed.take(20)} (${pcm.size} B)" }
                true
            } else {
                _ttsGen.value = TtsGen.FAILED
                GlassLog.b("Playback") { "TTS 生成失败/超时: ${trimmed.take(20)}" }
                false
            }
        } catch (e: CancellationException) {
            _ttsGen.value = TtsGen.IDLE
            GlassLog.b("Playback") { "TTS 生成已取消" }
            throw e
        }
    }

    /**
     * 播放 [text]：草稿与上次生成不一致时先合成再播，避免误播旧文案。
     * 文本未变则直接重播缓存。
     */
    suspend fun playTts(text: String): Boolean = withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return@withContext false
        if (!hasGeneratedFor(trimmed)) {
            if (!generateTts(trimmed)) return@withContext false
        }
        playGeneratedTts()
    }

    /**
     * 切到音频库时：若当前在播 TTS，恢复上次选中的虚拟音源并暂停（不自动播放）。
     * 已是文件音源则只保证暂停，避免与 TTS「同时在播」的错觉。
     */
    suspend fun switchToLibrarySource(): Boolean = withContext(Dispatchers.IO) {
        clearTtsDelayCountdown()
        ttsFactory.cancelAll()
        if (_ttsGen.value == TtsGen.GENERATING) {
            _ttsGen.value = if (generatedTtsPcm != null) TtsGen.READY else TtsGen.IDLE
        }
        when (runtime.value.currentSourceType) {
            SourceType.FILE -> {
                if (!publisher.isPaused) pause()
                true
            }
            else -> {
                val clipId = configStore.current().currentAudioId.takeIf { it.isNotBlank() }
                if (clipId != null) {
                    setCurrentClip(clipId, startPaused = true)
                } else {
                    // 没有上次音源：暂停当前 TTS，用舒适噪声保活，切真麦/纯静音会触发录音错误
                    if (runtime.value.currentSourceType == SourceType.TTS && !publisher.isPaused) {
                        pause()
                    }
                    false
                }
            }
        }
    }

    private val _ttsDelayRemainingMs = MutableStateFlow(0L)
    /** 延时播放倒计时剩余毫秒，0 表示当前没有待播放的语音。供悬浮窗显示倒计时。 */
    val ttsDelayRemainingMs: StateFlow<Long> = _ttsDelayRemainingMs.asStateFlow()

    /**
     * 把上次生成的语音喂给目标 App，可重复调用重播。
     *
     * 配置了 tts.delay_ms 时先倒计时再出声，留出切到目标 App 的时间；
     * 倒计时期间可用 [cancelDelayedTts] 取消。返回 true 表示语音已真正开始播放
     * （被取消则返回 false）。
     */
    suspend fun playGeneratedTts(): Boolean = withContext(Dispatchers.IO) {
        val pcm = generatedTtsPcm ?: return@withContext false

        val delayMs = configStore.current().tts.delayMs.toLong().coerceAtLeast(0L)
        if (delayMs > 0) {
            // 已有倒计时在跑时不叠加，交给调用方先取消
            if (_ttsDelayRemainingMs.value > 0L) return@withContext false
            val deadline = System.currentTimeMillis() + delayMs
            try {
                var remaining = delayMs
                while (remaining > 0) {
                    _ttsDelayRemainingMs.value = remaining
                    // 100ms 一档刷新，倒计时读数够跟手又不至于频繁重组
                    delay(remaining.coerceAtMost(COUNTDOWN_TICK_MS))
                    remaining = deadline - System.currentTimeMillis()
                }
            } catch (e: CancellationException) {
                // 取消后要清干净，否则下次播放会被上面的"已有倒计时"分支挡掉
                _ttsDelayRemainingMs.value = 0L
                GlassLog.b("Playback") { "TTS 延时播放已取消" }
                throw e
            }
            _ttsDelayRemainingMs.value = 0L
        }

        // 先暂停、拆旧 pipe、再挂上新 TTS——第二次播放必须新 pipe，否则结尾会再次读出上一段开头
        // 本地监听已独立时钟：不再为此关监听（注入无 consumer 时也不会空转超前）
        publisher.setPaused(true)
        publisher.resetConsumersForNewSession()
        publisher.setSource(BufferedPcmSource(pcm))
        publisher.setPaused(false)
        // 不清除 currentAudioId：保留上次虚拟音源，切回音频库时可直接恢复（暂停态）
        applyLocalMonitorPreferenceBlocking()
        true
    }

    /** 倒计时归零，[playGeneratedTts] 的协程被取消后由调用方触发。 */
    fun clearTtsDelayCountdown() {
        _ttsDelayRemainingMs.value = 0L
    }

    /** 合成并转成主时钟格式（48kHz 单声道 PCM16）。 */
    private suspend fun synthesizeToMasterPcm(
        synth: io.mo.glassmic.audio.tts.SpeechSynthesizer,
        req: TtsRequest
    ): ByteArray? = suspendCancellableCoroutine { cont ->
        val out = ByteArrayOutputStream()
        var converter: Pcm16Converter? = null
        val sink = object : PcmSink {
            override fun onFormat(sampleRate: Int, channels: Int) {
                converter = Pcm16Converter(
                    sourceSampleRate = sampleRate.coerceAtLeast(8_000),
                    sourceChannels = channels.coerceAtLeast(1),
                    targetSampleRate = MASTER_SAMPLE_RATE,
                    targetChannels = MASTER_CHANNELS
                )
            }
            override fun onPcm(chunk: ByteArray) {
                val c = converter ?: return
                out.write(c.convert(chunk))
            }
            override fun onDone() {
                if (cont.isActive) cont.resumeWith(Result.success(out.toByteArray()))
            }
            override fun onError(message: String) {
                if (cont.isActive) cont.resumeWith(Result.success(null))
            }
        }
        synth.synthesize(req, sink)
        cont.invokeOnCancellation { synth.cancel() }
    }

    private companion object {
        const val MASTER_SAMPLE_RATE = 48_000
        const val MASTER_CHANNELS = 1
        const val COUNTDOWN_TICK_MS = 100L
    }

    /** 切回真实麦克风：关闭虚拟注入，hook 透传真麦。 */
    suspend fun setRealMic() = withContext(Dispatchers.IO) {
        runtime.setRealMicPassThrough(false)
        publisher.setLocalMonitor(false)
        publisher.switchToRealMic()
        configStore.update {
            it.setCurrentGroupId("")
            it.setCurrentAudioId("")
        }
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

    val isLocalMonitorEnabled: Boolean get() = publisher.isLocalMonitorEnabled

    /** 运行时是否正在本地监听（供 UI，勿用偏好配置冒充）。 */
    val localMonitorEnabled: StateFlow<Boolean> = publisher.localMonitorEnabled

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
        val activeVirtual = rt.enabled && !rt.safeMode &&
            (rt.currentSourceType == SourceType.FILE || rt.currentSourceType == SourceType.TTS)
        publisher.setLocalMonitor(preferred && activeVirtual)
    }

    suspend fun isLocalMonitorPreferred(): Boolean = withContext(Dispatchers.IO) {
        configStore.current().floatingWindow.localMonitor
    }

    suspend fun restorePersistedClip(): Boolean {
        val clipId = configStore.current().currentAudioId.takeIf { it.isNotBlank() } ?: return false
        return setCurrentClip(clipId, startPaused = true)
    }

    val isPaused: Boolean get() = publisher.isPaused

    fun pause() {
        publisher.setPaused(true)
        GlassLog.b("Playback") { "暂停" }
    }

    fun resume() {
        runtime.setRealMicPassThrough(false)
        publisher.setPaused(false)
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
