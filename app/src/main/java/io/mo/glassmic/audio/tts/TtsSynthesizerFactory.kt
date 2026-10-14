package io.mo.glassmic.audio.tts

import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.log.GlassLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 按当前配置选择语音合成引擎。
 *
 * - `tts.ai.enabled = true` → [AiTtsSynthesizer]（在线，配置始终保留）
 * - 否则 → [SystemTtsSynthesizer]（系统默认引擎，如 MultiTTS）
 */
@Singleton
class TtsSynthesizerFactory @Inject constructor(
    private val configStore: ConfigStore,
    private val system: SystemTtsSynthesizer,
    private val ai: AiTtsSynthesizer
) {
    /** 返回当前应使用的合成器（不持有状态，可直接调用 synthesize）。 */
    suspend fun current(): SpeechSynthesizer {
        val aiCfg = runCatching { configStore.current().tts.ai }.getOrNull()
        return if (aiCfg != null && aiCfg.enabled) {
            GlassLog.b("Tts") { "使用在线 AI TTS: ${aiCfg.provider}" }
            ai
        } else {
            GlassLog.b("Tts") { "使用系统 TTS（本地默认引擎）" }
            system
        }
    }

    /** 取消进行中的合成（不释放引擎，可继续用）。 */
    fun cancelAll() {
        runCatching { system.cancel() }
        runCatching { ai.cancel() }
    }

    fun releaseAll() {
        runCatching { system.release() }
        runCatching { ai.cancel() }
    }
}
