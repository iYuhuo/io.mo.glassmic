package io.mo.glassmic.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.ParcelFileDescriptor
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.core.model.PlaybackPolicy
import io.mo.glassmic.core.model.SourceType
import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.data.runtime.RuntimeStateHolder
import io.mo.glassmic.log.GlassLog
import io.mo.glassmic.service.SafeModeWatchdog
import io.mo.glassmic.proto.PlaybackPolicy as ProtoPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * PCM 数据广播器。
 *
 * 维护一个"当前音源"——可能是 FileAudioSource / SilenceSource。
 * 多个目标 App 进程通过 PcmStreamProvider 拿到不同的 pipe，
 * Publisher 把同一份 PCM 流广播给所有 consumer，共享播放进度（需求 §10.6）。
 *
 * 注入路径：实时主时钟广播；慢 consumer 队列满则丢帧，不拖全局。
 * 本地监听：独立 fork + 独立泵。
 * 无 consumer 时注入不空转（防「先播完再打开 App」丢开头）；预览靠本地监听。
 */
@Singleton
class SharedPcmPublisher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val runtime: RuntimeStateHolder,
    private val configStore: ConfigStore,
    private val watchdog: SafeModeWatchdog
) {

    private data class Consumer(
        val id: String,
        val pkg: String,
        val sampleRate: Int,
        val channels: Int,
        val fd: ParcelFileDescriptor,
        val out: FileOutputStream,
        val queue: Channel<ByteArray>,
        val converter: Pcm16Converter,
        /** 已入队尚未写出的帧数（满队列丢帧时不增，EOF 可准确等待） */
        val pending: AtomicInteger = AtomicInteger(0)
    )

    private data class AudioEffects(
        val noiseSim: Boolean = false,
        val highGain: Boolean = false,
        val limiterEnabled: Boolean = true,
        val reverb: Boolean = false,
        val reverbAmount: Float = 0f,
        val speed: Boolean = false,
        val speedFactor: Float = 1f
    )

    private val consumers = ConcurrentHashMap<String, Consumer>()
    private val consumerSeq = AtomicLong(0L)
    private val mutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private companion object {
        // 当前所有路径都按 PCM_16 处理；后续如果接入 PCM_FLOAT / PCM_8 需要参数化
        const val BYTES_PER_SAMPLE = 2
        const val MASTER_SAMPLE_RATE = 48_000
        const val MASTER_CHANNELS = 1
        const val NOISE_SIM_AMPLITUDE = 6_000
        const val HIGH_GAIN_MULTIPLIER = 1.8f
        const val REVERB_DELAY_SAMPLES = 2_880   // 60ms @48k 单声道
        const val WAVEFORM_POINTS_PER_FRAME = 24 // 每帧下采样出的波形振幅点数
        /** 播完切真麦前：最多等队列排空这么久，避免掐掉结尾 */
        const val EOF_DRAIN_TIMEOUT_MS = 1_500L
    }

    // 实时波形振幅点（0..1）。仅在有订阅者（波形悬浮窗打开）时计算并发送。
    private val _waveform = MutableSharedFlow<FloatArray>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val waveform: SharedFlow<FloatArray> = _waveform.asSharedFlow()

    @Volatile private var currentSource: AudioSourceProvider = ComfortNoiseSource
    @Volatile private var writerStarted = false
    @Volatile private var paused: Boolean = false
    @Volatile private var monitorEnabled = false
    @Volatile private var outputVolumeRuntime = Float.NaN
    @Volatile private var effects = AudioEffects()
    // 仅在广播协程单线程访问，无需同步
    private val reverbLine = ReverbLine(REVERB_DELAY_SAMPLES)
    private val monitorLock = Any()
    private var monitorTrack: AudioTrack? = null
    @Volatile private var monitorSource: AudioSourceProvider? = null
    private var monitorPumpJob: Job? = null
    private val _localMonitorEnabled = MutableStateFlow(false)
    /** 运行时本地监听开关（与 DataStore 偏好可能不同，例如真麦态强制关）。 */
    val localMonitorEnabled: StateFlow<Boolean> = _localMonitorEnabled.asStateFlow()

    val isLocalMonitorEnabled: Boolean get() = monitorEnabled

    init {
        scope.launch {
            configStore.flow.collect { cfg ->
                val exp = cfg.experimental
                val speedRaw = if (exp.speedFactor <= 0f) 1f else exp.speedFactor.coerceIn(0.5f, 2f)
                effects = AudioEffects(
                    noiseSim = exp.unlocked && exp.noiseSim,
                    highGain = exp.unlocked && exp.highGain,
                    limiterEnabled = exp.limiterEnabled,
                    reverb = exp.unlocked && exp.reverbEnabled,
                    reverbAmount = exp.reverbAmount.coerceIn(0f, 1f),
                    speed = exp.unlocked && exp.speedEnabled && speedRaw != 1f,
                    speedFactor = speedRaw
                )
            }
        }
    }

    val isPaused: Boolean get() = paused

    /**
     * 本地监听：独立时钟播放当前虚拟源的 fork，不跟注入背压。
     * 无录音 consumer 时注入不会空转；仅预览听感。
     */
    fun setLocalMonitor(enabled: Boolean) {
        if (monitorEnabled == enabled) return
        monitorEnabled = enabled
        _localMonitorEnabled.value = enabled
        if (enabled) {
            rebuildMonitorSource()
            startMonitorTrack()
            ensureIndependentMonitorPump()
        } else {
            stopIndependentMonitorPump()
            releaseMonitorSource()
            scope.launch(Dispatchers.IO) {
                stopMonitorTrack()
            }
        }
        GlassLog.b("Publisher") { "localMonitor=$enabled" }
    }

    /** 暂停只影响"是否从 source 读取数据"，下游仍然收到等量静音，避免 pipe 阻塞/EOF。 */
    fun setPaused(value: Boolean) {
        if (paused == value) return
        paused = value
        runtime.setPaused(value)
        GlassLog.b("Publisher") { "paused=$value" }
    }

    /** 把当前源跳转到指定毫秒。仅 FileAudioSource / BufferedPcmSource 支持，其他类型忽略。 */
    suspend fun seekCurrent(positionMs: Long) {
        when (val src = currentSource) {
            is FileAudioSource -> {
                // 先写 UI 位置，避免拖动松手后拇指先弹回旧进度再跳过去
                runtime.setPosition(positionMs)
                src.seekTo(positionMs)
            }
            is BufferedPcmSource -> {
                runtime.setPosition(positionMs)
                src.seekTo(positionMs)
            }
            else -> return
        }
        when (val mon = monitorSource) {
            is FileAudioSource -> mon.seekTo(positionMs)
            is BufferedPcmSource -> mon.seekTo(positionMs)
            else -> Unit
        }
    }

    /** Xposed 进程通过 ContentProvider 调到这里 */
    fun attachConsumer(
        consumerPackage: String,
        sampleRate: Int,
        channels: Int,
        writeFd: ParcelFileDescriptor
    ) {
        val id = buildConsumerId(consumerPackage)
        val fos = FileOutputStream(writeFd.fileDescriptor)
        // 实时主时钟 + 有界队列：满则丢新帧，绝不 await 目标 App
        // 「播完又放出开头」靠 EOF/换源 detachAll，不靠背压死等
        val queue = Channel<ByteArray>(capacity = 16)
        val safeSampleRate = sampleRate.coerceAtLeast(8_000)
        val safeChannels = channels.coerceAtLeast(1)
        val consumer = Consumer(
            id = id,
            pkg = consumerPackage,
            sampleRate = safeSampleRate,
            channels = safeChannels,
            fd = writeFd,
            out = fos,
            queue = queue,
            converter = Pcm16Converter(
                sourceSampleRate = MASTER_SAMPLE_RATE,
                sourceChannels = MASTER_CHANNELS,
                targetSampleRate = safeSampleRate,
                targetChannels = safeChannels
            )
        )
        consumers[id] = consumer
        startConsumerWriter(consumer)
        GlassLog.b("Publisher") { "新 consumer: $id pkg=$consumerPackage sr=$sampleRate ch=$channels" }
        ensureWriterRunning()
    }

    fun setSource(
        src: AudioSourceProvider,
        groupId: String? = null,
        audioId: String? = null
    ) {
        replaceSource(src, updateRuntime = true, groupId = groupId, audioId = audioId)
    }

    /**
     * 播完 / 手动切回真实麦克风：hook 透传真麦。
     *
     * 立刻标 REAL_MIC：hook 不再读 pipe。
     * 并 detachAll 关掉写端——下次虚拟播放必须 open 新 pipe，避免第二次起把上一段残留开头又读出来。
     * （真麦态 hook 已 Proceed，关 pipe 不会再灌虚拟帧；这与「录音中途仍标 FILE 时掐 pipe」不同。）
     */
    fun switchToRealMic() {
        setLocalMonitor(false)
        // paused=true → 迷你条显示 ▶；resolver 对 REAL_MIC / paused 均透传真麦
        setPaused(true)
        // 立刻标成真麦，hook 放弃 pipe（残留帧不再被读出）
        runtime.setSource(SourceType.REAL_MIC, groupId = null, audioId = null, durationMs = 0L)
        replaceSource(ComfortNoiseSource, updateRuntime = false)
        drainAllConsumerQueues()
        resetAllConverters()
        reverbLine.reset()
        detachAll()
        GlassLog.b("Publisher") { "切换 → REAL_MIC（透传真麦，已拆 pipe）" }
    }

    /**
     * 新一轮虚拟播放前：拆掉所有旧 consumer。
     * 首次冷启动无 consumer；第二次若不拆，hook/native 会继续读旧 pipe 里堵着的开头。
     */
    fun resetConsumersForNewSession() {
        drainAllConsumerQueues()
        resetAllConverters()
        reverbLine.reset()
        detachAll()
    }

    /** 换源前排空 consumer 队列，供切歌 / TTS 重播等路径复用。 */
    fun drainForSourceSwitch() {
        drainAllConsumerQueues()
        resetAllConverters()
        reverbLine.reset()
    }

    private fun resetAllConverters() {
        consumers.values.forEach { it.converter.reset() }
    }

    private fun replaceSource(
        src: AudioSourceProvider,
        updateRuntime: Boolean,
        groupId: String? = null,
        audioId: String? = null
    ) {
        currentSource.release()
        currentSource = src
        if (updateRuntime) {
            runtime.setSource(
                type = src.type,
                groupId = groupId,
                audioId = audioId,
                durationMs = src.durationMs()
            )
        }
        if (monitorEnabled) rebuildMonitorSource()
        GlassLog.b("Publisher") {
            "切换音源 → ${src.type}, group=$groupId audio=$audioId, updateRuntime=$updateRuntime"
        }
    }

    private fun ensureWriterRunning() {
        if (writerStarted) return
        synchronized(this) {
            if (writerStarted) return
            writerStarted = true
        }
        scope.launch {
            val frame = ByteBuffer.allocate(4096)
            // 用时间戳维持实时节奏，避免每帧 delay 累计误差
            var nextSendAt = System.currentTimeMillis()
            while (isActive) {
                // 本地监听已独立泵：无 consumer 时绝不可空转推进注入源
                if (consumers.isEmpty()) {
                    delay(50)
                    nextSendAt = System.currentTimeMillis()
                    continue
                }
                frame.clear()
                val sr = MASTER_SAMPLE_RATE
                val ch = MASTER_CHANNELS
                val bytesPerSec = sr.toLong() * ch * BYTES_PER_SAMPLE

                // 暂停 → 读舒适噪声源（不动真实源的位置，但保持下游有本底信号，避免录音中断）；
                // 其它情况读当前源
                val readSource = if (paused) ComfortNoiseSource else currentSource

                val n = runCatching { readSource.read(frame, sr, ch) }.getOrElse {
                    watchdog.onAudioEngineFailure()
                    GlassLog.b("Publisher") { "read 失败: ${it.message}，降级舒适噪声" }
                    setSource(ComfortNoiseSource)
                    0
                }
                when {
                    n > 0 -> {
                        frame.flip()
                        if (!paused) runtime.setPosition(readSource.positionMs())
                        // 变速会改变实际广播的字节数——按广播出去的量节流，
                        // 才能让消费端以正常采样率播放时得到正确的变速节奏
                        val outBytes = broadcast(frame)

                        val frameMs = (outBytes.toLong() * 1000L + bytesPerSec - 1) / bytesPerSec
                        nextSendAt += frameMs
                        val now = System.currentTimeMillis()
                        val sleep = nextSendAt - now
                        if (sleep > 0) {
                            delay(sleep)
                        } else if (sleep < -200) {
                            // 落后超过 200ms（被 GC / 系统抢占），追平基准，避免突然爆发
                            nextSendAt = now
                        }
                    }
                    n == -1 -> {
                        // 快照：配置读取/换源竞态下不能对「已经不是这份」的源再 reset
                        // 先让队列尽量排空，再切真麦——主时钟已不等读端，结尾靠这里补
                        awaitConsumerQueuesDrained()
                        handleEof(eofSource = readSource)
                        nextSendAt = System.currentTimeMillis()
                    }
                    else -> delay(5)
                }
            }
        }
    }

    private suspend fun handleEof(eofSource: AudioSourceProvider) = mutex.withLock {
        val src = currentSource
        // 换源竞态：这份 EOF 已经过期，绝不能再对新政源 reset/replay
        if (eofSource !== src) {
            GlassLog.b("Publisher") {
                "忽略过期 EOF：eof=${eofSource.type} current=${src.type}"
            }
            return@withLock
        }
        // TTS 必须以 BufferedPcmSource 判定（勿跟全局「循环」）；blocking 避免 withLock 内挂起
        val isTts = src is BufferedPcmSource || src.type == SourceType.TTS
        val cfg = configStore.currentBlocking()
        val policy = if (isTts) {
            when (cfg.tts.playbackPolicy) {
                ProtoPolicy.LOOP -> PlaybackPolicy.LOOP
                else -> PlaybackPolicy.REAL_MIC
            }
        } else {
            cfg.playbackPolicy.toCore()
        }
        GlassLog.b("Publisher") {
            "EOF type=${src.type} isTts=$isTts policy=$policy ttsCfg=${cfg.tts.playbackPolicy}"
        }
        when (policy) {
            // 仅显式 LOOP：TTS 走 replay()（reset 已空操作，防误触灌出开头）
            PlaybackPolicy.LOOP -> {
                when (src) {
                    is BufferedPcmSource -> {
                        src.replay()
                        (monitorSource as? BufferedPcmSource)?.replay()
                    }
                    else -> {
                        src.reset()
                        (monitorSource as? FileAudioSource)?.reset()
                    }
                }
            }
            // 立刻切真麦：hook 放弃 pipe，残留开头不会被读出
            PlaybackPolicy.SILENCE, PlaybackPolicy.REAL_MIC -> switchToRealMic()
        }
    }

    /** 返回实际广播出去的 master 字节数（变速后可能与输入不同）。 */
    private fun broadcast(buf: ByteBuffer): Int {
        val data = ByteArray(buf.remaining())
        buf.get(data)
        // 仅在「未暂停 + 非真麦 + 虚拟源」时做音量/特效；真麦态只允许保活噪声进 pipe
        val virtualInject = !paused &&
            runtime.value.currentSourceType != SourceType.REAL_MIC &&
            (currentSource.type == SourceType.FILE || currentSource.type == SourceType.TTS)
        val sourceData = if (virtualInject) {
            processOutput(data)
        } else {
            data
        }
        // 真麦且未暂停时若仍挂着 TTS/文件源，丢弃，绝不写出
        if (!paused &&
            runtime.value.currentSourceType == SourceType.REAL_MIC &&
            (currentSource.type == SourceType.FILE || currentSource.type == SourceType.TTS)
        ) {
            return data.size
        }
        consumers.values.toList().forEach { c ->
            val payload = c.converter.convert(sourceData)
            if (payload.isEmpty()) return@forEach
            // 主时钟实时推进：队列满丢本帧，绝不 await 目标 App
            val result = c.queue.trySend(payload)
            when {
                result.isSuccess -> c.pending.incrementAndGet()
                result.isClosed -> {
                    GlassLog.b("Publisher") { "consumer ${c.id} 队列已关闭，断开" }
                    detach(c.id)
                }
                // 满：丢这一帧，注入继续按墙钟走
            }
        }
        // 波形窗打开时才计算振幅，避免无谓开销
        if (_waveform.subscriptionCount.value > 0) {
            _waveform.tryEmit(downsample(sourceData, WAVEFORM_POINTS_PER_FRAME))
        }
        return sourceData.size
    }

    /** EOF 后短暂等 consumer 队列排空，减少「主时钟不等读端」时掐掉最后几百毫秒。 */
    private suspend fun awaitConsumerQueuesDrained() {
        if (consumers.isEmpty()) return
        val deadline = System.currentTimeMillis() + EOF_DRAIN_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (consumers.values.all { it.pending.get() <= 0 }) return
            delay(10)
        }
    }

    private fun rebuildMonitorSource() {
        val old = monitorSource
        monitorSource = when (val src = currentSource) {
            is BufferedPcmSource -> src.fork()
            is FileAudioSource -> {
                val forked = src.fork()
                val pos = src.positionMs()
                if (pos > 0L) {
                    scope.launch {
                        runCatching { forked.seekTo(pos) }
                    }
                }
                forked
            }
            else -> null
        }
        old?.release()
    }

    private fun releaseMonitorSource() {
        val old = monitorSource
        monitorSource = null
        old?.release()
    }

    private fun ensureIndependentMonitorPump() {
        if (monitorPumpJob?.isActive == true) return
        monitorPumpJob = scope.launch {
            val frame = ByteBuffer.allocate(4096)
            var nextSendAt = System.currentTimeMillis()
            val bytesPerSec = MASTER_SAMPLE_RATE.toLong() * MASTER_CHANNELS * BYTES_PER_SAMPLE
            while (isActive && monitorEnabled) {
                if (paused) {
                    delay(20)
                    nextSendAt = System.currentTimeMillis()
                    continue
                }
                val src = monitorSource
                if (src == null ||
                    (src.type != SourceType.FILE && src.type != SourceType.TTS)
                ) {
                    delay(50)
                    nextSendAt = System.currentTimeMillis()
                    continue
                }
                frame.clear()
                val n = runCatching {
                    src.read(frame, MASTER_SAMPLE_RATE, MASTER_CHANNELS)
                }.getOrDefault(0)
                when {
                    n > 0 -> {
                        frame.flip()
                        val raw = ByteArray(frame.remaining())
                        frame.get(raw)
                        // 无注入 consumer 时由监听驱动进度 UI（纯预览）
                        if (consumers.isEmpty()) {
                            runtime.setPosition(src.positionMs())
                        }
                        // 与注入同一音量；特效/混响线不与广播协程共享，避免竞态
                        writeToMonitorBlocking(processMonitorOutput(raw))
                        val frameMs = (raw.size.toLong() * 1000L + bytesPerSec - 1) / bytesPerSec
                        nextSendAt += frameMs.coerceAtLeast(1L)
                        val now = System.currentTimeMillis()
                        val sleep = nextSendAt - now
                        if (sleep > 0) delay(sleep)
                        else if (sleep < -200) nextSendAt = now
                    }
                    n == -1 -> {
                        // 等注入侧 LOOP/换源重建 fork；不在监听侧擅自 reset
                        delay(50)
                        nextSendAt = System.currentTimeMillis()
                    }
                    else -> delay(5)
                }
            }
        }
    }

    private fun stopIndependentMonitorPump() {
        monitorPumpJob?.cancel()
        monitorPumpJob = null
    }

    /** 丢掉 consumer 队列里尚未写出的虚拟 PCM，避免切真麦后仍灌出旧帧（含 TTS 开头）。 */
    private fun drainAllConsumerQueues() {
        consumers.values.forEach { c ->
            while (true) {
                val result = c.queue.tryReceive()
                if (result.isFailure) break
            }
        }
    }

    private fun startMonitorTrack() {
        synchronized(monitorLock) {
            stopMonitorTrackLocked()
            runCatching {
                val minBuffer = AudioTrack.getMinBufferSize(
                    MASTER_SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(8192 * 2)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            // 用 SONIFICATION，避免 USAGE_MEDIA 抢焦点/改路由导致录音 App ERROR
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(MASTER_SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(minBuffer * 2)
                    .build()
                if (track.state != AudioTrack.STATE_INITIALIZED) {
                    track.release()
                    GlassLog.b("Publisher") { "AudioTrack 初始化失败 state=${track.state}" }
                    return@runCatching
                }
                track.play()
                monitorTrack = track
            }.onFailure {
                GlassLog.b("Publisher") { "AudioTrack 创建失败: ${it.message}" }
            }
        }
    }

    private fun stopMonitorTrack() {
        synchronized(monitorLock) {
            stopMonitorTrackLocked()
        }
    }

    private fun stopMonitorTrackLocked() {
        monitorTrack?.runCatching {
            pause()
            flush()
            release()
        }
        monitorTrack = null
    }

    private fun writeToMonitorBlocking(data: ByteArray) {
        if (!monitorEnabled) return
        var offset = 0
        while (offset < data.size) {
            if (!monitorEnabled) return
            // 取引用后在锁外 write，避免 WRITE_BLOCKING 卡住 start/stop
            val track = synchronized(monitorLock) { monitorTrack } ?: return
            val written = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                track.write(data, offset, data.size - offset, AudioTrack.WRITE_BLOCKING)
            } else {
                track.write(data, offset, data.size - offset)
            }
            when {
                written > 0 -> offset += written
                written == AudioTrack.ERROR_INVALID_OPERATION ||
                    written == AudioTrack.ERROR_BAD_VALUE -> {
                    // 关闭过程中 track 被释放属预期，禁止误重建
                    if (!monitorEnabled) return
                    GlassLog.b("Publisher") { "monitor write error=$written，重建 AudioTrack" }
                    synchronized(monitorLock) {
                        if (monitorTrack === track) stopMonitorTrackLocked()
                    }
                    if (monitorEnabled) startMonitorTrack()
                    return
                }
                else -> {
                    // track 已被 stop/替换，或短暂不可写
                    if (!monitorEnabled || synchronized(monitorLock) { monitorTrack !== track }) return
                    try {
                        Thread.sleep(2)
                    } catch (_: InterruptedException) {
                        return
                    }
                }
            }
        }
    }

    /** 悬浮窗拖动音量条时立即生效，避免等 DataStore 写入。 */
    fun setOutputVolumeRuntime(volume: Float) {
        outputVolumeRuntime = volume.coerceIn(0f, 2f)
    }

    fun outputVolumeBlocking(): Float = outputVolume()

    private fun outputVolume(): Float {
        if (!outputVolumeRuntime.isNaN()) {
            return outputVolumeRuntime.coerceIn(0f, 2f)
        }
        // proto 默认 0 表示尚未写入，按 1.0 处理；已持久化的 0 仍可通过 runtime 静音
        val v = configStore.currentBlocking().floatingWindow.outputVolume
        return if (v <= 0f) 1f else v.coerceIn(0f, 2f)
    }

    private fun processOutput(data: ByteArray): ByteArray {
        val vol = outputVolume()
        val needVolume = vol < 0.999f || vol > 1.001f
        val effected = applyEffects(data)
        if (!needVolume) return effected
        val buf = if (effected === data) data.copyOf() else effected
        applyVolumeInPlace(buf, vol)
        return buf
    }

    /** 本地监听专用：只做音量，不碰注入侧混响延迟线。 */
    private fun processMonitorOutput(data: ByteArray): ByteArray {
        val vol = outputVolume()
        if (vol >= 0.999f && vol <= 1.001f) return data
        val buf = data.copyOf()
        applyVolumeInPlace(buf, vol)
        return buf
    }

    private fun applyVolumeInPlace(data: ByteArray, volume: Float) {
        if (volume <= 0f) {
            data.fill(0)
            return
        }
        if (volume >= 0.999f && volume <= 1.001f) return
        var i = 0
        while (i + 1 < data.size) {
            val sample = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF)).toShort().toInt()
            val scaled = (sample * volume).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            data[i] = (scaled and 0xFF).toByte()
            data[i + 1] = ((scaled ushr 8) and 0xFF).toByte()
            i += 2
        }
    }

    /** 把一帧 PCM16 下采样成 [points] 个峰值振幅点（0..1），供波形显示。 */
    private fun downsample(pcm: ByteArray, points: Int): FloatArray {
        val samples = pcm.size / 2
        val out = FloatArray(points)
        if (samples <= 0) return out
        val per = (samples / points).coerceAtLeast(1)
        for (p in 0 until points) {
            val start = p * per
            if (start >= samples) break
            val end = minOf(start + per, samples)
            var peak = 0
            var i = start
            while (i < end) {
                val s = ((pcm[i * 2 + 1].toInt() shl 8) or (pcm[i * 2].toInt() and 0xFF)).toShort().toInt()
                val a = if (s < 0) -s else s
                if (a > peak) peak = a
                i++
            }
            out[p] = (peak / 32768f).coerceIn(0f, 1f)
        }
        return out
    }

    private fun applyEffects(input: ByteArray): ByteArray {
        val fx = effects
        // 未启用混响时清空延迟线，避免下次开启时残留旧回声
        if (!fx.reverb) reverbLine.reset()

        // 变速（变速变调）：线性重采样，改变样本数量
        val data = if (fx.speed) resample(input, fx.speedFactor) else input

        if (!fx.noiseSim && !fx.highGain && !fx.reverb) return data

        var i = 0
        while (i + 1 < data.size) {
            var mixed = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF)).toShort().toInt()
            if (fx.highGain) {
                mixed = (mixed * HIGH_GAIN_MULTIPLIER).toInt()
            }
            if (fx.noiseSim) {
                mixed += Random.nextInt(-NOISE_SIM_AMPLITUDE, NOISE_SIM_AMPLITUDE + 1)
            }
            if (fx.reverb) {
                mixed = reverbLine.process(mixed, fx.reverbAmount)
            }
            val clipped = mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            data[i] = (clipped and 0xFF).toByte()
            data[i + 1] = ((clipped ushr 8) and 0xFF).toByte()
            i += 2
        }
        return data
    }

    /** PCM16 线性重采样：speed>1 加速（样本变少、音调升高），speed<1 减速。 */
    private fun resample(input: ByteArray, speed: Float): ByteArray {
        val inSamples = input.size / 2
        if (inSamples <= 0) return input
        val outSamples = (inSamples / speed).toInt().coerceAtLeast(1)
        val out = ByteArray(outSamples * 2)
        var j = 0
        while (j < outSamples) {
            val srcPos = j * speed
            val i0 = srcPos.toInt()
            val frac = srcPos - i0
            val s0 = sampleAt(input, i0, inSamples)
            val s1 = sampleAt(input, i0 + 1, inSamples)
            val v = (s0 + (s1 - s0) * frac).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[j * 2] = (v and 0xFF).toByte()
            out[j * 2 + 1] = ((v ushr 8) and 0xFF).toByte()
            j++
        }
        return out
    }

    private fun sampleAt(b: ByteArray, idx: Int, total: Int): Float {
        val i = idx.coerceIn(0, total - 1)
        return (((b[i * 2 + 1].toInt() shl 8) or (b[i * 2].toInt() and 0xFF)).toShort()).toFloat()
    }

    private fun startConsumerWriter(consumer: Consumer) {
        scope.launch {
            try {
                for (data in consumer.queue) {
                    try {
                        consumer.out.write(data)
                        consumer.out.flush()
                    } finally {
                        consumer.pending.updateAndGet { (it - 1).coerceAtLeast(0) }
                    }
                }
            } catch (t: Throwable) {
                GlassLog.b("Publisher") { "consumer ${consumer.id} 写失败: ${t.message}" }
            } finally {
                consumer.pending.set(0)
                detach(consumer.id)
            }
        }
    }

    fun detach(id: String) {
        consumers.remove(id)?.let { c ->
            runCatching { c.queue.close() }
            runCatching { c.out.close() }
            runCatching { c.fd.close() }
            GlassLog.b("Publisher") { "consumer 已断开: ${c.id}" }
        }
    }

    private fun detachAll() {
        consumers.keys.toList().forEach { detach(it) }
    }

    private fun buildConsumerId(pkg: String): String =
        "$pkg-${android.os.Process.myPid()}-${consumerSeq.incrementAndGet()}"
}

private fun ProtoPolicy.toCore(): PlaybackPolicy = when (this) {
    ProtoPolicy.SILENCE -> PlaybackPolicy.SILENCE
    ProtoPolicy.LOOP -> PlaybackPolicy.LOOP
    ProtoPolicy.REAL_MIC -> PlaybackPolicy.REAL_MIC
    else -> PlaybackPolicy.LOOP
}

/** 单抽头反馈延迟线，产生简单混响。仅在广播协程单线程访问。 */
private class ReverbLine(size: Int) {
    private val buf = ShortArray(size)
    private var idx = 0

    fun reset() {
        buf.fill(0)
        idx = 0
    }

    /** amount 0..1：控制湿信号比例与反馈量。返回叠加后的样本（未限幅，由调用方裁剪）。 */
    fun process(input: Int, amount: Float): Int {
        val delayed = buf[idx].toInt()
        val out = input + (delayed * amount).toInt()
        val feedback = (input + delayed * amount * 0.6f).toInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        buf[idx] = feedback.toShort()
        idx++
        if (idx >= buf.size) idx = 0
        return out
    }
}
