package io.mo.glassmic.xposed



import android.content.Context

import android.media.AudioFormat

import android.media.AudioRecord

import de.robv.android.xposed.XC_MethodHook

import de.robv.android.xposed.XposedBridge

import de.robv.android.xposed.XposedHelpers

import io.mo.glassmic.core.audio.ComfortNoise

import io.mo.glassmic.core.model.SourceType

import java.nio.ByteBuffer

import java.nio.ByteOrder

import java.util.Collections

import java.util.WeakHashMap



/** legacy API 82 的 AudioRecord read() 拦截实现。 */

object LegacyAudioRecordHook {



    private const val TAG = "Yuhuo-LegacyAudio"



    private val readers = WeakHashMap<AudioRecord, XposedPcmReader>()



    /** 本进程见过的 AudioRecord（含真麦透传路径），供一键解除占用。 */
    private val liveRecords = Collections.synchronizedMap(WeakHashMap<AudioRecord, Boolean>())



    @Volatile private var installed = false



    private fun replaceByteArrayFromVirtual(

        appCtx: Context,

        record: AudioRecord,

        buf: ByteArray,

        offset: Int,

        size: Int

    ): Int {

        val reader = obtainReader(appCtx, record)

        val n = if (record.audioFormat == AudioFormat.ENCODING_PCM_FLOAT) {

            reader.readFloatBytes(buf, offset, size)

        } else {

            reader.read(buf, offset, size)

        }

        if (n <= 0) releaseReader(record)

        return n

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

        hookStartRecording(appCtx, callerPkg)



        XposedBridge.log("$TAG: installed in $callerPkg")

    }



    private fun hookReadByteArray(appCtx: Context, pkg: String, withMode: Boolean) {

        hook("read", ByteArray::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, withMode = withMode) { param ->

            val record = param.thisObject as AudioRecord

            track(record)

            val src = XBridge.resolveSource(appCtx, pkg)

            if (src == SourceType.REAL_MIC) {

                releaseReader(record)

                return@hook

            }



            val buf = param.args[0] as ByteArray

            val offset = param.args[1] as Int

            val size = param.args[2] as Int

            if (!validRange(buf.size, offset, size)) return@hook



            if (src == SourceType.SILENCE) {

                releaseReader(record)


                val isFloat = record.audioFormat == AudioFormat.ENCODING_PCM_FLOAT

                if (isFloat) ComfortNoise.fillFloatBytes(buf, offset, size)

                else ComfortNoise.fillBytes(buf, offset, size)

                param.result = size

                XBridge.recordInterception(appCtx, pkg, size, record.sampleRate, record.channelCount)

                return@hook

            }



            val n = replaceByteArrayFromVirtual(appCtx, record, buf, offset, size)

            if (n <= 0) {

                releaseReader(record)

                return@hook

            }

            if (n < size) java.util.Arrays.fill(buf, offset + n, offset + size, 0.toByte())

            param.result = size

            XBridge.recordInterception(appCtx, pkg, size, record.sampleRate, record.channelCount)

        }

    }



