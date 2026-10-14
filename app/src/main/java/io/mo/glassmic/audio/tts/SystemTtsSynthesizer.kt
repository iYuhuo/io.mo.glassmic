package io.mo.glassmic.audio.tts

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.log.GlassLog
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 系统 TextToSpeech 合成器（含 MultiTTS 等第三方默认引擎）。
 *
 * 优先走 [UtteranceProgressListener.onAudioAvailable] 流式 PCM（Google TTS 等支持）；
 * 若引擎只写文件、不回调 PCM（MultiTTS 等常见），则在 [onDone] 时读取合成文件解码为 PCM16。
 *
 * 使用 [TextToSpeech.synthesizeToFile] 而非 speak()，避免扬声器出声。
 */
@Singleton
class SystemTtsSynthesizer @Inject constructor(
    @ApplicationContext private val context: Context
) : SpeechSynthesizer {

    private val seq = AtomicLong(0L)
    private val sinks = ConcurrentHashMap<String, PcmSink>()
    private val files = ConcurrentHashMap<String, File>()
    /** 该 utterance 是否已通过 onAudioAvailable 收到过 PCM。 */
    private val streamedPcm = ConcurrentHashMap<String, AtomicBoolean>()

    @Volatile private var ready = false
    @Volatile private var initFailed = false
    // 引擎初始化前进来的请求，就绪后补发（只保留最后一个）。
    @Volatile private var pending: Pair<TtsRequest, PcmSink>? = null

    private val tts: TextToSpeech = TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            val engine = runCatching { tts.defaultEngine }.getOrNull().orEmpty()
            GlassLog.b("Tts") { "系统 TTS 就绪 engine=${engine.ifBlank { "?" }}" }
            pending?.let { (req, sink) ->
                pending = null
                synthesize(req, sink)
            }
        } else {
            initFailed = true
            GlassLog.b("Tts") { "系统 TTS 初始化失败: status=$status" }
            pending?.let { (_, sink) -> sink.onError("系统 TTS 初始化失败") }
            pending = null
        }
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onBeginSynthesis(
                utteranceId: String?,
                sampleRateInHz: Int,
                audioFormat: Int,
                channelCount: Int
            ) {
                val id = utteranceId ?: return
                sinks[id]?.onFormat(sampleRateInHz, channelCount.coerceAtLeast(1))
            }

            override fun onAudioAvailable(utteranceId: String?, audio: ByteArray?) {
                val id = utteranceId ?: return
                if (audio != null && audio.isNotEmpty()) {
                    streamedPcm[id]?.set(true)
                    sinks[id]?.onPcm(audio)
                }
            }

            override fun onDone(utteranceId: String?) {
                val id = utteranceId ?: return
                val sink = sinks.remove(id) ?: return
                val file = files[id]
                val gotStream = streamedPcm.remove(id)?.get() == true
                if (!gotStream) {
                    // MultiTTS 等引擎常不实现 onAudioAvailable，只写 synthesizeToFile 的目标文件。
                    val ok = file != null && emitFromFile(file, sink)
                    cleanupFile(id)
                    if (!ok) sink.onError("系统 TTS 未产出音频（引擎可能不支持文件合成）")
                    return
                }
                cleanupFile(id)
                sink.onDone()
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String?) = failUtterance(utteranceId, "合成错误")

            override fun onError(utteranceId: String?, errorCode: Int) =
                failUtterance(utteranceId, "合成错误 code=$errorCode")
        })
    }

    override fun synthesize(request: TtsRequest, sink: PcmSink) {
        if (initFailed) {
            sink.onError("系统 TTS 不可用")
            return
        }
        if (!ready) {
            pending = request to sink
            return
        }
        if (request.text.isBlank()) {
            sink.onError("文本为空")
            return
        }

        runCatching {
            if (request.rate > 0f) tts.setSpeechRate(request.rate)
            if (request.pitch > 0f) tts.setPitch(request.pitch)
            if (request.voice.isNotBlank()) {
                val locale = java.util.Locale.forLanguageTag(request.voice)
                if (locale.language.isNotBlank()) tts.language = locale
            }
        }.onFailure { GlassLog.b("Tts") { "应用 TTS 参数失败: ${it.message}" } }

        val id = "glass-tts-${seq.incrementAndGet()}"
        val file = File(context.cacheDir, "$id.wav")
        sinks[id] = sink
        files[id] = file
        streamedPcm[id] = AtomicBoolean(false)

        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id)
        }
        val result = runCatching { tts.synthesizeToFile(request.text, params, file, id) }
            .getOrDefault(TextToSpeech.ERROR)
        if (result != TextToSpeech.SUCCESS) {
            sinks.remove(id)
            streamedPcm.remove(id)
            cleanupFile(id)
            sink.onError("合成启动失败")
        }
    }

    override fun cancel() {
        runCatching { tts.stop() }
        sinks.keys.toList().forEach { cleanupFile(it) }
        sinks.clear()
        streamedPcm.clear()
    }

    override fun release() {
        cancel()
        runCatching { tts.shutdown() }
    }

    private fun failUtterance(utteranceId: String?, message: String) {
        val id = utteranceId ?: return
        sinks.remove(id)?.onError(message)
        streamedPcm.remove(id)
        cleanupFile(id)
    }

    private fun cleanupFile(id: String) {
        files.remove(id)?.let { runCatching { it.delete() } }
    }

    /**
     * 从合成文件解码并回调 PCM。成功返回 true（已调用 onDone / onError）。
     */
    private fun emitFromFile(file: File, sink: PcmSink): Boolean {
        if (!file.exists() || file.length() < 12) {
            GlassLog.b("Tts") { "合成文件缺失或过短: ${file.length()} B" }
            return false
        }
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return false
        GlassLog.b("Tts") { "引擎未流式回调，改读文件: ${bytes.size} B" }
        if (emitWav(bytes, sink)) return true
        return emitViaMediaCodec(file, sink)
    }

    /** 解析最简 WAV；成功则已 onDone，返回 true。 */
    private fun emitWav(bytes: ByteArray, sink: PcmSink): Boolean {
        if (bytes.size < 44 || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") {
            return false
        }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var sampleRate = 24_000
        var channels = 1
        while (pos + 8 <= bytes.size) {
            val chunkId = String(bytes, pos, 4)
            val chunkSize = bb.getInt(pos + 4)
            val dataStart = pos + 8
            when (chunkId) {
                "fmt " -> {
                    channels = bb.getShort(dataStart + 2).toInt().coerceAtLeast(1)
                    sampleRate = bb.getInt(dataStart + 4)
                }
                "data" -> {
                    val end = (dataStart + chunkSize).coerceAtMost(bytes.size)
                    sink.onFormat(sampleRate, channels)
                    sink.onPcm(bytes.copyOfRange(dataStart, end))
                    sink.onDone()
                    return true
                }
            }
            pos = dataStart + chunkSize + (chunkSize and 1)
        }
        return false
    }

    /** 非 WAV 或异常 WAV：用 MediaExtractor 解码为 PCM16。 */
    private fun emitViaMediaCodec(file: File, sink: PcmSink): Boolean {
        val extractor = MediaExtractor()
        return runCatching {
            extractor.setDataSource(file.absolutePath)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    track = i
                    format = f
                    break
                }
            }
            if (track < 0 || format == null) {
                GlassLog.b("Tts") { "合成文件无音轨" }
                return@runCatching false
            }
            extractor.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return@runCatching false
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8_000)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            sink.onFormat(sampleRate, channels)

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(buf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex >= 0 -> {
                        if (info.size > 0) {
                            val out = codec.getOutputBuffer(outIndex)!!
                            val chunk = ByteArray(info.size)
                            out.position(info.offset)
                            out.get(chunk)
                            sink.onPcm(chunk)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* spin */ }
                }
            }
            codec.stop()
            codec.release()
            sink.onDone()
            true
        }.onFailure {
            GlassLog.b("Tts") { "解码合成文件失败: ${it.message}" }
        }.getOrDefault(false).also {
            runCatching { extractor.release() }
        }
    }
}
