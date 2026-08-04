package io.mo.glassmic.service

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.mo.glassmic.R
import io.mo.glassmic.core.model.SourceType
import io.mo.glassmic.data.audio.FloatingIconStore
import io.mo.glassmic.data.audio.PlaybackController
import io.mo.glassmic.data.config.AppLocale
import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.data.db.AudioDao
import io.mo.glassmic.data.runtime.RuntimeStateHolder
import io.mo.glassmic.log.GlassLog
import io.mo.glassmic.proto.AppConfig
import io.mo.glassmic.proto.FloatingSize
import io.mo.glassmic.ui.theme.LocalGlassEnabled
import io.mo.glassmic.ui.theme.LocalReduceMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 悬浮窗服务（Compose 版）。
 *
 * 三态：球态 / 迷你播放条 / 选曲菜单，全部渲染在同一个挂到 WindowManager 的 ComposeView 里。
 * 手势：
 * - 点击球 → 恢复上次收起前的界面；尚无记录时：播放中开迷你条，否则开选曲
 * - 音频库 / TTS 等大面板点窗外 → 收起为球（并记下当前界面）；迷你条不因点外收起
 * - 展开态始终 NOT_TOUCH_MODAL，窗外可操作下层 App
 * - 迷你条内「换音源」→ 音频库（若在播 TTS 则恢复上次音源并暂停）；「TTS」直达文字转语音
 * - 选中片段 → 设为当前音源（暂停，不自动播）并回到迷你条
 * - TTS 播放成功 → 留在 TTS 面板（面板内可暂停/继续，不跳迷你条）
 * - 拖动球移动，松手贴边
 */
@AndroidEntryPoint
class FloatingWindowService : LifecycleService() {

    @Inject lateinit var runtime: RuntimeStateHolder
    @Inject lateinit var playback: PlaybackController
    @Inject lateinit var configStore: ConfigStore
    @Inject lateinit var audioDao: AudioDao
    @Inject lateinit var iconStore: FloatingIconStore

    private var windowManager: WindowManager? = null
    private var host: FloatingOverlayHost? = null
    private var params: WindowManager.LayoutParams? = null

    private val modeFlow = MutableStateFlow(FloatMode.BALL)

    /**
     * 上次展开态界面。收起为球时写入；再点球优先还原，避免总是跳回默认选曲/迷你条。
     */
    private var lastExpandedMode: FloatMode? = null