    private fun hookReadShortArray(appCtx: Context, pkg: String, withMode: Boolean) {

        hook("read", ShortArray::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, withMode = withMode) { param ->

            val record = param.thisObject as AudioRecord

            track(record)

            val src = XBridge.resolveSource(appCtx, pkg)

            if (src == SourceType.REAL_MIC) {

                releaseReader(record)

                return@hook

            }



            val buf = param.args[0] as ShortArray

            val offset = param.args[1] as Int

            val sizeInShorts = param.args[2] as Int

            if (!validRange(buf.size, offset, sizeInShorts)) return@hook



            if (src == SourceType.SILENCE) {

                releaseReader(record)

                ComfortNoise.fillShorts(buf, offset, sizeInShorts)

                param.result = sizeInShorts

                XBridge.recordInterception(appCtx, pkg, sizeInShorts * 2, record.sampleRate, record.channelCount)

                return@hook

            }



            val byteBuf = ByteArray(sizeInShorts * 2)

            val n = obtainReader(appCtx, record).read(byteBuf, 0, byteBuf.size)

            if (n <= 0) {

                releaseReader(record)

                return@hook

            }

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

            val record = param.thisObject as AudioRecord

            track(record)

            val src = XBridge.resolveSource(appCtx, pkg)

            if (src == SourceType.REAL_MIC) {

                releaseReader(record)

                return@hook

            }



            val buf = param.args[0] as FloatArray

            val offset = param.args[1] as Int

            val sizeInFloats = param.args[2] as Int

            if (!validRange(buf.size, offset, sizeInFloats)) return@hook



            if (src == SourceType.SILENCE) {

                releaseReader(record)

                ComfortNoise.fillFloats(buf, offset, sizeInFloats)

                param.result = sizeInFloats

                XBridge.recordInterception(appCtx, pkg, sizeInFloats * 4, record.sampleRate, record.channelCount)

                return@hook

            }



            val byteBuf = ByteArray(sizeInFloats * 2)

            val n = obtainReader(appCtx, record).read(byteBuf, 0, byteBuf.size)

            if (n <= 0) {

                releaseReader(record)

                return@hook

            }

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

            val record = param.thisObject as AudioRecord

            track(record)

            val src = XBridge.resolveSource(appCtx, pkg)

            if (src == SourceType.REAL_MIC) {

                releaseReader(record)

                return@hook

            }



            val buf = param.args[0] as ByteBuffer

            val size = param.args[1] as Int

            if (size < 0 || size > buf.remaining()) return@hook



            if (src == SourceType.SILENCE) {

                releaseReader(record)

                repeat(size) { buf.put(0.toByte()) }

                param.result = size

                XBridge.recordInterception(appCtx, pkg, size, record.sampleRate, record.channelCount)

                return@hook

            }



            val pos = buf.position()

            val reader = obtainReader(appCtx, record)

            val n = if (record.audioFormat == AudioFormat.ENCODING_PCM_FLOAT) {

                reader.readFloatBuffer(buf, size)

            } else {

                reader.read(buf, size)

            }

            if (n <= 0) {

                releaseReader(record)

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

            track(record)

            releaseReader(record)

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



    private fun releaseReader(record: AudioRecord) {

        synchronized(readers) {

            readers.remove(record)?.release()

        }

    }



    private fun validRange(total: Int, offset: Int, size: Int): Boolean =

        offset >= 0 && size >= 0 && offset <= total && size <= total - offset


    private fun hookStartRecording(appCtx: Context, pkg: String) {

        hook("startRecording") { param ->

            val record = param.thisObject as? AudioRecord ?: return@hook

            track(record)

            XBridge.resolveSource(appCtx, pkg)

            if (MicForceRelease.shouldBlockRecording()) {

                param.throwable = IllegalStateException("yuhuo: recording blocked briefly after mic release")

            }

        }

    }



    private fun track(record: AudioRecord) {

        liveRecords[record] = java.lang.Boolean.TRUE

    }



    /** 打断本进程跟踪到的全部 AudioRecord，释放系统麦克风占用。 */

    fun forceReleaseAll() {

        val snapshot = synchronized(liveRecords) { liveRecords.keys.toList() }

        var n = 0

        for (record in snapshot) {

            val err = runCatching {

                if (record.state != AudioRecord.STATE_UNINITIALIZED) {

                    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {

                        record.stop()

                    }

                    record.release()

                }

            }.exceptionOrNull()

            if (err != null) {

                XposedBridge.log("$TAG: forceRelease one failed: ${err.message}")

            } else {

                n++

            }

            releaseReader(record)

            liveRecords.remove(record)

        }

        XposedBridge.log("$TAG: forceReleaseAll ok=$n / ${snapshot.size}")

    }

}


