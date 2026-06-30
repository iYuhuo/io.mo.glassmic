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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
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
 * 一个解码线程 + N 个独立写出协程，避免单个慢 consumer 阻塞全局广播。
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
        val converter: Pcm16Converter
    )

    private data class AudioEffects(
        val noiseSim: Boolean = false,
        val highGain: Boolean = false,
        val limiterEnabled: Boolean = true
    )

    private val consumers = ConcurrentHashMap<String, Consumer>()
    private val consumerSeq = AtomicLong(0L)
    private val sourceMutex = Mutex()
    @Volatile private var suppressWatchdog = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private companion object {
        // 当前所有路径都按 PCM_16 处理；后续如果接入 PCM_FLOAT / PCM_8 需要参数化
        const val BYTES_PER_SAMPLE = 2
        const val MASTER_SAMPLE_RATE = 48_000
        const val MASTER_CHANNELS = 1
        const val NOISE_SIM_AMPLITUDE = 6_000
        const val HIGH_GAIN_MULTIPLIER = 1.8f
    }

    @Volatile private var currentSource: AudioSourceProvider = SilenceSource
    @Volatile private var writerStarted = false
    @Volatile private var paused: Boolean = false
    @Volatile private var monitorEnabled = false
    @Volatile private var outputVolumeRuntime = Float.NaN
    @Volatile private var effects = AudioEffects()
    private val monitorLock = Any()
    private var monitorTrack: AudioTrack? = null
    private var monitorWriterStarted = false
    private val monitorQueue = Channel<ByteArray>(
        capacity = 12,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val isLocalMonitorEnabled: Boolean get() = monitorEnabled

    init {
        scope.launch {
            configStore.flow.collect { cfg ->
                val exp = cfg.experimental
                effects = AudioEffects(
                    noiseSim = exp.unlocked && exp.noiseSim,
                    highGain = exp.unlocked && exp.highGain,
                    limiterEnabled = exp.limiterEnabled
                )
            }
        }
    }

    val isPaused: Boolean get() = paused

    /** 本地监听：把虚拟麦克风输出的 PCM 同步播放到扬声器/耳机。 */
    fun setLocalMonitor(enabled: Boolean) {
        if (monitorEnabled == enabled) return
        monitorEnabled = enabled
        if (enabled) {
            startMonitorTrack()
            ensureMonitorWriterRunning()
            ensureWriterRunning()
        } else {
            stopMonitorTrack()
            drainMonitorQueue()
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

    /** FILE 替麦时须持续泵数据，即使尚无 pipe consumer 或本地监听。 */
    private fun shouldKeepPublishing(): Boolean {
        val rt = runtime.value
        if (rt.realMicPassThrough) return false
        if (consumers.isNotEmpty() || monitorEnabled) return true
        if (!rt.enabled || rt.safeMode || rt.currentSourceType != SourceType.FILE) return false
        if (rt.paused) return false
        return true
    }

    /** 把当前 FileAudioSource 跳转到指定毫秒。其他类型源忽略。 */
    suspend fun seekCurrent(positionMs: Long) {
        val src = currentSource
        if (src is FileAudioSource) src.seekTo(positionMs)
        runtime.setPosition(positionMs)
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
        val queue = Channel<ByteArray>(
            capacity = 8,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
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

    suspend fun setSource(
        src: AudioSourceProvider,
        groupId: String? = null,
        audioId: String? = null
    ) {
        sourceMutex.withLock {
            replaceSourceLocked(src, updateRuntime = true, groupId = groupId, audioId = audioId)
        }
    }

    private fun replaceSourceLocked(
        src: AudioSourceProvider,
        updateRuntime: Boolean,
        groupId: String? = null,
        audioId: String? = null
    ) {
        suppressWatchdog = true
        try {
            currentSource.release()
            currentSource = src
            if (src.type != SourceType.FILE && monitorEnabled) {
                setLocalMonitor(false)
            }
            if (updateRuntime) {
                runtime.setSource(
                    type = src.type,
                    groupId = groupId,
                    audioId = audioId,
                    durationMs = src.durationMs()
                )
            }
            GlassLog.b("Publisher") {
                "切换音源 → ${src.type}, group=$groupId audio=$audioId, updateRuntime=$updateRuntime"
            }
        } finally {
            suppressWatchdog = false
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
                if (!shouldKeepPublishing()) {
                    kotlinx.coroutines.delay(50)
                    nextSendAt = System.currentTimeMillis()
                    continue
                }
                frame.clear()
                val sr = MASTER_SAMPLE_RATE
                val ch = MASTER_CHANNELS
                val bytesPerSec = sr.toLong() * ch * BYTES_PER_SAMPLE

                val readResult = sourceMutex.withLock {
                    val readSource = if (paused) SilenceSource else currentSource
                    runCatching { readSource.read(frame, sr, ch) to readSource }.getOrElse { e ->
                        if (!suppressWatchdog) {
                            watchdog.onAudioEngineFailure()
                        }
                        GlassLog.b("Publisher") { "read 失败: ${e.message}，降级静音" }
                        replaceSourceLocked(SilenceSource, updateRuntime = true)
                        0 to SilenceSource
                    }
                }
                val n = readResult.first
                val readSource = readResult.second

                when {
                    n > 0 -> {
                        watchdog.onAudioEngineRecovered()
                        frame.flip()
                        if (!paused) runtime.setPosition(readSource.positionMs())
                        broadcast(frame)

                        // n 字节代表 (n / bytesPerSec) 秒音频——按这个真实时长节流
                        val frameMs = (n.toLong() * 1000L + bytesPerSec - 1) / bytesPerSec
                        nextSendAt += frameMs
                        val now = System.currentTimeMillis()
                        val sleep = nextSendAt - now
                        if (sleep > 0) {
                            kotlinx.coroutines.delay(sleep)
                        } else if (sleep < -200) {
                            // 落后超过 200ms（被 GC / 系统抢占），追平基准，避免突然爆发
                            nextSendAt = now
                        }
                    }
                    n == -1 -> {
                        handleEof()
                        nextSendAt = System.currentTimeMillis()
                    }
                    else -> kotlinx.coroutines.delay(5)
                }
            }
        }
    }

    private suspend fun handleEof() = sourceMutex.withLock {
        val policy = configStore.current().playbackPolicy.toCore()
        when (policy) {
            PlaybackPolicy.LOOP -> currentSource.reset()
            PlaybackPolicy.SILENCE -> replaceSourceLocked(SilenceSource, updateRuntime = true)
            PlaybackPolicy.REAL_MIC -> {
                runtime.setSource(SourceType.REAL_MIC)
                detachAll()
                replaceSourceLocked(SilenceSource, updateRuntime = false)
            }
        }
    }

    private fun broadcast(buf: ByteBuffer) {
        val data = ByteArray(buf.remaining())
        buf.get(data)
        val sourceData = if (!paused && currentSource.type == SourceType.FILE) {
            processOutput(data)
        } else {
            data
        }
        consumers.values.forEach { c ->
            val payload = c.converter.convert(sourceData)
            if (payload.isEmpty()) return@forEach
            val result = c.queue.trySend(payload)
            if (result.isFailure) {
                GlassLog.b("Publisher") { "consumer ${c.id} 队列不可用，断开" }
                detach(c.id)
            }
        }
        if (shouldMonitor(sourceData)) {
            val payload = sourceData.copyOf()
            if (monitorQueue.trySend(payload).isFailure) {
                GlassLog.b("Publisher") { "monitor 队列满，丢弃一帧" }
            }
        }
    }

    private fun shouldMonitor(sourceData: ByteArray): Boolean {
        if (!monitorEnabled || paused) return false
        return sourceData.isNotEmpty()
    }

    private fun ensureMonitorWriterRunning() {
        if (monitorWriterStarted) return
        synchronized(this) {
            if (monitorWriterStarted) return
            monitorWriterStarted = true
        }
        scope.launch {
            for (data in monitorQueue) {
                writeToMonitorBlocking(data)
            }
        }
    }

    private fun drainMonitorQueue() {
        while (true) {
            val result = monitorQueue.tryReceive()
            if (result.isFailure) break
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
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
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
        var offset = 0
        while (offset < data.size) {
            val written = synchronized(monitorLock) {
                val t = monitorTrack ?: return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    t.write(data, offset, data.size - offset, AudioTrack.WRITE_BLOCKING)
                } else {
                    t.write(data, offset, data.size - offset)
                }
            }
            when {
                written > 0 -> offset += written
                written == AudioTrack.ERROR_INVALID_OPERATION ||
                    written == AudioTrack.ERROR_BAD_VALUE -> {
                    GlassLog.b("Publisher") { "monitor write error=$written，重建 AudioTrack" }
                    synchronized(monitorLock) { stopMonitorTrackLocked() }
                    startMonitorTrack()
                    return
                }
                else -> {
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
        val v = configStore.currentBlocking().floatingWindow.outputVolume
        return if (v <= 0f) 1f else v.coerceIn(0f, 2f)
    }

    private fun processOutput(data: ByteArray): ByteArray {
        val vol = outputVolume()
        val fx = effects
        val needVolume = vol < 0.999f || vol > 1.001f
        val needFx = fx.noiseSim || fx.highGain
        if (!needVolume && !needFx) return data
        val buf = data.copyOf()
        if (needVolume) applyVolumeInPlace(buf, vol)
        if (needFx) applyEffects(buf)
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

    private fun applyEffects(data: ByteArray): ByteArray {
        val fx = effects
        if (!fx.noiseSim && !fx.highGain) return data

        var i = 0
        while (i + 1 < data.size) {
            val sample = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF)).toShort().toInt()
            var mixed = sample
            if (fx.highGain) {
                mixed = (mixed * HIGH_GAIN_MULTIPLIER).toInt()
            }
            if (fx.noiseSim) {
                mixed += Random.nextInt(-NOISE_SIM_AMPLITUDE, NOISE_SIM_AMPLITUDE + 1)
            }
            val clipped = mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            data[i] = (clipped and 0xFF).toByte()
            data[i + 1] = ((clipped ushr 8) and 0xFF).toByte()
            i += 2
        }
        return data
    }

    private fun startConsumerWriter(consumer: Consumer) {
        scope.launch {
            try {
                for (data in consumer.queue) {
                    consumer.out.write(data)
                    consumer.out.flush()
                }
            } catch (t: Throwable) {
                GlassLog.b("Publisher") { "consumer ${consumer.id} 写失败: ${t.message}" }
            } finally {
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

private class Pcm16Converter(
    private val sourceSampleRate: Int,
    private val sourceChannels: Int,
    private val targetSampleRate: Int,
    private val targetChannels: Int
) {
    private var pending = ShortArray(0)
    private var sourcePosition = 0.0

    fun convert(bytes: ByteArray): ByteArray {
        if (sourceSampleRate == targetSampleRate && sourceChannels == targetChannels) {
            return bytes
        }

        append(bytes)
        if (pending.isEmpty()) return ByteArray(0)

        val step = sourceSampleRate.toDouble() / targetSampleRate.toDouble()
        val frames = ArrayList<ShortArray>(pending.size / sourceChannels)
        while (sourcePosition < frameCount()) {
            val sourceFrame = sourcePosition.toInt()
            frames += mixFrame(sourceFrame)
            sourcePosition += step
        }

        trimConsumedSourceFrames()
        if (frames.isEmpty()) return ByteArray(0)

        val out = ByteArray(frames.size * targetChannels * 2)
        var offset = 0
        frames.forEach { frame ->
            frame.forEach { sample ->
                out[offset++] = (sample.toInt() and 0xFF).toByte()
                out[offset++] = ((sample.toInt() ushr 8) and 0xFF).toByte()
            }
        }
        return out
    }

    private fun append(bytes: ByteArray) {
        val samplesToAdd = bytes.size / 2
        if (samplesToAdd <= 0) return

        val combined = ShortArray(pending.size + samplesToAdd)
        pending.copyInto(combined)
        var src = 0
        var dst = pending.size
        while (src + 1 < bytes.size) {
            val lo = bytes[src].toInt() and 0xFF
            val hi = bytes[src + 1].toInt()
            combined[dst++] = ((hi shl 8) or lo).toShort()
            src += 2
        }
        pending = combined
    }

    private fun frameCount(): Int = pending.size / sourceChannels

    private fun mixFrame(frameIndex: Int): ShortArray {
        val base = frameIndex * sourceChannels
        return ShortArray(targetChannels) { channel ->
            when {
                sourceChannels == 1 -> pending.getOrElse(base) { 0 }
                targetChannels == 1 -> {
                    val left = pending.getOrElse(base) { 0 }.toInt()
                    val right = pending.getOrElse(base + 1) { 0 }.toInt()
                    ((left + right) / 2).toShort()
                }
                channel < sourceChannels -> pending.getOrElse(base + channel) { 0 }
                else -> 0
            }
        }
    }

    private fun trimConsumedSourceFrames() {
        val frames = frameCount()
        val consumed = sourcePosition.toInt().coerceAtMost(frames)
        if (consumed <= 0) return

        val consumedSamples = consumed * sourceChannels
        pending = pending.copyOfRange(consumedSamples, pending.size)
        sourcePosition -= consumed
    }
}
