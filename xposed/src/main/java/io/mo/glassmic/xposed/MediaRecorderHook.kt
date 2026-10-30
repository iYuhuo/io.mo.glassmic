package io.mo.glassmic.xposed

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.libxposed.api.XposedInterface
import java.io.FileDescriptor
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * MediaRecorder 整合 Hook，承载两套能力：
 *
 * 1. 纯音频接管（上游能力，仅 libxposed 路径）：当音源决策不是 REAL_MIC、且是
 *    **纯音频 + AAC 编码 + MPEG_4/3GP 容器**时，不让系统媒体服务采集真麦，改为用应用内
 *    AudioRecord（已被 AudioRecordHook 覆盖 → 自动填虚拟 PCM）+ MediaCodec(AAC) + MediaMuxer
 *    写到原输出。其余情况一律放行真实录音（安全默认）。
 * 2. yuhuo 本地能力：跟踪 MediaRecorder 会话供一键解除占用（[forceReleaseAll]），
 *    封锁窗内拒绝 start（MicForceRelease）。
 *
 * 不处理：视频 / Camcorder 分支、AMR / 裸 AAC-ADTS 容器、legacy API 82 的接管接线。
 */
object MediaRecorderHook {

    private const val TAG = "Yuhuo-MediaRec"

    // MediaRecorder.AudioEncoder
    private const val ENC_AAC = 3
    private const val ENC_HE_AAC = 4
    private const val ENC_AAC_ELD = 5
    // MediaRecorder.OutputFormat
    private const val OUT_THREE_GPP = 1
    private const val OUT_MPEG_4 = 2

    private const val DEFAULT_SAMPLE_RATE = 44100
    private const val DEFAULT_CHANNELS = 1
    private const val DEFAULT_BITRATE = 128_000
    private const val CODEC_TIMEOUT_US = 10_000L

    // yuhuo：活跃会话跟踪，供 forceReleaseAll
    private val live = Collections.synchronizedMap(WeakHashMap<MediaRecorder, Boolean>())
    private val states = WeakHashMap<MediaRecorder, RecorderState>()

    @Volatile private var installed = false
    @Volatile private var callerPkg: String = ""

    private class RecorderState {
        var audioSource: Int = -1
        var hasVideoSource: Boolean = false
        var outputFormat: Int = -1
        var audioEncoder: Int = -1
        var sampleRate: Int = DEFAULT_SAMPLE_RATE
        var channels: Int = DEFAULT_CHANNELS
        var bitRate: Int = DEFAULT_BITRATE
        var outputPath: String? = null
        var outputFd: FileDescriptor? = null
        var maxDurationMs: Int = 0

        @Volatile var pipeline: CapturePipeline? = null
        val takeover: Boolean get() = pipeline != null
    }

    private sealed interface HookDecision {
        data object Proceed : HookDecision
        data class Replace(val value: Any?) : HookDecision
    }

