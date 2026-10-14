package io.mo.glassmic.audio

import io.mo.glassmic.core.model.SourceType
import java.nio.ByteBuffer

/**
 * 播放一段已经生成好的、主时钟格式（48kHz 单声道 PCM16）的音频。
 *
 * 用于「先生成后播放」：TTS 合成结果先转成主格式缓存，用户点播放时用本 source 喂给管线，
 * 可重复播放（每次重新 setSource 或 [replay] 从头开始）。播完返回 -1（EOF）交由播放策略处理。
 */
class BufferedPcmSource(private val pcm: ByteArray) : AudioSourceProvider {

    override val type = SourceType.TTS

    private var pos = 0
    /**
     * 一旦读到 EOF 即锁死，拒绝 [reset]/[seekTo] 把进度拉回开头。
     * 否则「切真麦」竞态里一次误 reset/seek(0) 会再灌出不到一秒的 TTS 开头。
     * 显式循环请走 [replay]。
     */
    @Volatile private var finished = false

    override suspend fun read(out: ByteBuffer, sampleRate: Int, channels: Int): Int {
        if (finished || pos >= pcm.size) {
            finished = true
            return -1
        }
        val remaining = pcm.size - pos
        // 按 2 字节（PCM16 单声道帧）对齐写出
        val toWrite = minOf(out.remaining(), remaining).let { it - (it % BYTES_PER_FRAME) }
        if (toWrite <= 0) {
            if (remaining < BYTES_PER_FRAME) {
                finished = true
                return -1
            }
            return 0
        }
        out.put(pcm, pos, toWrite)
        pos += toWrite
        if (pos >= pcm.size) finished = true
        return toWrite
    }

    override fun positionMs(): Long = pos.toLong() * 1000 / (MASTER_SAMPLE_RATE * BYTES_PER_FRAME)
    override fun durationMs(): Long = pcm.size.toLong() * 1000 / (MASTER_SAMPLE_RATE * BYTES_PER_FRAME)

    /** 默认拒绝：EOF 后误调 reset 不得重头播放。循环策略请用 [replay]。 */
    override fun reset() = Unit

    /** 循环播放：清除 EOF 锁并回到开头。 */
    fun replay() {
        pos = 0
        finished = false
    }

    /** 本地监听独立时钟：共享同一段 PCM，进度互不影响。 */
    fun fork(): BufferedPcmSource {
        val other = BufferedPcmSource(pcm)
        other.pos = pos
        other.finished = finished
        return other
    }

    /** 跳转到指定毫秒，按帧对齐并夹在有效范围内。EOF 后忽略，避免播完再被 UI seek(0)。 */
    fun seekTo(positionMs: Long) {
        if (finished) return
        val bytePos = positionMs.coerceAtLeast(0L) * MASTER_SAMPLE_RATE * BYTES_PER_FRAME / 1000
        val aligned = bytePos - (bytePos % BYTES_PER_FRAME)
        pos = aligned.coerceIn(0L, pcm.size.toLong()).toInt()
        if (pos >= pcm.size) finished = true
    }

    private companion object {
        const val MASTER_SAMPLE_RATE = 48_000
        const val BYTES_PER_FRAME = 2   // 48k 单声道 PCM16
    }
}