    /**
     * 悬浮球的锚点位置（用户拖动决定）。
     * 展开态（菜单/迷你条/TTS）面板比球宽，靠右时会被 [clampExpandedX] 临时左移避让屏幕边缘，
     * 但该偏移不写回锚点；收起时按锚点还原，否则球会一次次被"推"到屏幕左侧。
     */
    private var anchorX = DEFAULT_X
    private var anchorY = DEFAULT_Y

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            GlassLog.b("Float") { "overlay permission missing; stop floating window" }
            stopSelf()
            return
        }
        showOverlay()
        runtime.setFloatingVisible(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        host?.let { h ->
            runCatching { windowManager?.removeView(h.view) }
            h.onDestroy()
        }
        host = null
        runtime.setFloatingVisible(false)
        super.onDestroy()
    }

    private fun showOverlay() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            ballWindowFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = anchorX
            y = anchorY
        }
        params = lp

        // Service 不是 Activity，Android 13 以下不会自动跟随应用内切换的语言，
        // 这里手动包一层 Locale Context 传给 ComposeView，保证悬浮窗文案也能正确显示。
        val overlayHost = FloatingOverlayHost(AppLocale.wrap(this)).also { it.onCreate() }
        host = overlayHost
        // 大面板点窗外收起（迷你条保留，方便边用其它 App 边控制播放）
        // FLAG_WATCH_OUTSIDE_TOUCH + NOT_TOUCH_MODAL：触摸仍落到下层 App
        overlayHost.view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                when (modeFlow.value) {
                    FloatMode.MENU, FloatMode.TTS, FloatMode.TTS_SETTINGS -> setMode(FloatMode.BALL)
                    else -> Unit
                }
            }
            false
        }
        overlayHost.setContent {
            // 悬浮窗以前只包了裸 MaterialTheme{}，Slider/Switch 会用 Material3 默认紫色，
            // 和面板里的绿色 Accent 打架。挂上 OverlayColorScheme 后它们自动跟随主色。
            MaterialTheme(colorScheme = OverlayColorScheme) {
                val rt by runtime.flow.collectAsState()
                val cfg by configStore.flow.collectAsState(initial = AppConfig.getDefaultInstance())
                val groups by audioDao.observeGroups().collectAsState(initial = emptyList())
                val allClips by audioDao.observeAllClips().collectAsState(initial = emptyList())
                val mode by modeFlow.collectAsState()
                val ttsGen by playback.ttsGen.collectAsState()
                val ttsDelayRemainingMs by playback.ttsDelayRemainingMs.collectAsState()
                val localMonitorRuntime by playback.localMonitorEnabled.collectAsState()
                val generatedTtsText by playback.generatedTtsText.collectAsState()

                val activeFile = (rt.currentSourceType == SourceType.FILE || rt.currentSourceType == SourceType.TTS) &&
                    rt.enabled && !rt.safeMode
                // 非虚拟音源（真麦）或已暂停 → 显示 ▶；仅虚拟音源播放中显示 ⏸
                val showPaused = !activeFile || rt.paused
                val currentId = cfg.currentAudioId
                val playingFileId = rt.currentAudioId.takeIf {
                    rt.currentSourceType == SourceType.FILE && !it.isNullOrBlank()
                }
                val highlightClipId = playingFileId ?: currentId.takeIf { it.isNotBlank() }
                val currentName = when (rt.currentSourceType) {
                    SourceType.TTS -> getString(R.string.source_tts)
                    SourceType.FILE -> allClips.firstOrNull { it.id == (playingFileId ?: currentId) }?.displayName
                    SourceType.REAL_MIC -> getString(R.string.source_real_mic)
                    else -> allClips.firstOrNull { it.id == currentId }?.displayName
                }

                // 悬浮窗不走 GlassMicTheme，这两个外观开关得在这里手动注入
                CompositionLocalProvider(
                    LocalGlassEnabled provides cfg.appearance.glassEffect,
                    LocalReduceMotion provides cfg.appearance.reduceMotion,
                ) {
                FloatingBubbleRoot(
                    mode = mode,
                    activeFile = activeFile,
                    paused = showPaused,
                    positionMs = rt.positionMs,
                    durationMs = rt.durationMs,
                    currentName = currentName,
                    sizeDp = sizeToDp(cfg.floatingWindow.size),
                    iconPath = cfg.floatingWindow.customIconPath.takeIf { it.isNotBlank() }
                        ?.let { iconStore.iconFile(it).absolutePath },
                    opacity = cfg.floatingWindow.opacity.takeIf { it > 0f } ?: 0.85f,
                    groups = groups.map { FloatGroupItem(it.id, it.emoji, it.name) },
                    clipsProvider = { gid ->
                        audioDao.observeClipsInGroup(gid).map { list ->
                            list.map { FloatClipItem(it.id, it.displayName, it.id == highlightClipId) }
                        }
                    },
                    onBallTap = { onBallTap(activeFile) },
                    onTogglePause = {
                        // 仅虚拟音源在播/暂停时切换；真麦态不再自动重播 TTS
                        // （播完瞬间 ▶ 与点击竞态会听到「第二次开头」）
                        if (activeFile) playback.togglePause()
                    },
                    onSeek = { frac -> onSeek(frac, rt.durationMs) },
                    onOpenMenu = { onOpenLibrary() },
                    onCollapse = { setMode(FloatMode.BALL) },
                    onSelectClip = { clipId -> onSelectClip(clipId) },
                    onOpenTts = { onOpenTtsPanel() },
                    onOpenVirtualSource = { onOpenVirtualSource() },
                    ttsGenerating = ttsGen == PlaybackController.TtsGen.GENERATING,
                    ttsReady = ttsGen == PlaybackController.TtsGen.READY,
                    ttsFailed = ttsGen == PlaybackController.TtsGen.FAILED,
                    generatedTtsText = generatedTtsText,
                    onGenerateTts = { text -> onGenerateTts(text) },
                    onPlayTts = { text -> onPlayTts(text) },
                    ttsProgressBarEnabled = cfg.tts.progressBarEnabled,
                    ttsActive = rt.currentSourceType == SourceType.TTS,
                    ttsPlaybackPolicy = cfg.tts.playbackPolicy,
                    onOpenTtsSettings = { setMode(FloatMode.TTS_SETTINGS) },
                    onToggleTtsProgressBar = { enabled -> onToggleTtsProgressBar(enabled) },
                    onSetTtsPlaybackPolicy = { policy -> onSetTtsPlaybackPolicy(policy) },
                    ttsDelayMs = cfg.tts.delayMs,
                    onSetTtsDelay = { ms -> onSetTtsDelay(ms) },
                    ttsDelayRemainingMs = ttsDelayRemainingMs,
                    onCancelDelayedTts = { onCancelDelayedTts() },
                    onSeekTts = { frac -> onSeek(frac, rt.durationMs) },
                    onCloseTtsSettings = { setMode(FloatMode.TTS) },
                    localMonitor = localMonitorRuntime,
                    // 优先用运行时音量，避免拖动中/刚调完时 UI 与真实增益脱节
                    outputVolume = playback.outputVolumeBlocking(),
                    onToggleLocalMonitor = { enabled ->
                        lifecycleScope.launch { playback.setLocalMonitorEnabled(enabled) }
                    },
                    onOutputVolumeChange = { vol -> playback.setOutputVolumeRuntime(vol) },
                    onOutputVolumeChangeFinished = { vol ->
                        lifecycleScope.launch { playback.setOutputVolume(vol) }
                    },
                    recordingBlocked = rt.recordingBlockUntilMs > 0L &&
                        System.currentTimeMillis() < rt.recordingBlockUntilMs,
                    onReleaseMicOccupation = releaseMic@{
                        // 冷却期内重复点击直接忽略
                        if (releaseMicJob?.isActive == true) return@releaseMic
                        releaseMicJob = lifecycleScope.launch {
                            val result = playback.forceReleaseMicOccupation()
                            android.widget.Toast.makeText(
                                this@FloatingWindowService,
                                result.message,
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    onDragBy = { dx, dy -> onDragBy(dx, dy) },
                    onDragEnd = { onDragEnd() },
                )
                }
            }
        }
        runCatching { wm.addView(overlayHost.view, lp) }
            .onFailure { GlassLog.b("Float") { "addView 失败: ${it.message}" } }
    }

    // ============ 手势 / 交互回调 ============
    private fun onBallTap(activeFile: Boolean) {
        val restore = lastExpandedMode
        setMode(
            when {
                restore != null -> restore
                activeFile -> FloatMode.MINI_BAR
                else -> FloatMode.MENU
            }
        )
    }

    /** 打开音频库选曲（迷你条「换音源」）。 */
    private fun onOpenLibrary() {
        setMode(FloatMode.MENU)
    }

    /**
     * TTS → 当前虚拟音源：恢复上次选中的文件音源（暂停），进入迷你条。
     * 若还没有选过音源，再落到选曲界面。
     */
    private fun onOpenVirtualSource() {
        cancelTtsJobs()
        lifecycleScope.launch {
            val ok = playback.switchToLibrarySource()
            setMode(if (ok) FloatMode.MINI_BAR else FloatMode.MENU)
        }
    }

    /** 打开 TTS：暂停当前文件音源，保证不会和 TTS「同时在播」。 */
    private fun onOpenTtsPanel() {
        setMode(FloatMode.TTS)
        if (runtime.value.currentSourceType == SourceType.FILE && !playback.isPaused) {
            playback.pause()
        }
    }

    private fun onSelectClip(clipId: String) {
        cancelTtsJobs()
        lifecycleScope.launch {
            // 选中即设为当前音源，但暂停——与 TTS 互斥，由用户在迷你条点播放
            val ok = playback.setCurrentClip(clipId, startPaused = true)
            if (ok) setMode(FloatMode.MINI_BAR)
            else GlassLog.b("Float") { "选中片段失败: $clipId" }
        }
    }

    private var ttsGenerateJob: Job? = null
    private var releaseMicJob: Job? = null
    /** 延时播放期间持有倒计时协程，供再次点击时取消。 */
    private var ttsPlayJob: Job? = null

    private fun cancelTtsJobs() {
        ttsPlayJob?.cancel()
        ttsPlayJob = null
        ttsGenerateJob?.cancel()
        ttsGenerateJob = null
        playback.clearTtsDelayCountdown()
    }

    private fun onGenerateTts(text: String) {
        ttsGenerateJob?.cancel()
        ttsGenerateJob = lifecycleScope.launch {
            val ok = playback.generateTts(text)
            if (!ok) GlassLog.b("Float") { "TTS 生成失败（文本为空或引擎不可用）" }
        }
    }

    private fun onPlayTts(text: String) {
        // 取消上一次延时/生成/播放，避免叠两次；文案变了会先生成再播
        ttsPlayJob?.cancel()
        ttsGenerateJob?.cancel()
        ttsPlayJob = lifecycleScope.launch {
            val ok = playback.playTts(text)
            // 留在 TTS 面板：播放按钮会变成暂停/继续，跳迷你条反而打断编辑/重播
            if (!ok) GlassLog.b("Float") { "TTS 播放失败（文本为空或生成失败）" }
        }
    }

    /** 倒计时中再点一次播放按钮 → 取消，不出声。 */
    private fun onCancelDelayedTts() {
        ttsPlayJob?.cancel()
        ttsPlayJob = null
        playback.clearTtsDelayCountdown()
    }

    private fun onToggleTtsProgressBar(enabled: Boolean) {
        lifecycleScope.launch {
            configStore.update {
                it.setTts(it.tts.toBuilder().setProgressBarEnabled(enabled).build())
            }
        }
    }

    private fun onSetTtsPlaybackPolicy(policy: io.mo.glassmic.proto.PlaybackPolicy) {
        lifecycleScope.launch {
            configStore.update {
                it.setTts(it.tts.toBuilder().setPlaybackPolicy(policy).build())
            }
        }
    }

    private fun onSetTtsDelay(delayMs: Int) {
        lifecycleScope.launch {
            configStore.update {
                it.setTts(it.tts.toBuilder().setDelayMs(delayMs.coerceIn(0, MAX_TTS_DELAY_MS)).build())
            }
        }
    }

    /** 进度条松手后的唯一 seek；拖动过程中不应调用（见 SeekBar）。 */
    private var seekJob: Job? = null

    private fun onSeek(frac: Float, durationMs: Long) {
        if (durationMs <= 0) return
        val target = (durationMs * frac).toLong().coerceIn(0L, durationMs)
        runtime.setPosition(target)
        seekJob?.cancel()
        seekJob = lifecycleScope.launch { playback.seekTo(target) }
    }

    private fun onDragBy(dx: Float, dy: Float) {
        val lp = params ?: return
        val h = host ?: return
        lp.x += dx.toInt()
        lp.y += dy.toInt()
        runCatching { windowManager?.updateViewLayout(h.view, lp) }
    }

    private fun onDragEnd() {
        // 松手后停在原地，仅做边界兜底，允许自由悬停（不贴边）
        clampToBounds()
        // 用户主动拖动即视为重新选定位置：同步锚点，收起时才不会跳回旧位置
        params?.let { anchorX = it.x; anchorY = it.y }
    }

    private fun setMode(mode: FloatMode) {
        val prev = modeFlow.value
        if (mode == FloatMode.BALL && prev != FloatMode.BALL) {
            lastExpandedMode = prev
        }
        modeFlow.value = mode
        // TTS 面板与其设置面板（自定义延时输入）需要输入法焦点，其它态保持不可聚焦
        applyWindowFlags(mode)
        // 展开态（迷你条/菜单/TTS）确保不超出屏幕右边；收起时还原到球的锚点
        if (mode != FloatMode.BALL) clampExpandedX() else restoreAnchor()
    }

    /**
     * 收起为球态时还原用户拖动过的锚点位置。
     * 边界兜底放到 post 里执行：此刻 view 仍是展开态的宽度，需等重新测量成球的尺寸后再 clamp，
     * 否则会用面板宽度去约束球，把靠右的球再次推向左边。
     */
    private fun restoreAnchor() {
        val lp = params ?: return
        val h = host ?: return
        lp.x = anchorX
        lp.y = anchorY
        runCatching { windowManager?.updateViewLayout(h.view, lp) }
        h.view.post { clampToBounds() }
    }

    /**
     * 按模式切换窗口 flags。
     * - 始终 NOT_TOUCH_MODAL：窗外触摸落到下层 App，展开时也不挡操作
     * - 展开态加 WATCH_OUTSIDE_TOUCH：收到 ACTION_OUTSIDE 后收起
     * - TTS / 设置需要软键盘时去掉 NOT_FOCUSABLE
     */
    private fun applyWindowFlags(mode: FloatMode) {
        val lp = params ?: return
        val h = host ?: return
        lp.flags = if (mode == FloatMode.BALL) {
            ballWindowFlags()
        } else {
            expandedWindowFlags(focusable = mode == FloatMode.TTS || mode == FloatMode.TTS_SETTINGS)
        }
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        runCatching { windowManager?.updateViewLayout(h.view, lp) }
    }

    private fun ballWindowFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    private fun expandedWindowFlags(focusable: Boolean): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!focusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        return flags
    }

    /** 边界兜底：把悬浮球约束在屏幕内，但不贴边，保留自由悬停位置。 */
    private fun clampToBounds() {
        val lp = params ?: return
        val h = host ?: return
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val viewW = h.view.width.takeIf { it > 0 } ?: dpToPx(56)
        val viewH = h.view.height.takeIf { it > 0 } ?: dpToPx(56)
        lp.x = lp.x.coerceIn(0, (screenW - viewW).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (screenH - viewH).coerceAtLeast(0))
        runCatching { windowManager?.updateViewLayout(h.view, lp) }
    }

    private fun clampExpandedX() {
        val lp = params ?: return
        val h = host ?: return
        val screenW = resources.displayMetrics.widthPixels
        val panelW = dpToPx(EXPANDED_PANEL_WIDTH_DP)
        if (lp.x + panelW > screenW) lp.x = (screenW - panelW).coerceAtLeast(8)
        if (lp.x < 8) lp.x = 8
        runCatching { windowManager?.updateViewLayout(h.view, lp) }
    }

    private fun sizeToDp(size: FloatingSize): Dp = when (size) {
        FloatingSize.SMALL -> 44.dp
        FloatingSize.LARGE -> 72.dp
        else -> 56.dp   // STANDARD / UNRECOGNIZED
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    companion object {
        private const val DEFAULT_X = 24
        private const val DEFAULT_Y = 300

        /** 自定义延时上限 60s：再长就该用别的方式了，也避免误输入把语音永远压住。 */
        const val MAX_TTS_DELAY_MS = 60_000

        fun start(ctx: Context) {
            if (!Settings.canDrawOverlays(ctx)) return
            ctx.startService(Intent(ctx, FloatingWindowService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, FloatingWindowService::class.java))
        }
    }
}
