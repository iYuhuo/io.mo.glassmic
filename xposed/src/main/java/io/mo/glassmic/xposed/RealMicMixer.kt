package io.mo.glassmic.xposed

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 真实麦克风 + 虚拟音频混音，并对虚拟轨做侧链闪避，尽量保证人声清晰。
 *
 * 虚拟音源为 mono PCM16；[channels] 为真实录音声道数（游戏常为 stereo）。
 */
object RealMicMixer {

    private const val ATTACK = 0.55f
    private const val RELEASE = 0.12f
    private const val THRESHOLD = 550f
    private const val FULL_SCALE = 10_000f
    private const val MIN_DUCK = 0.03f
    private const val MAX_VIRTUAL = 0.55f

    @Volatile
    private var envelope = MAX_VIRTUAL

    fun resetEnvelope() {
        envelope = MAX_VIRTUAL
    }

    private fun readLeShort(buf: ByteArray, index: Int): Int =
        ((buf[index].toInt() and 0xFF) or (buf[index + 1].toInt() shl 8)).toShort().toInt()

    private fun writeLeShort(buf: ByteArray, index: Int, value: Int) {
        buf[index] = (value and 0xFF).toByte()
        buf[index + 1] = (value shr 8).toByte()
    }

    /** 与 native map_src_frame 一致。 */
    private fun mapVirtualFrame(frame: Int, srcFrames: Int, dstFrames: Int): Int {
        if (srcFrames <= 1 || dstFrames <= 1) return 0
        val mapped = (frame.toLong() * srcFrames / dstFrames).toInt()
        return if (mapped >= srcFrames) srcFrames - 1 else mapped
    }

    private fun rmsFromPcm16(buf: ByteArray, offset: Int, size: Int): Float {
        if (size < 2) return 0f
        var sumSq = 0.0
        var count = 0
        var i = offset
        val end = offset + size - 1
        while (i < end) {
            val sample = readLeShort(buf, i)
            sumSq += sample.toDouble() * sample
            count++
            i += 2
        }
        return if (count > 0) sqrt(sumSq / count).toFloat() else 0f
    }

    private fun rmsFromFloats(buf: FloatArray, offset: Int, count: Int): Float {
        if (count <= 0) return 0f
        var sumSq = 0.0
        for (i in offset until offset + count) {
            val sample = (buf[i] * 32768f).toInt()
            sumSq += sample.toDouble() * sample
        }
        return sqrt(sumSq / count).toFloat()
    }

    private fun computeDuckGain(rms: Float): Float {
        if (rms < THRESHOLD) return MAX_VIRTUAL
        val t = ((rms - THRESHOLD) / (FULL_SCALE - THRESHOLD)).coerceIn(0f, 1f)
        return MAX_VIRTUAL + (MIN_DUCK - MAX_VIRTUAL) * t
    }

    private fun updateEnvelope(rms: Float): Float {
        val target = computeDuckGain(rms)
        envelope = if (target < envelope) {
            envelope + (target - envelope) * ATTACK
        } else {
            envelope + (target - envelope) * RELEASE
        }
        return envelope
    }

    fun mixPcm16Bytes(
        real: ByteArray,
        offset: Int,
        size: Int,
        channels: Int,
        virtualMono: ByteArray,
        virtualBytes: Int
    ): Boolean {
        if (virtualBytes < 2) return false
        val ch = channels.coerceAtLeast(1)
        val frameCount = size / (2 * ch)
        if (frameCount <= 0) return false

        val duck = updateEnvelope(rmsFromPcm16(real, offset, size))
        val virtFrames = virtualBytes / 2
        for (f in 0 until frameCount) {
            val vi = mapVirtualFrame(f, virtFrames, frameCount) * 2
            if (vi + 1 >= virtualBytes) break
            val v = (readLeShort(virtualMono, vi) * duck).roundToInt()
            for (c in 0 until ch) {
                val i = offset + (f * ch + c) * 2
                if (i + 1 >= offset + size) break
                val mixed = (readLeShort(real, i) + v)
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                writeLeShort(real, i, mixed)
            }
        }
        return true
    }

    fun mixPcm16Shorts(
        real: ShortArray,
        offset: Int,
        count: Int,
        channels: Int,
        virtualMono: ByteArray,
        virtualBytes: Int
    ): Boolean {
        val byteScratch = ByteArray(count * 2)
        val bb = ByteBuffer.wrap(byteScratch).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        repeat(count) { idx -> bb.put(idx, real[offset + idx]) }
        val ok = mixPcm16Bytes(byteScratch, 0, byteScratch.size, channels, virtualMono, virtualBytes)
        if (!ok) return false
        val out = ByteBuffer.wrap(byteScratch).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        repeat(count) { idx -> real[offset + idx] = out.get(idx) }
        return true
    }

    fun mixPcmFloats(
        real: FloatArray,
        offset: Int,
        count: Int,
        channels: Int,
        virtualMono: ByteArray,
        virtualBytes: Int
    ): Boolean {
        if (virtualBytes < 2) return false
        val ch = channels.coerceAtLeast(1)
        val frameCount = count / ch
        if (frameCount <= 0) return false

        val duck = updateEnvelope(rmsFromFloats(real, offset, count))
        val virtFrames = virtualBytes / 2
        val virt = ByteBuffer.wrap(virtualMono, 0, virtualBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        for (f in 0 until frameCount) {
            val vi = mapVirtualFrame(f, virtFrames, frameCount)
            val vSample = if (vi < virt.capacity()) virt.get(vi) / 32768f * duck else 0f
            for (c in 0 until ch) {
                val idx = offset + f * ch + c
                if (idx >= offset + count) break
                real[idx] = (real[idx] + vSample).coerceIn(-1f, 1f)
            }
        }
        return true
    }

    fun mixFloatBytesWithVirtualPcm16(
        real: ByteArray,
        offset: Int,
        size: Int,
        channels: Int,
        virtualMono: ByteArray,
        virtualBytes: Int
    ): Boolean {
        val sampleCount = size / 4
        if (sampleCount <= 0) return false
        val floats = FloatArray(sampleCount)
        val fb = ByteBuffer.wrap(real, offset, size).order(ByteOrder.LITTLE_ENDIAN)
        repeat(sampleCount) { floats[it] = fb.getFloat() }
        val ok = mixPcmFloats(floats, 0, sampleCount, channels, virtualMono, virtualBytes)
        if (!ok) return false
        val out = ByteBuffer.wrap(real, offset, size).order(ByteOrder.LITTLE_ENDIAN)
        repeat(sampleCount) { out.putFloat(floats[it]) }
        return true
    }
}