    fun install(api: XposedInterface?, appCtx: Context?, callerPkg: String) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            installed = true
            this.callerPkg = callerPkg
        }
        if (api != null && appCtx != null) {
            installLibxposed(api, appCtx, callerPkg)
        } else {
            installLegacy(callerPkg)
        }
        Log.i(TAG, "installed in $callerPkg")
    }

    // ============ libxposed：跟踪 + 封锁 + 纯音频接管 ============
    private fun installLibxposed(api: XposedInterface, appCtx: Context, callerPkg: String) {
        val cls = MediaRecorder::class.java
        val intp = Int::class.javaPrimitiveType!!

        fun hookMethod(method: Method?, handler: (Context, String, XposedInterface.Chain) -> HookDecision) {
            method ?: return
            api.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    (chain.getThisObject() as? MediaRecorder)?.let { live[it] = true }
                    val decision = try {
                        handler(appCtx, callerPkg, chain)
                    } catch (t: Throwable) {
                        api.log(Log.WARN, TAG, "hook error: ${t.message}")
                        HookDecision.Proceed
                    }
                    when (decision) {
                        HookDecision.Proceed -> chain.proceed()
                        is HookDecision.Replace -> decision.value
                    }
                }
        }

        // 仅记录状态的 setter
        hookMethod(find(cls, "setAudioSource", intp)) { _, _, chain ->
            stateOf(chain).audioSource = chain.getArg(0) as Int
            HookDecision.Proceed
        }
        hookMethod(find(cls, "setVideoSource", intp)) { _, _, chain ->
            stateOf(chain).hasVideoSource = true
            HookDecision.Proceed
        }
        hookMethod(find(cls, "setOutputFormat", intp)) { _, _, chain ->
            stateOf(chain).outputFormat = chain.getArg(0) as Int
            HookDecision.Proceed
        }
        hookMethod(find(cls, "setAudioEncoder", intp)) { _, _, chain ->
            stateOf(chain).audioEncoder = chain.getArg(0) as Int
            HookDecision.Proceed
        }
        hookMethod(find(cls, "setAudioSamplingRate", intp)) { _, _, chain ->
            (chain.getArg(0) as Int).let { if (it > 0) stateOf(chain).sampleRate = it }
            HookDecision.Proceed
        }
        hookMethod(find(cls, "setAudioChannels", intp)) { _, _, chain ->
            (chain.getArg(0) as Int).let { if (it > 0) stateOf(chain).channels = it }
            HookDecision.Proceed
        }
        hookMethod(find(cls, "setAudioEncodingBitRate", intp)) { _, _, chain ->
            (chain.getArg(0) as Int).let { if (it > 0) stateOf(chain).bitRate = it }
            HookDecision.Proceed
        }
        hookMethod(find(cls, "setMaxDuration", intp)) { _, _, chain ->
            stateOf(chain).maxDurationMs = chain.getArg(0) as Int
            HookDecision.Proceed
        }
        hookMethod(find(cls, "setOutputFile", String::class.java)) { _, _, chain ->
            stateOf(chain).apply { outputPath = chain.getArg(0) as? String; outputFd = null }
            HookDecision.Proceed
        }
        hookMethod(find(cls, "setOutputFile", FileDescriptor::class.java)) { _, _, chain ->
            stateOf(chain).apply { outputFd = chain.getArg(0) as? FileDescriptor; outputPath = null }
            HookDecision.Proceed
        }
        find(cls, "setOutputFile", java.io.File::class.java)?.let { m ->
            hookMethod(m) { _, _, chain ->
                stateOf(chain).apply {
                    outputPath = (chain.getArg(0) as? java.io.File)?.absolutePath; outputFd = null
                }
                HookDecision.Proceed
            }
        }

        hookMethod(find(cls, "prepare"), ::onPrepare)
        hookMethod(find(cls, "start"), ::onStart)
        hookMethod(find(cls, "stop"), ::onStop)
        hookMethod(find(cls, "reset"), ::onResetOrRelease)
        hookMethod(find(cls, "release"), ::onResetOrRelease)
        hookMethod(find(cls, "getMaxAmplitude"), ::onGetMaxAmplitude)
        api.log(Log.INFO, TAG, "libxposed MediaRecorder hooks in $callerPkg")
    }

    // ============ 生命周期（接管决策） ============
    private fun onPrepare(appCtx: Context, pkg: String, chain: XposedInterface.Chain): HookDecision {
        val st = stateOf(chain)
        if (XBridge.resolveSource(appCtx, pkg) == io.mo.glassmic.core.model.SourceType.REAL_MIC)
            return HookDecision.Proceed
        if (!canTakeOver(st)) return HookDecision.Proceed

        val pipeline = runCatching { CapturePipeline(st) }
            .onFailure { Log.w(TAG, "takeover init failed, fall back to real recorder: ${it.message}") }
            .getOrNull() ?: return HookDecision.Proceed

        st.pipeline = pipeline
        XBridge.reportDiagnosticEvent(appCtx, pkg, "mediarecorder_takeover", android.os.Bundle().apply {
            putInt("sample_rate", st.sampleRate)
            putInt("channels", st.channels)
            putInt("encoder", st.audioEncoder)
            putInt("output_format", st.outputFormat)
        })
        Log.i(TAG, "takeover prepared pkg=$pkg sr=${st.sampleRate} ch=${st.channels}")
        return HookDecision.Replace(null)
    }

    private fun onStart(appCtx: Context, pkg: String, chain: XposedInterface.Chain): HookDecision {
        // yuhuo 封锁窗：解除占用后短暂拒绝录音
        if (MicForceRelease.shouldBlockRecording()) {
            throw IllegalStateException("yuhuo: recording blocked briefly after mic release")
        }
        val st = stateOf(chain)
        val pipeline = st.pipeline ?: return HookDecision.Proceed
        return if (runCatching { pipeline.start() }.isSuccess) {
            HookDecision.Replace(null)
        } else {
            runCatching { pipeline.release() }
            st.pipeline = null
            HookDecision.Proceed
        }
    }

    private fun onStop(appCtx: Context, pkg: String, chain: XposedInterface.Chain): HookDecision {
        val st = stateOf(chain)
        // 未接管（真实录音）时必须放行系统 stop，否则录音无法正常停止/封装。
        val pipeline = st.pipeline ?: return HookDecision.Proceed
        runCatching { pipeline.stop() }
        st.pipeline = null
        return HookDecision.Replace(null)
    }

    private fun onResetOrRelease(appCtx: Context, pkg: String, chain: XposedInterface.Chain): HookDecision {
        val st = stateOf(chain)
        st.pipeline?.let { runCatching { it.release() } }
        st.pipeline = null
        return HookDecision.Proceed
    }

    private fun onGetMaxAmplitude(appCtx: Context, pkg: String, chain: XposedInterface.Chain): HookDecision {
        val pipeline = stateOf(chain).pipeline ?: return HookDecision.Proceed
        return HookDecision.Replace(pipeline.drainMaxAmplitude())
    }

    private fun canTakeOver(st: RecorderState): Boolean {
        if (st.hasVideoSource) return false
        if (st.audioEncoder !in intArrayOf(ENC_AAC, ENC_HE_AAC, ENC_AAC_ELD)) return false
        if (st.outputFormat !in intArrayOf(OUT_MPEG_4, OUT_THREE_GPP)) return false
        return st.outputPath != null || st.outputFd != null
    }

    private fun stateOf(chain: XposedInterface.Chain): RecorderState {
        val recorder = chain.getThisObject() as MediaRecorder
        synchronized(states) {
            return states.getOrPut(recorder) { RecorderState() }
        }
    }

    private fun find(cls: Class<*>, name: String, vararg paramTypes: Class<*>?): Method? =
        runCatching { cls.getMethod(name, *paramTypes) }
            .recoverCatching { cls.getDeclaredMethod(name, *paramTypes) }
            .getOrNull()

    // ============ legacy API 82：仅跟踪 + 封锁（不接接管，与上游一致） ============
    private fun installLegacy(callerPkg: String) {
        val trackCb = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                (param.thisObject as? MediaRecorder)?.let { live[it] = true }
            }
        }
        val startCb = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                (param.thisObject as? MediaRecorder)?.let { live[it] = true }
                if (MicForceRelease.shouldBlockRecording()) {
                    param.throwable =
                        IllegalStateException("yuhuo: recording blocked briefly after mic release")
                }
            }
        }
        runCatching { XposedBridge.hookAllConstructors(MediaRecorder::class.java, trackCb) }
        runCatching {
            XposedHelpers.findAndHookMethod(MediaRecorder::class.java, "start", startCb)
        }.onFailure { XposedBridge.log("$TAG: hook start failed: ${it.message}") }
        for (name in listOf("prepare", "release")) {
            runCatching {
                XposedHelpers.findAndHookMethod(MediaRecorder::class.java, name, trackCb)
            }.onFailure { XposedBridge.log("$TAG: hook $name failed: ${it.message}") }
        }
        XposedBridge.log("$TAG: legacy MediaRecorder hooks in $callerPkg")
    }

    // ============ yuhuo：一键解除占用 ============
    fun forceReleaseAll() {
        val snapshot = synchronized(live) { live.keys.toList() }
        var n = 0
        for (rec in snapshot) {
            runCatching {
                runCatching { rec.stop() }
                runCatching { rec.reset() }
                runCatching { rec.release() }
                n++
            }.onFailure { Log.w(TAG, "release one MediaRecorder failed: ${it.message}") }
            live.remove(rec)
        }
        Log.i(TAG, "forceReleaseAll released=$n / ${snapshot.size}")
    }

    /**
     * AudioRecord(已被 Hook → 虚拟 PCM) → MediaCodec(AAC) → MediaMuxer，写到应用指定输出。
     * 实时节奏由 AudioRecord.read 的 Hook 继承，这里不额外节流。
     */
    private class CapturePipeline(private val st: RecorderState) {
        private val sampleRate = st.sampleRate
        private val channels = st.channels.coerceIn(1, 2)

        private val record: AudioRecord
        private val codec: MediaCodec
        private val muxer: MediaMuxer

        @Volatile private var running = false
        @Volatile private var finished = false
        private var thread: Thread? = null
        private var trackIndex = -1
        private var muxerStarted = false
        private var framesWritten = 0L
        private val peak = AtomicInteger(0)

        init {
            val channelMask =
                if (channels >= 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { record.release() }
                error("AudioRecord not initialized")
            }

            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels
            ).apply {
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC
                )
                setInteger(MediaFormat.KEY_BIT_RATE, st.bitRate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuf * 2)
            }
            codec = runCatching {
                MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                    configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                }
            }.getOrElse {
                runCatching { record.release() }
                throw it
            }

            val muxerFormat =
                if (st.outputFormat == OUT_THREE_GPP) MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP
                else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            muxer = runCatching {
                val path = st.outputPath
                if (path != null) MediaMuxer(path, muxerFormat)
                else MediaMuxer(st.outputFd!!, muxerFormat)
            }.getOrElse {
                runCatching { codec.release() }
                runCatching { record.release() }
                throw it
            }
        }

        fun start() {
            codec.start()
            record.startRecording()
            running = true
            thread = Thread({ loop() }, "GlassMic-MediaRecCap").apply { start() }
        }

        private fun loop() {
            val info = MediaCodec.BufferInfo()
            val pcm = ByteArray(2048 * channels)
            val maxFrames =
                if (st.maxDurationMs > 0) st.maxDurationMs.toLong() * sampleRate / 1000
                else Long.MAX_VALUE
            var inputDone = false
            var outputDone = false
            try {
                while (!outputDone) {
                    if (!inputDone) {
                        val n = record.read(pcm, 0, pcm.size)
                        val eos = !running || n < 0 || framesWritten >= maxFrames
                        if (n > 0) trackPeak(pcm, n)
                        feedInput(pcm, if (n > 0) n else 0, eos)
                        if (eos) inputDone = true
                    }
                    outputDone = drainOutput(info)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "capture loop error: ${t.message}")
            }
        }

        private fun feedInput(pcm: ByteArray, len: Int, eos: Boolean) {
            val index = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
            if (index < 0) return
            val input = codec.getInputBuffer(index) ?: return
            input.clear()
            if (len > 0) input.put(pcm, 0, len)
            val ptsUs = framesWritten * 1_000_000L / sampleRate
            framesWritten += len / (2L * channels)
            codec.queueInputBuffer(
                index, 0, len, ptsUs,
                if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
            )
        }

        /** @return true 表示收到 END_OF_STREAM，应结束。 */
        private fun drainOutput(info: MediaCodec.BufferInfo): Boolean {
            while (true) {
                val index = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    index >= 0 -> {
                        val out = codec.getOutputBuffer(index)
                        if (out != null && info.size > 0 &&
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && muxerStarted
                        ) {
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            muxer.writeSampleData(trackIndex, out, info)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return true
                    }
                }
            }
        }

        private fun trackPeak(pcm: ByteArray, len: Int) {
            val bb = ByteBuffer.wrap(pcm, 0, len).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            var p = 0
            while (bb.hasRemaining()) {
                val a = kotlin.math.abs(bb.get().toInt())
                if (a > p) p = a
            }
            var cur = peak.get()
            while (p > cur && !peak.compareAndSet(cur, p)) cur = peak.get()
        }

        fun drainMaxAmplitude(): Int = peak.getAndSet(0)

        fun stop() {
            running = false
            thread?.join(3000)
            finalizeOutput()
        }

        fun release() {
            running = false
            thread?.join(1500)
            finalizeOutput()
        }

        private fun finalizeOutput() {
            if (finished) return
            finished = true
            runCatching { record.stop() }
            runCatching { record.release() }
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }
}
