package io.mo.glassmic.xposed

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.mo.glassmic.core.model.SourceType
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.WeakHashMap

/**
 * 拦截 AudioRecord 的全部 read() 重载，把读到的数据替换成虚拟音源。
 *
 * 注：NDK AAudio 路径（AAudioStream_read）需要 native hook，留给后续版本。
 */
object AudioRecordHook {

    private const val TAG = "Yuhuo-AudioRec"

    private val readers = WeakHashMap<AudioRecord, XposedPcmReader>()
    /** 混音闪避专用：始终 mono 虚拟轨，与 native 侧 fd ch=1 一致。 */
    private val mixReaders = WeakHashMap<AudioRecord, XposedPcmReader>()

    @Volatile private var installed = false
    @Volatile private var lastMixEnabled = false

    private fun trackMixState(appCtx: Context, pkg: String) {
        val mix = XBridge.isRealMicMixEnabled(appCtx, pkg)
        if (lastMixEnabled && !mix) {
            RealMicMixer.resetEnvelope()
        }
        lastMixEnabled = mix
    }

    private fun accumulate(ctx: Context, pkg: String, bytes: Int, sampleRate: Int, channels: Int) {
        XBridge.recordInterception(ctx, pkg, bytes, sampleRate, channels)
    }

    private sealed interface HookDecision {
        data object Proceed : HookDecision
        data class Replace(val value: Any?) : HookDecision
    }

