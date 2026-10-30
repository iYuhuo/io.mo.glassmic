package io.mo.glassmic.audio.tts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一次 AI TTS 生成的进度，供悬浮窗实时显示（LLM 优化、Fish 请求、耗时、错误）。
 * 每行前缀为距本次生成开始的秒数。
 */
@Singleton
class TtsProgress @Inject constructor() {

    /**
     * 自动标签结果：[original] 为用户输入，[tagged] 为实际送去合成的文本。
     * [seq] 每次递增：同一段文本再次优化出相同结果时 StateFlow 也会重新发出，悬浮窗才会再次回填。
     */
    data class Enhanced(val original: String, val tagged: String, val seq: Long)

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private val _enhanced = MutableStateFlow<Enhanced?>(null)
    val enhanced: StateFlow<Enhanced?> = _enhanced.asStateFlow()

    @Volatile private var startMs = 0L
    private val enhancedSeq = AtomicLong()

    fun start() {
        startMs = System.currentTimeMillis()
        _lines.value = emptyList()
    }

    fun step(message: String) {
        val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(0L) / 1000.0
        val line = "%.1fs  %s".format(elapsed, message)
        _lines.update { (it + line).takeLast(MAX_LINES) }
    }

    fun publishEnhanced(original: String, tagged: String) {
        _enhanced.value = Enhanced(original, tagged, enhancedSeq.incrementAndGet())
    }

    private companion object {
        const val MAX_LINES = 6
    }
}
