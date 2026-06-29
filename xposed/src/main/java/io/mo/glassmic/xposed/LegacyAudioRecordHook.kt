package io.mo.glassmic.xposed

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.mo.glassmic.core.model.SourceType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.WeakHashMap

/** legacy API 82 的 AudioRecord read() 拦截实现。 */
object LegacyAudioRecordHook {

    private const val TAG = "Yuhuo-LegacyAudio"

    private val readers = WeakHashMap<AudioRecord, XposedPcmReader>()
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

    private fun replaceByteArrayFromVirtual(
        appCtx: Context,
        record: AudioRecord,
        buf: ByteArray,
        offset: Int,
        size: Int
    ): Int {
        val reader = obtainReader(appCtx, record)
        return if (record.audioFormat == AudioFormat.ENCODING_PCM_FLOAT) {
            reader.readFloatBytes(buf, offset, size)
        } else {
            reader.read(buf, offset, size)
        }
    }

    fun install(appCtx: Context, callerPkg: String) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            if (!XposedHookGate.tryMarkAudioHookInstalled()) {
                installed = true
                XposedBridge.log("$TAG: skip duplicate legacy hook in $callerPkg")
                return
            }
            installed = true
        }

        hookReadByteArray(appCtx, callerPkg, false)
        hookReadByteArray(appCtx, callerPkg, true)
        hookReadShortArray(appCtx, callerPkg, false)
        hookReadShortArray(appCtx, callerPkg, true)
        hookReadFloatArray(appCtx, callerPkg)
        hookReadByteBuffer(appCtx, callerPkg, false)
        hookReadByteBuffer(appCtx, callerPkg, true)
        hookRelease()

        XposedBridge.log("$TAG: installed in $callerPkg")
    }

    private fun hookReadByteArray(appCtx: Context, pkg: String, withMode: Boolean) {
        hook("read", ByteArray::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, withMode = withMode) { param ->
            val src = XBridge.resolveSource(appCtx, pkg)
            if (src == SourceType.REAL_MIC) return@hook
            trackMixState(appCtx, pkg)

            val buf = param.args[0] as ByteArray
            val offset = param.args[1] as Int
            val size = param.args[2] as Int
            if (!validRange(buf.size, offset, size)) return@hook

            val record = param.thisObject as AudioRecord
            if (src == SourceType.SILENCE) {
                java.util.Arrays.fill(buf, offset, offset + size, 0.toByte())
                param.result = size
                XBridge.recordInterception(appCtx, pkg, size, record.sampleRate, record.channelCount)
                return@hook
            }

            if (mixByteArray(appCtx, pkg, param, record, offset, size)) return@hook

            val n = replaceByteArrayFromVirtual(appCtx, record, buf, offset, size)
            if (n < 0) return@hook
            if (n < size) java.util.Arrays.fill(buf, offset + n, offset + size, 0.toByte())
            param.result = size
            XBridge.recordInterception(appCtx, pkg, size, record.sampleRate, record.channelCount)
        }
    }

    private fun hookReadShortArray(appCtx: Context, pkg: String, withMode: Boolean) {
        hook("read", ShortArray::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, withMode = withMode) { param ->
            val src = XBridge.resolveSource(appCtx, pkg)
            if (src == SourceType.REAL_MIC) return@hook
            trackMixState(appCtx, pkg)

            val buf = param.args[0] as ShortArray
            val offset = param.args[1] as Int
            val sizeInShorts = param.args[2] as Int
            if (!validRange(buf.size, offset, sizeInShorts)) return@hook

            val record = param.thisObject as AudioRecord
            if (src == SourceType.SILENCE) {
                java.util.Arrays.fill(buf, offset, offset + sizeInShorts, 0.toShort())
                param.result = sizeInShorts
                XBridge.recordInterception(appCtx, pkg, sizeInShorts * 2, record.sampleRate, record.channelCount)
                return@hook
            }

            if (mixShortArray(appCtx, pkg, param, record, buf, offset, sizeInShorts)) return@hook

            val byteBuf = ByteArray(sizeInShorts * 2)
            val n = obtainReader(appCtx, record).read(byteBuf, 0, byteBuf.size)
            if (n < 0) return@hook
            val bb = ByteBuffer.wrap(byteBuf).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val shortsRead = n / 2
            repeat(shortsRead) { i -> buf[offset + i] = bb.get(i) }
            if (shortsRead < sizeInShorts) {
                java.util.Arrays.fill(buf, offset + shortsRead, offset + sizeInShorts, 0.toShort())
            }
            param.result = sizeInShorts
            XBridge.recordInterception(appCtx, pkg, sizeInShorts * 2, record.sampleRate, record.channelCount)
        }
    }

    private fun hookReadFloatArray(appCtx: Context, pkg: String) {
        hook("read", FloatArray::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!) { param ->
            val src = XBridge.resolveSource(appCtx, pkg)
            if (src == SourceType.REAL_MIC) return@hook
            trackMixState(appCtx, pkg)

            val buf = param.args[0] as FloatArray
            val offset = param.args[1] as Int
            val sizeInFloats = param.args[2] as Int
            if (!validRange(buf.size, offset, sizeInFloats)) return@hook

            val record = param.thisObject as AudioRecord
            if (src == SourceType.SILENCE) {
                java.util.Arrays.fill(buf, offset, offset + sizeInFloats, 0f)
                param.result = sizeInFloats
                XBridge.recordInterception(appCtx, pkg, sizeInFloats * 4, record.sampleRate, record.channelCount)
                return@hook
            }

            if (mixFloatArray(appCtx, pkg, param, record, buf, offset, sizeInFloats)) return@hook

            val byteBuf = ByteArray(sizeInFloats * 2)
            val n = obtainReader(appCtx, record).read(byteBuf, 0, byteBuf.size)
            if (n < 0) return@hook
            val bb = ByteBuffer.wrap(byteBuf).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val floatsRead = n / 2
            repeat(floatsRead) { i -> buf[offset + i] = bb.get(i) / 32768f }
            if (floatsRead < sizeInFloats) {
                java.util.Arrays.fill(buf, offset + floatsRead, offset + sizeInFloats, 0f)
            }
            param.result = sizeInFloats
            XBridge.recordInterception(appCtx, pkg, sizeInFloats * 4, record.sampleRate, record.channelCount)
        }
    }

    private fun hookReadByteBuffer(appCtx: Context, pkg: String, withMode: Boolean) {
        hook("read", ByteBuffer::class.java, Int::class.javaPrimitiveType!!, withMode = withMode) { param ->
            val src = XBridge.resolveSource(appCtx, pkg)
            if (src == SourceType.REAL_MIC) return@hook
            trackMixState(appCtx, pkg)

            val buf = param.args[0] as ByteBuffer
            val size = param.args[1] as Int
            if (size < 0 || size > buf.remaining()) return@hook

            val record = param.thisObject as AudioRecord
            if (src == SourceType.SILENCE) {
                repeat(size) { buf.put(0.toByte()) }
                param.result = size
                XBridge.recordInterception(appCtx, pkg, size, record.sampleRate, record.channelCount)
                return@hook
            }

            if (mixByteBuffer(appCtx, pkg, param, record, buf, size)) return@hook

            val pos = buf.position()
            val reader = obtainReader(appCtx, record)
            val n = if (record.audioFormat == AudioFormat.ENCODING_PCM_FLOAT) {
                reader.readFloatBuffer(buf, size)
            } else {
                reader.read(buf, size)
            }
            if (n < 0) {
                buf.position(pos)
                return@hook
            }
            if (n < size) repeat(size - n) { buf.put(0.toByte()) }
            param.result = size
            XBridge.recordInterception(appCtx, pkg, size, record.sampleRate, record.channelCount)
        }
    }

    private fun hookRelease() {
        hook("release") { param ->
            val record = param.thisObject as? AudioRecord ?: return@hook
            synchronized(readers) {
                readers.remove(record)?.release()
                mixReaders.remove(record)?.release()
            }
        }
    }

    private fun hook(
        name: String,
        vararg paramTypes: Class<*>,
        withMode: Boolean = false,
        before: (XC_MethodHook.MethodHookParam) -> Unit
    ) {
        val args = if (withMode) {
            arrayOf(*paramTypes, Int::class.javaPrimitiveType!!, hookCallback(before))
        } else {
            arrayOf(*paramTypes, hookCallback(before))
        }
        runCatching {
            XposedHelpers.findAndHookMethod(AudioRecord::class.java, name, *args)
        }.onFailure {
            XposedBridge.log("$TAG: hook $name failed: ${it.message}")
        }
    }

    private fun hookCallback(before: (XC_MethodHook.MethodHookParam) -> Unit): XC_MethodHook =
        object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                runCatching { before(param) }
                    .onFailure {
                        XposedBridge.log("$TAG: hook error: ${it.message}")
                        XposedBridge.log(it)
                    }
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

    private fun obtainMixReader(ctx: Context, record: AudioRecord): XposedPcmReader {
        synchronized(mixReaders) {
            mixReaders[record]?.let { return it }
            val r = XposedPcmReader(ctx, sampleRate = record.sampleRate, channels = 1)
            mixReaders[record] = r
            return r
        }
    }

    private fun mixByteArray(
        appCtx: Context,
        pkg: String,
        param: XC_MethodHook.MethodHookParam,
        record: AudioRecord,
        offset: Int,
        size: Int
    ): Boolean {
        if (!XBridge.isRealMicMixEnabled(appCtx, pkg)) return false
        val buf = param.args[0] as ByteArray
        @Suppress("UNCHECKED_CAST")
        val realRead = XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args) as Int
        if (realRead <= 0) {
            param.result = realRead
            return true
        }
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
            val n = replaceByteArrayFromVirtual(appCtx, record, buf, offset, size)
            if (n < 0) return false
            if (n < size) java.util.Arrays.fill(buf, offset + n, offset + size, 0.toByte())
            param.result = size
            XBridge.recordInterception(appCtx, pkg, size, record.sampleRate, record.channelCount)
            return true
        }
        if (realRead < size) {
            java.util.Arrays.fill(buf, offset + realRead, offset + size, 0.toByte())
        }
        param.result = size
        XBridge.recordInterception(appCtx, pkg, size, record.sampleRate, record.channelCount)
        return true
    }

    private fun mixShortArray(
        appCtx: Context,
        pkg: String,
        param: XC_MethodHook.MethodHookParam,
        record: AudioRecord,
        buf: ShortArray,
        offset: Int,
        sizeInShorts: Int
    ): Boolean {
        if (!XBridge.isRealMicMixEnabled(appCtx, pkg)) return false
        @Suppress("UNCHECKED_CAST")
        val realRead = XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args) as Int
        if (realRead <= 0) {
            param.result = realRead
            return true
        }
        val ch = record.channelCount.coerceAtLeast(1)
        val virtual = ByteArray((realRead / ch * 2).coerceAtLeast(2))
        val vn = obtainMixReader(appCtx, record).read(virtual, 0, virtual.size)
        if (!RealMicMixer.mixPcm16Shorts(buf, offset, realRead, ch, virtual, vn)) {
            val byteBuf = ByteArray(sizeInShorts * 2)
            val n = obtainReader(appCtx, record).read(byteBuf, 0, byteBuf.size)
            if (n < 0) return false
            val bb = ByteBuffer.wrap(byteBuf).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val shortsRead = n / 2
            repeat(shortsRead) { i -> buf[offset + i] = bb.get(i) }
            if (shortsRead < sizeInShorts) {
                java.util.Arrays.fill(buf, offset + shortsRead, offset + sizeInShorts, 0.toShort())
            }
            param.result = sizeInShorts
            XBridge.recordInterception(appCtx, pkg, sizeInShorts * 2, record.sampleRate, record.channelCount)
            return true
        }
        if (realRead < sizeInShorts) {
            java.util.Arrays.fill(buf, offset + realRead, offset + sizeInShorts, 0.toShort())
        }
        param.result = sizeInShorts
        XBridge.recordInterception(appCtx, pkg, sizeInShorts * 2, record.sampleRate, record.channelCount)
        return true
    }

    private fun mixFloatArray(
        appCtx: Context,
        pkg: String,
        param: XC_MethodHook.MethodHookParam,
        record: AudioRecord,
        buf: FloatArray,
        offset: Int,
        sizeInFloats: Int
    ): Boolean {
        if (!XBridge.isRealMicMixEnabled(appCtx, pkg)) return false
        @Suppress("UNCHECKED_CAST")
        val realRead = XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args) as Int
        if (realRead <= 0) {
            param.result = realRead
            return true
        }
        val ch = record.channelCount.coerceAtLeast(1)
        val virtual = ByteArray((realRead / ch * 2).coerceAtLeast(2))
        val vn = obtainMixReader(appCtx, record).read(virtual, 0, virtual.size)
        if (!RealMicMixer.mixPcmFloats(buf, offset, realRead, ch, virtual, vn)) {
            val byteBuf = ByteArray(sizeInFloats * 2)
            val n = obtainReader(appCtx, record).read(byteBuf, 0, byteBuf.size)
            if (n < 0) return false
            val bb = ByteBuffer.wrap(byteBuf).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val floatsRead = n / 2
            repeat(floatsRead) { i -> buf[offset + i] = bb.get(i) / 32768f }
            if (floatsRead < sizeInFloats) {
                java.util.Arrays.fill(buf, offset + floatsRead, offset + sizeInFloats, 0f)
            }
            param.result = sizeInFloats
            XBridge.recordInterception(appCtx, pkg, sizeInFloats * 4, record.sampleRate, record.channelCount)
            return true
        }
        if (realRead < sizeInFloats) {
            java.util.Arrays.fill(buf, offset + realRead, offset + sizeInFloats, 0f)
        }
        param.result = sizeInFloats
        XBridge.recordInterception(appCtx, pkg, sizeInFloats * 4, record.sampleRate, record.channelCount)
        return true
    }

    private fun mixByteBuffer(
        appCtx: Context,
        pkg: String,
        param: XC_MethodHook.MethodHookParam,
        record: AudioRecord,
        buf: ByteBuffer,
        size: Int
    ): Boolean {
        if (!XBridge.isRealMicMixEnabled(appCtx, pkg)) return false
        val pos = buf.position()
        @Suppress("UNCHECKED_CAST")
        val realRead = XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args) as Int
        if (realRead <= 0) {
            param.result = realRead
            return true
        }
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
            val reader = obtainReader(appCtx, record)
            val n = if (record.audioFormat == AudioFormat.ENCODING_PCM_FLOAT) {
                reader.readFloatBuffer(buf, size)
            } else {
                reader.read(buf, size)
            }
            if (n < 0) {
                buf.position(pos)
                return false
            }
            if (n < size) repeat(size - n) { buf.put(0.toByte()) }
            param.result = size
            XBridge.recordInterception(appCtx, pkg, size, record.sampleRate, record.channelCount)
            return true
        }
        buf.position(pos)
        buf.put(temp, 0, realRead)
        if (realRead < size) repeat(size - realRead) { buf.put(0.toByte()) }
        param.result = size
        XBridge.recordInterception(appCtx, pkg, size, record.sampleRate, record.channelCount)
        return true
    }

    private fun validRange(total: Int, offset: Int, size: Int): Boolean =
        offset >= 0 && size >= 0 && offset <= total && size <= total - offset
}