    fun install(api: XposedInterface, appCtx: Context, callerPkg: String) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            if (!XposedHookGate.tryMarkAudioHookInstalled()) {
                installed = true
                api.log(Log.INFO, TAG, "skip duplicate API 101 hook in $callerPkg")
                return
            }
            installed = true
        }

        hookMethod(
            api, appCtx, callerPkg,
            findMethod(AudioRecord::class.java, "read", ByteArray::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
            ::handleByteArray
        )
        hookMethod(
            api, appCtx, callerPkg,
            findMethod(AudioRecord::class.java, "read", ByteArray::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
            ::handleByteArray
        )

        hookMethod(
            api, appCtx, callerPkg,
            findMethod(AudioRecord::class.java, "read", ShortArray::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
            ::handleShortArray
        )
        hookMethod(
            api, appCtx, callerPkg,
            findMethod(AudioRecord::class.java, "read", ShortArray::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
            ::handleShortArray
        )

        hookMethod(
            api, appCtx, callerPkg,
            findMethod(AudioRecord::class.java, "read", FloatArray::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
            ::handleFloatArray
        )

        hookMethod(
            api, appCtx, callerPkg,
            findMethod(AudioRecord::class.java, "read", ByteBuffer::class.java, Int::class.javaPrimitiveType!!),
            ::handleByteBuffer
        )
        hookMethod(
            api, appCtx, callerPkg,
            findMethod(AudioRecord::class.java, "read", ByteBuffer::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
            ::handleByteBuffer
        )

        hookMethod(
            api, appCtx, callerPkg,
            findMethod(AudioRecord::class.java, "release"),
            ::handleRelease
        )

        api.log(Log.INFO, TAG, "installed in $callerPkg")
    }

    private fun findMethod(cls: Class<*>, name: String, vararg paramTypes: Class<*>?): Method? =
        runCatching { cls.getMethod(name, *paramTypes) }
            .recoverCatching { cls.getDeclaredMethod(name, *paramTypes) }
            .getOrNull()

    private fun hookMethod(
        api: XposedInterface,
        appCtx: Context,
        callerPkg: String,
        method: Method?,
        handler: (Context, String, XposedInterface.Chain) -> HookDecision
    ) {
        method ?: return
        api.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val decision = try {
                    handler(appCtx, callerPkg, chain)
                } catch (t: Throwable) {
                    api.log(Log.WARN, TAG, "hook error: ${t.message}", t)
                    HookDecision.Proceed
                }
                when (decision) {
                    HookDecision.Proceed -> chain.proceed()
                    is HookDecision.Replace -> decision.value
                }
            }
    }

    private fun handleByteArray(appCtx: Context, pkg: String, chain: XposedInterface.Chain): HookDecision {
        val src = XBridge.resolveSource(appCtx, pkg)
        if (src == SourceType.REAL_MIC) return HookDecision.Proceed
        trackMixState(appCtx, pkg)

        val buf = chain.getArg(0) as ByteArray
        val offset = chain.getArg(1) as Int
        val size = chain.getArg(2) as Int
        if (!validRange(buf.size, offset, size)) return HookDecision.Proceed

        val record = chain.getThisObject() as AudioRecord
        if (src == SourceType.SILENCE) {
            java.util.Arrays.fill(buf, offset, offset + size, 0.toByte())
            accumulate(appCtx, pkg, size, record.sampleRate, record.channelCount)
            return HookDecision.Replace(size)
        }

        val reader = obtainReader(appCtx, record)
        if (XBridge.isRealMicMixEnabled(appCtx, pkg)) {
            val realRead = chain.proceed() as? Int ?: return HookDecision.Proceed
            if (realRead <= 0) return HookDecision.Replace(realRead)
            val ch = record.channelCount.coerceAtLeast(1)
            val frameCount = if (record.audioFormat == AudioFormat.ENCODING_PCM_FLOAT) {
                realRead / 4 / ch
            } else {
                realRead / (2 * ch)
            }
            val virtual = ByteArray((frameCount * 2).coerceAtLeast(2))
            val vn = obtainMixReader(appCtx, record).read(virtual, 0, virtual.size)
            val mixed = if (record.audioFormat == AudioFormat.ENCODING_PCM_FLOAT) {
                RealMicMixer.mixFloatBytesWithVirtualPcm16(buf, offset, realRead, ch, virtual, vn)
            } else {
                RealMicMixer.mixPcm16Bytes(buf, offset, realRead, ch, virtual, vn)
            }
            if (!mixed) {
                val n = if (record.audioFormat == AudioFormat.ENCODING_PCM_FLOAT) {
                    reader.readFloatBytes(buf, offset, size)
                } else {
                    reader.read(buf, offset, size)
                }
                if (n < 0) {
                    java.util.Arrays.fill(buf, offset, offset + size, 0.toByte())
                    accumulate(appCtx, pkg, size, record.sampleRate, record.channelCount)
                    return HookDecision.Replace(size)
                }
                if (n < size) java.util.Arrays.fill(buf, offset + n, offset + size, 0.toByte())
                accumulate(appCtx, pkg, size, record.sampleRate, record.channelCount)
                return HookDecision.Replace(size)
            }
            if (realRead < size) {
                java.util.Arrays.fill(buf, offset + realRead, offset + size, 0.toByte())
            }
            accumulate(appCtx, pkg, size, record.sampleRate, record.channelCount)
            return HookDecision.Replace(size)
        }

        val n = if (record.audioFormat == AudioFormat.ENCODING_PCM_FLOAT) {
            reader.readFloatBytes(buf, offset, size)
        } else {
            reader.read(buf, offset, size)
        }
        if (n < 0) {
            java.util.Arrays.fill(buf, offset, offset + size, 0.toByte())
            accumulate(appCtx, pkg, size, record.sampleRate, record.channelCount)
            return HookDecision.Replace(size)
        }
        if (n < size) java.util.Arrays.fill(buf, offset + n, offset + size, 0.toByte())
        accumulate(appCtx, pkg, size, record.sampleRate, record.channelCount)
        return HookDecision.Replace(size)
    }

    private fun handleShortArray(appCtx: Context, pkg: String, chain: XposedInterface.Chain): HookDecision {
        val src = XBridge.resolveSource(appCtx, pkg)
        if (src == SourceType.REAL_MIC) return HookDecision.Proceed
        trackMixState(appCtx, pkg)

        val buf = chain.getArg(0) as ShortArray
        val offset = chain.getArg(1) as Int
        val sizeInShorts = chain.getArg(2) as Int
        if (!validRange(buf.size, offset, sizeInShorts)) return HookDecision.Proceed

        val record = chain.getThisObject() as AudioRecord
        if (src == SourceType.SILENCE) {
            java.util.Arrays.fill(buf, offset, offset + sizeInShorts, 0.toShort())
            accumulate(appCtx, pkg, sizeInShorts * 2, record.sampleRate, record.channelCount)
            return HookDecision.Replace(sizeInShorts)
        }

        val reader = obtainReader(appCtx, record)
        if (XBridge.isRealMicMixEnabled(appCtx, pkg)) {
            val realRead = chain.proceed() as? Int ?: return HookDecision.Proceed
            if (realRead <= 0) return HookDecision.Replace(realRead)
            val ch = record.channelCount.coerceAtLeast(1)
            val virtual = ByteArray((realRead / ch * 2).coerceAtLeast(2))
            val vn = obtainMixReader(appCtx, record).read(virtual, 0, virtual.size)
            if (!RealMicMixer.mixPcm16Shorts(buf, offset, realRead, ch, virtual, vn)) {
                val byteBuf = ByteArray(sizeInShorts * 2)
                val n = reader.read(byteBuf, 0, byteBuf.size)
                if (n < 0) {
                    java.util.Arrays.fill(buf, offset, offset + sizeInShorts, 0.toShort())
                    accumulate(appCtx, pkg, sizeInShorts * 2, record.sampleRate, record.channelCount)
                    return HookDecision.Replace(sizeInShorts)
                }
                val bb = ByteBuffer.wrap(byteBuf).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val shortsRead = n / 2
                repeat(shortsRead) { i -> buf[offset + i] = bb.get(i) }
                if (shortsRead < sizeInShorts) {
                    java.util.Arrays.fill(buf, offset + shortsRead, offset + sizeInShorts, 0.toShort())
                }
                accumulate(appCtx, pkg, sizeInShorts * 2, record.sampleRate, record.channelCount)
                return HookDecision.Replace(sizeInShorts)
            }
            if (realRead < sizeInShorts) {
                java.util.Arrays.fill(buf, offset + realRead, offset + sizeInShorts, 0.toShort())
            }
            accumulate(appCtx, pkg, sizeInShorts * 2, record.sampleRate, record.channelCount)
            return HookDecision.Replace(sizeInShorts)
        }

        val byteBuf = ByteArray(sizeInShorts * 2)
        val n = reader.read(byteBuf, 0, byteBuf.size)
        if (n < 0) {
            java.util.Arrays.fill(buf, offset, offset + sizeInShorts, 0.toShort())
            accumulate(appCtx, pkg, sizeInShorts * 2, record.sampleRate, record.channelCount)
            return HookDecision.Replace(sizeInShorts)
        }
        val bb = ByteBuffer.wrap(byteBuf).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val shortsRead = n / 2
        repeat(shortsRead) { i -> buf[offset + i] = bb.get(i) }
        if (shortsRead < sizeInShorts) {
            java.util.Arrays.fill(buf, offset + shortsRead, offset + sizeInShorts, 0.toShort())
        }
        accumulate(appCtx, pkg, sizeInShorts * 2, record.sampleRate, record.channelCount)
        return HookDecision.Replace(sizeInShorts)
    }

    private fun handleFloatArray(appCtx: Context, pkg: String, chain: XposedInterface.Chain): HookDecision {
        val src = XBridge.resolveSource(appCtx, pkg)
        if (src == SourceType.REAL_MIC) return HookDecision.Proceed
        trackMixState(appCtx, pkg)

        val buf = chain.getArg(0) as FloatArray
        val offset = chain.getArg(1) as Int
        val sizeInFloats = chain.getArg(2) as Int
        if (!validRange(buf.size, offset, sizeInFloats)) return HookDecision.Proceed

        val record = chain.getThisObject() as AudioRecord
        if (src == SourceType.SILENCE) {
            java.util.Arrays.fill(buf, offset, offset + sizeInFloats, 0f)
            accumulate(appCtx, pkg, sizeInFloats * 4, record.sampleRate, record.channelCount)
            return HookDecision.Replace(sizeInFloats)
        }

        val reader = obtainReader(appCtx, record)
        if (XBridge.isRealMicMixEnabled(appCtx, pkg)) {
            val realRead = chain.proceed() as? Int ?: return HookDecision.Proceed
            if (realRead <= 0) return HookDecision.Replace(realRead)
            val ch = record.channelCount.coerceAtLeast(1)
            val virtual = ByteArray((realRead / ch * 2).coerceAtLeast(2))
            val vn = obtainMixReader(appCtx, record).read(virtual, 0, virtual.size)
            if (!RealMicMixer.mixPcmFloats(buf, offset, realRead, ch, virtual, vn)) {
                val byteBuf = ByteArray(sizeInFloats * 2)
                val n = reader.read(byteBuf, 0, byteBuf.size)
                if (n < 0) {
                    java.util.Arrays.fill(buf, offset, offset + sizeInFloats, 0f)
                    accumulate(appCtx, pkg, sizeInFloats * 4, record.sampleRate, record.channelCount)
                    return HookDecision.Replace(sizeInFloats)
                }
                val bb = ByteBuffer.wrap(byteBuf).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val floatsRead = n / 2
                repeat(floatsRead) { i -> buf[offset + i] = bb.get(i) / 32768f }
                if (floatsRead < sizeInFloats) {
                    java.util.Arrays.fill(buf, offset + floatsRead, offset + sizeInFloats, 0f)
                }
                accumulate(appCtx, pkg, sizeInFloats * 4, record.sampleRate, record.channelCount)
                return HookDecision.Replace(sizeInFloats)
            }
            if (realRead < sizeInFloats) {
                java.util.Arrays.fill(buf, offset + realRead, offset + sizeInFloats, 0f)
            }
            accumulate(appCtx, pkg, sizeInFloats * 4, record.sampleRate, record.channelCount)
            return HookDecision.Replace(sizeInFloats)
        }

        val byteBuf = ByteArray(sizeInFloats * 2)
        val n = reader.read(byteBuf, 0, byteBuf.size)
        if (n < 0) {
            java.util.Arrays.fill(buf, offset, offset + sizeInFloats, 0f)
            accumulate(appCtx, pkg, sizeInFloats * 4, record.sampleRate, record.channelCount)
            return HookDecision.Replace(sizeInFloats)
        }
        val bb = ByteBuffer.wrap(byteBuf).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val floatsRead = n / 2
        repeat(floatsRead) { i -> buf[offset + i] = bb.get(i) / 32768f }
        if (floatsRead < sizeInFloats) {
            java.util.Arrays.fill(buf, offset + floatsRead, offset + sizeInFloats, 0f)
        }
        accumulate(appCtx, pkg, sizeInFloats * 4, record.sampleRate, record.channelCount)
        return HookDecision.Replace(sizeInFloats)
    }

    private fun handleByteBuffer(appCtx: Context, pkg: String, chain: XposedInterface.Chain): HookDecision {
        val src = XBridge.resolveSource(appCtx, pkg)
        if (src == SourceType.REAL_MIC) return HookDecision.Proceed
        trackMixState(appCtx, pkg)

        val buf = chain.getArg(0) as ByteBuffer
        val size = chain.getArg(1) as Int
        if (size < 0 || size > buf.remaining()) return HookDecision.Proceed

        val record = chain.getThisObject() as AudioRecord
        if (src == SourceType.SILENCE) {
            repeat(size) { buf.put(0.toByte()) }
            accumulate(appCtx, pkg, size, record.sampleRate, record.channelCount)
            return HookDecision.Replace(size)
        }

        val reader = obtainReader(appCtx, record)
        if (XBridge.isRealMicMixEnabled(appCtx, pkg)) {
            val pos = buf.position()
            val realRead = chain.proceed() as? Int ?: return HookDecision.Proceed
            if (realRead <= 0) return HookDecision.Replace(realRead)
            val ch = record.channelCount.coerceAtLeast(1)
            val temp = ByteArray(realRead)
            buf.position(pos)
            buf.get(temp, 0, realRead)
            val frameCount = if (record.audioFormat == AudioFormat.ENCODING_PCM_FLOAT) {
                realRead / 4 / ch
            } else {
                realRead / (2 * ch)
            }
            val virtual = ByteArray((frameCount * 2).coerceAtLeast(2))
            val vn = obtainMixReader(appCtx, record).read(virtual, 0, virtual.size)
            val mixed = if (record.audioFormat == AudioFormat.ENCODING_PCM_FLOAT) {
                RealMicMixer.mixFloatBytesWithVirtualPcm16(temp, 0, realRead, ch, virtual, vn)
            } else {
                RealMicMixer.mixPcm16Bytes(temp, 0, realRead, ch, virtual, vn)
            }
            if (!mixed) {
                buf.position(pos)
                val n = if (record.audioFormat == AudioFormat.ENCODING_PCM_FLOAT) {
                    reader.readFloatBuffer(buf, size)
                } else {
                    reader.read(buf, size)
                }
                if (n < 0) {
                    buf.position(pos)
                    repeat(size) { buf.put(0.toByte()) }
                    accumulate(appCtx, pkg, size, record.sampleRate, record.channelCount)
                    return HookDecision.Replace(size)
                }
                if (n < size) repeat(size - n) { buf.put(0.toByte()) }
                accumulate(appCtx, pkg, size, record.sampleRate, record.channelCount)
                return HookDecision.Replace(size)
            }
            buf.position(pos)
            buf.put(temp, 0, realRead)
            if (realRead < size) {
                repeat(size - realRead) { buf.put(0.toByte()) }
            }
            accumulate(appCtx, pkg, size, record.sampleRate, record.channelCount)
            return HookDecision.Replace(size)
        }

        val pos = buf.position()
        val n = if (record.audioFormat == AudioFormat.ENCODING_PCM_FLOAT) {
            reader.readFloatBuffer(buf, size)
        } else {
            reader.read(buf, size)
        }
        if (n < 0) {
            buf.position(pos)
            repeat(size) { buf.put(0.toByte()) }
            accumulate(appCtx, pkg, size, record.sampleRate, record.channelCount)
            return HookDecision.Replace(size)
        }
        if (n < size) {
            repeat(size - n) { buf.put(0.toByte()) }
        }
        accumulate(appCtx, pkg, size, record.sampleRate, record.channelCount)
        return HookDecision.Replace(size)
    }

    private fun handleRelease(appCtx: Context, pkg: String, chain: XposedInterface.Chain): HookDecision {
        val record = chain.getThisObject() as AudioRecord
        synchronized(readers) {
            readers.remove(record)?.release()
            mixReaders.remove(record)?.release()
        }
        return HookDecision.Proceed
    }

    private fun obtainMixReader(ctx: Context, record: AudioRecord): XposedPcmReader {
        synchronized(mixReaders) {
            mixReaders[record]?.let { return it }
            val r = XposedPcmReader(ctx, sampleRate = record.sampleRate, channels = 1)
            mixReaders[record] = r
            return r
        }
    }

    private fun obtainReader(ctx: Context, record: AudioRecord): XposedPcmReader {
        synchronized(readers) {
            readers[record]?.let { return it }
            val r = XposedPcmReader(
                ctx,
                sampleRate = record.sampleRate,
                channels = record.channelCount
            )
            readers[record] = r
            return r
        }
    }

    private fun validRange(total: Int, offset: Int, size: Int): Boolean =
        offset >= 0 && size >= 0 && offset <= total && size <= total - offset
}
