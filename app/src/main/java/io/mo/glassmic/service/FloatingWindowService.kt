package io.mo.glassmic.service

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.mo.glassmic.core.model.SourceType
import io.mo.glassmic.data.audio.PlaybackController
import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.data.db.AudioClipEntity
import io.mo.glassmic.data.runtime.RuntimeStateHolder
import io.mo.glassmic.log.GlassLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs
import io.mo.glassmic.proto.FloatingMode
import io.mo.glassmic.proto.FloatingSize
import io.mo.glassmic.R

@AndroidEntryPoint
class FloatingWindowService : LifecycleService() {

    @Inject lateinit var runtime: RuntimeStateHolder
    @Inject lateinit var playback: PlaybackController
    @Inject lateinit var configStore: ConfigStore

    private var windowManager: WindowManager? = null
    private var rootView: LinearLayout? = null
    private var textView: TextView? = null
    private var playButton: TextView? = null
    private var progressBar: SeekBar? = null
    private var timeView: TextView? = null
    private var statusDot: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var userSeeking = false
    private var expanded = false
    private var floatingOpacity = 0.85f
    private var currentSize = FloatingSize.STANDARD
    private var lastDisplayedAudioId: String? = null

    // New controls
    private var sourceButton: TextView? = null
    private var monitorSwitch: SwitchCompat? = null
    private var mixSwitch: SwitchCompat? = null
    private var mixLabelView: TextView? = null
    private var monitorRow: LinearLayout? = null
    private var mixRow: LinearLayout? = null
    private var dragHandle: LinearLayout? = null
    private var panelContext: Context? = null
    private var currentClipNameJob: Job? = null
    private var sourcePickerPopup: PopupWindow? = null
    private var monitorSwitchUpdating = false
    private var mixSwitchUpdating = false
    private var lastMonitorSwitchChecked: Boolean? = null
    private var lastMixSwitchChecked: Boolean? = null
    private var localMonitorPreferred = false
    private var realMicMixPreferred = false

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            GlassLog.b("Float") { "overlay permission missing; stop floating window" }
            Toast.makeText(this, "请授予悬浮窗权限", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            stopSelf()
            return
        }

        lifecycleScope.launch {
            val cfg = configStore.current()
            if (!cfg.floatingWindow.enabled || !cfg.globalSwitch) {
                GlassLog.b("Float") { "floating disabled or master switch off; stop" }
                stopSelf()
                return@launch
            }
            showPanelInternal(cfg)
        }
    }

    private suspend fun showPanelInternal(cfg: io.mo.glassmic.proto.AppConfig) {
        currentSize = cfg.floatingWindow.size.takeUnless { it == FloatingSize.UNRECOGNIZED }
            ?: FloatingSize.STANDARD
        floatingOpacity = cfg.floatingWindow.opacity
            .takeIf { it > 0f }
            ?.coerceIn(FloatingWindowConstants.MIN_OPACITY, FloatingWindowConstants.MAX_OPACITY)
            ?: FloatingWindowConstants.DEFAULT_OPACITY

        showPanel()

        layoutParams?.x = cfg.floatingWindow.positionX
            .takeIf { it >= 0 }
            ?: FloatingWindowConstants.DEFAULT_X
        layoutParams?.y = cfg.floatingWindow.positionY
            .takeIf { it >= 0 }
            ?: FloatingWindowConstants.DEFAULT_Y

        rootView?.alpha = floatingOpacity
        localMonitorPreferred = cfg.floatingWindow.localMonitor
        realMicMixPreferred = cfg.floatingWindow.realMicMix
        val startExpanded = cfg.floatingWindow.defaultMode == FloatingMode.PANEL
        setExpanded(startExpanded, updateWindow = false)

        layoutParams?.let { params ->
            runCatching { windowManager?.addView(rootView, params) }
        }
        runtime.setFloatingVisible(true)
        playback.applyLocalMonitorPreference()
        playback.applyRealMicMixPreference()

        lifecycleScope.launch {
            @OptIn(FlowPreview::class)
            runtime.flow.sample(100).collect { rt ->
                val activeFile = rt.currentSourceType == SourceType.FILE && rt.enabled && !rt.safeMode
                updateVisuals(activeFile, rt.paused, rt.positionMs, rt.durationMs, rt)
            }
        }
        lifecycleScope.launch {
            configStore.flow.collect { updated ->
                floatingOpacity = updated.floatingWindow.opacity
                    .takeIf { it > 0f }
                    ?.coerceIn(FloatingWindowConstants.MIN_OPACITY, FloatingWindowConstants.MAX_OPACITY)
                    ?: FloatingWindowConstants.DEFAULT_OPACITY
                rootView?.alpha = floatingOpacity
                localMonitorPreferred = updated.floatingWindow.localMonitor
                realMicMixPreferred = updated.floatingWindow.realMicMix
                playback.applyLocalMonitorPreference()
                playback.applyRealMicMixPreference()
                val size = updated.floatingWindow.size.takeUnless { it == FloatingSize.UNRECOGNIZED }
                    ?: FloatingSize.STANDARD
                if (size != currentSize) {
                    currentSize = size
                    if (expanded) applySize(currentSize)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        sourcePickerPopup?.dismiss()
        sourcePickerPopup = null
        rootView?.let { runCatching { windowManager?.removeView(it) } }
        rootView = null
        textView = null
        playButton = null
        progressBar = null
        timeView = null
        statusDot = null
        sourceButton = null
        monitorSwitch = null
        mixSwitch = null
        mixLabelView = null
        monitorRow = null
        mixRow = null
        dragHandle = null
        panelContext = null
        currentClipNameJob?.cancel()
        currentClipNameJob = null
        lastDisplayedAudioId = null
        lastMonitorSwitchChecked = null
        lastMixSwitchChecked = null
        runtime.setFloatingVisible(false)
        super.onDestroy()
    }

    private fun showPanel() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val uiContext = ContextThemeWrapper(this, R.style.Theme_Yuhuo_Overlay)
        panelContext = uiContext

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = FloatingWindowConstants.DEFAULT_X
            y = FloatingWindowConstants.DEFAULT_Y
        }
        layoutParams = params

        val dot = View(uiContext).apply {
            background = dotBackground(active = false)
            val size = dp(FloatingWindowConstants.STATUS_DOT_SIZE_DP).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                rightMargin = dp(FloatingWindowConstants.EDGE_MARGIN_DP).toInt()
                gravity = Gravity.CENTER_VERTICAL
            }
        }

        val title = TextView(uiContext).apply {
            text = getString(R.string.app_name)
            setTextColor(Color.WHITE)
            textSize = 14f
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
                gravity = Gravity.CENTER_VERTICAL
            }
        }

        val button = TextView(uiContext).apply {
            text = "||"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = buttonBackground(enabled = false)
            val size = dp(FloatingWindowConstants.BUTTON_SIZE_DP).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                leftMargin = dp(FloatingWindowConstants.EDGE_MARGIN_DP).toInt()
                gravity = Gravity.CENTER_VERTICAL
            }
            isEnabled = false
            isClickable = true
            setOnClickListener {
                val wasPaused = playback.isPaused
                playback.togglePause()
                val msg = if (wasPaused) {
                    getString(R.string.toast_resumed_virtual)
                } else {
                    getString(R.string.toast_paused_real_mic)
                }
                Toast.makeText(this@FloatingWindowService, msg, Toast.LENGTH_SHORT).show()
                it.parent?.requestDisallowInterceptTouchEvent(true)
            }
        }

        // Audio source selection button (shows scrollable popup list)
        val srcBtn = TextView(uiContext).apply {
            text = "音源"
            setTextColor(Color.WHITE)
            textSize = 12f
            includeFontPadding = false
            background = buttonBackground(enabled = true)
            val w = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(FloatingWindowConstants.BUTTON_SIZE_DP).toInt())
            w.leftMargin = dp(FloatingWindowConstants.EDGE_MARGIN_DP).toInt()
            layoutParams = w
            setPadding(dp(FloatingWindowConstants.EDGE_MARGIN_DP).toInt(), 0, dp(FloatingWindowConstants.EDGE_MARGIN_DP).toInt(), 0)
            isClickable = true
            setOnClickListener {
                lifecycleScope.launch {
                    val rt = runtime.value
                    if (!rt.enabled || rt.safeMode) {
                        Toast.makeText(this@FloatingWindowService, "请先在主界面启用虚拟麦克风", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val clips = playback.listClips()
                    if (clips.isEmpty()) {
                        Toast.makeText(this@FloatingWindowService, "无可用音源", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    showSourcePicker(this@apply, clips)
                }
            }
        }

        val topRow = LinearLayout(uiContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(dot)
            addView(title)
            addView(srcBtn)
            addView(button)
        }

        val seek = SeekBar(uiContext).apply {
            max = 1000
            progress = 0
            isEnabled = false
            splitTrack = false
            setPadding(0, dp(4f).toInt(), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(FloatingWindowConstants.SEEK_BAR_HEIGHT_DP).toInt()
            )
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) = Unit

                override fun onStartTrackingTouch(bar: SeekBar) {
                    userSeeking = true
                }

                override fun onStopTrackingTouch(bar: SeekBar) {
                    val duration = runtime.value.durationMs
                    if (duration > 0) {
                        val target = duration * bar.progress / bar.max
                        lifecycleScope.launch { playback.seekTo(target) }
                    }
                    userSeeking = false
                }
            })
        }

        val time = TextView(uiContext).apply {
            text = "00:00 / 00:00"
            setTextColor(Color.argb(0xCC, 0xFF, 0xFF, 0xFF))
            textSize = 11f
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val monitorRowLayout = LinearLayout(uiContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8f).toInt() }
            visibility = View.GONE
        }

        val monitorLabel = TextView(uiContext).apply {
            text = "本地监听"
            setTextColor(Color.WHITE)
            textSize = 12f
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
                gravity = Gravity.CENTER_VERTICAL
            }
        }

        val monitorSw = SwitchCompat(uiContext).apply {
            isChecked = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isClickable = true
            setOnCheckedChangeListener { _, checked ->
                if (monitorSwitchUpdating) return@setOnCheckedChangeListener
                lifecycleScope.launch {
                    playback.setLocalMonitorEnabled(checked)
                    localMonitorPreferred = checked
                    val msg = if (checked) "已开启本地监听" else "已关闭本地监听"
                    Toast.makeText(this@FloatingWindowService, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }

        monitorRowLayout.addView(monitorLabel)
        monitorRowLayout.addView(monitorSw)

        val mixRowLayout = LinearLayout(uiContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8f).toInt() }
            visibility = View.GONE
        }

        val mixLabel = TextView(uiContext).apply {
            text = getString(R.string.float_mix_active)
            setTextColor(Color.WHITE)
            textSize = 12f
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
                gravity = Gravity.CENTER_VERTICAL
            }
        }
        mixLabelView = mixLabel

        val mixSw = SwitchCompat(uiContext).apply {
            isChecked = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isClickable = true
            setOnCheckedChangeListener { _, checked ->
                if (mixSwitchUpdating) return@setOnCheckedChangeListener
                lifecycleScope.launch {
                    val ok = playback.setRealMicMixEnabled(checked)
                    if (!ok) {
                        mixSwitchUpdating = true
                        isChecked = false
                        mixSwitchUpdating = false
                        Toast.makeText(
                            this@FloatingWindowService,
                            "请先开启主开关并选择虚拟音源",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@launch
                    }
                    realMicMixPreferred = checked
                    val msg = if (checked) {
                        "已开启麦声闪避（播放虚拟音频时生效）"
                    } else {
                        "已关闭麦声闪避"
                    }
                    Toast.makeText(this@FloatingWindowService, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }

        mixRowLayout.addView(mixLabel)
        mixRowLayout.addView(mixSw)

        val container = LinearLayout(uiContext).apply {
            orientation = LinearLayout.VERTICAL
            background = idleBackground()
            isClickable = false
            isFocusable = false
            addView(topRow)
            addView(seek)
            addView(time)
            addView(monitorRowLayout)
            addView(mixRowLayout)
        }

        dragHandle = topRow
        layoutParams?.let { attachDragAndTapInteractions(topRow, it) }

        rootView = container
        rootView?.alpha = floatingOpacity
        textView = title
        playButton = button
        progressBar = seek
        timeView = time
        statusDot = dot
        sourceButton = srcBtn
        monitorSwitch = monitorSw
        mixSwitch = mixSw
        monitorRow = monitorRowLayout
        mixRow = mixRowLayout
    }

    private fun pickerBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(12f)
        setColor(Color.argb(0xF0, 0x1C, 0x1C, 0x20))
        setStroke(dp(1f).toInt(), Color.argb(0x44, 0xFF, 0xFF, 0xFF))
    }

    private fun showSourcePicker(anchor: View, clips: List<AudioClipEntity>) {
        sourcePickerPopup?.dismiss()
        val ctx = panelContext ?: return

        val names = clips.map { it.displayName }
        val listView = ListView(ctx).apply {
            adapter = object : ArrayAdapter<String>(ctx, android.R.layout.simple_list_item_1, names) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    return super.getView(position, convertView, parent).apply {
                        findViewById<TextView>(android.R.id.text1)?.apply {
                            setTextColor(Color.WHITE)
                            textSize = 14f
                        }
                        minimumHeight = dp(44f).toInt()
                        setBackgroundColor(Color.TRANSPARENT)
                    }
                }
            }
            divider = null
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setOnItemClickListener { _, _, position, _ ->
                val clip = clips[position]
                lifecycleScope.launch {
                    val ok = playback.setCurrentClip(clip.id, startPaused = false)
                    if (!ok) {
                        Toast.makeText(this@FloatingWindowService, "切换失败", Toast.LENGTH_SHORT).show()
                    }
                }
                sourcePickerPopup?.dismiss()
            }
        }

        val itemHeight = dp(44f).toInt()
        val maxHeight = (resources.displayMetrics.heightPixels * 0.45f).toInt()
        val popupHeight = (clips.size * itemHeight).coerceIn(itemHeight, maxHeight)
        val popupWidth = dp(FloatingWindowConstants.SOURCE_PICKER_WIDTH_DP).toInt()

        val popup = PopupWindow(listView, popupWidth, popupHeight, true).apply {
            isOutsideTouchable = true
            isFocusable = true
            isClippingEnabled = true
            elevation = dp(8f)
            setBackgroundDrawable(pickerBackground())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setWindowLayoutType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            }
            setOnDismissListener { sourcePickerPopup = null }
        }

        sourcePickerPopup = popup
        popup.showAsDropDown(anchor, 0, dp(4f).toInt())
    }

    private fun attachDragAndTapInteractions(handle: View, params: WindowManager.LayoutParams) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var touchStartX = 0f
        var touchStartY = 0f
        var dragging = false
        var downTime = 0L
        var touchOnButton = false

        handle.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchOnButton = findTopRowButton(v, ev.x, ev.y) != null
                    if (touchOnButton) {
                        return@setOnTouchListener false
                    }
                    initialX = params.x
                    initialY = params.y
                    touchStartX = ev.rawX
                    touchStartY = ev.rawY
                    dragging = false
                    downTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (touchOnButton) return@setOnTouchListener false
                    val dx = ev.rawX - touchStartX
                    val dy = ev.rawY - touchStartY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        rootView?.alpha = floatingOpacity * 0.8f
                        rootView?.scaleX = 1.05f
                        rootView?.scaleY = 1.05f
                    }
                    if (dragging) {
                        params.x = (initialX + dx).toInt()
                        params.y = (initialY + dy).toInt()
                        val root = rootView ?: v
                        runCatching { windowManager?.updateViewLayout(root, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (touchOnButton) {
                        touchOnButton = false
                        return@setOnTouchListener false
                    }
                    if (!dragging && System.currentTimeMillis() - downTime < FloatingWindowConstants.CLICK_TIMEOUT_MS) {
                        setExpanded(!expanded)
                    } else if (dragging) {
                        resetDragVisuals()
                        snapToEdge(rootView ?: v, params)
                    }
                    dragging = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) resetDragVisuals()
                    dragging = false
                    touchOnButton = false
                    true
                }
                else -> false
            }
        }
    }

    /** 顶部栏内的可点击按钮（播放/音源），需自行处理触摸 */
    private fun findTopRowButton(topRow: View, x: Float, y: Float): View? {
        if (topRow !is android.view.ViewGroup) return null
        for (i in topRow.childCount - 1 downTo 0) {
            val child = topRow.getChildAt(i)
            if (child.visibility != View.VISIBLE || !child.isEnabled) continue
            val localX = x - child.left
            val localY = y - child.top
            if (localX < 0f || localX >= child.width || localY < 0f || localY >= child.height) continue
            if (child is TextView && child.isClickable) return child
        }
        return null
    }

    private fun resetDragVisuals() {
        rootView?.alpha = floatingOpacity
        rootView?.scaleX = 1f
        rootView?.scaleY = 1f
    }

    private fun snapToEdge(view: View, params: WindowManager.LayoutParams) {
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val viewWidth = view.width.takeIf { it > 0 } ?: dp(FloatingWindowConstants.WIDTH_STANDARD_DP).toInt()
        val viewHeight = view.height.takeIf { it > 0 } ?: dp(100f).toInt()

        val centerX = params.x + viewWidth / 2
        val centerY = params.y + viewHeight / 2

        val edgeMargin = dp(FloatingWindowConstants.EDGE_MARGIN_DP).toInt()

        // 左右吸附
        params.x = if (centerX < screenWidth / 2) {
            edgeMargin
        } else {
            screenWidth - viewWidth - edgeMargin
        }

        // 上下边界检查
        params.y = params.y.coerceIn(
            edgeMargin,
            screenHeight - viewHeight - edgeMargin
        )

        runCatching { windowManager?.updateViewLayout(view, params) }
        savePosition()
    }

    private fun savePosition() {
        layoutParams?.let { params ->
            lifecycleScope.launch {
                configStore.update { builder ->
                    builder.setFloatingWindow(
                        builder.floatingWindow.toBuilder()
                            .setPositionX(params.x)
                            .setPositionY(params.y)
                    )
                }
            }
        }
    }

    private fun applySize(size: FloatingSize) {
        val (width, padding) = sizeDimensions(size)
        rootView?.minimumWidth = dp(width).toInt()
        rootView?.setPadding(
            dp(padding).toInt(),
            dp(8f).toInt(),
            dp(padding).toInt(),
            dp(9f).toInt()
        )
    }

    private fun applyCollapsedLayout() {
        rootView?.minimumWidth = 0
        rootView?.setPadding(
            dp(10f).toInt(),
            dp(8f).toInt(),
            dp(10f).toInt(),
            dp(8f).toInt()
        )
    }

    private fun sizeDimensions(size: FloatingSize): Pair<Float, Float> = when (size) {
        FloatingSize.SMALL ->
            FloatingWindowConstants.WIDTH_SMALL_DP to FloatingWindowConstants.PADDING_SMALL_DP
        FloatingSize.STANDARD ->
            FloatingWindowConstants.WIDTH_STANDARD_DP to FloatingWindowConstants.PADDING_STANDARD_DP
        FloatingSize.LARGE ->
            FloatingWindowConstants.WIDTH_LARGE_DP to FloatingWindowConstants.PADDING_LARGE_DP
        else ->
            FloatingWindowConstants.WIDTH_STANDARD_DP to FloatingWindowConstants.PADDING_STANDARD_DP
    }

    private fun updateVisuals(activeFile: Boolean, paused: Boolean, positionMs: Long, durationMs: Long, rt: io.mo.glassmic.core.model.RuntimeState) {
        val root = rootView ?: return
        val title = textView ?: return
        val dot = statusDot ?: return
        val button = playButton ?: return
        val seek = progressBar ?: return
        val time = timeView ?: return

        title.text = when {
            !activeFile -> getString(R.string.app_name)
            paused -> getString(R.string.float_status_paused_real_mic)
            expanded -> getString(R.string.float_status_playing)
            else -> "${formatMs(positionMs)} / ${formatMs(durationMs)}"
        }

        button.text = if (paused) ">" else "||"
        button.isEnabled = activeFile
        button.alpha = if (activeFile) 1f else 0.45f
        button.background = buttonBackground(enabled = activeFile)

        seek.isEnabled = activeFile && durationMs > 0
        seek.alpha = if (seek.isEnabled) 1f else 0.45f
        if (!userSeeking) {
            seek.progress = if (durationMs > 0) {
                ((positionMs.coerceIn(0L, durationMs) * seek.max) / durationMs).toInt()
            } else {
                0
            }
        }

        time.text = if (activeFile) {
            "${formatMs(positionMs)} / ${formatMs(durationMs)}"
        } else {
            "00:00 / 00:00"
        }

        dot.background = dotBackground(activeFile && !paused)
        root.background = when {
            activeFile && expanded -> activeBackground(paused)
            activeFile -> capsuleBackground(paused)
            else -> idleBackground()
        }

        // Update additional controls
        val canSelectSource = rt.enabled && !rt.safeMode
        sourceButton?.let { btn ->
            val audioId = rt.currentAudioId
            if (audioId != lastDisplayedAudioId) {
                lastDisplayedAudioId = audioId
                currentClipNameJob?.cancel()
                currentClipNameJob = lifecycleScope.launch {
                    val name = if (audioId.isNullOrBlank()) null else playback.getClipDisplayName(audioId)
                    btn.text = name ?: "音源"
                }
            }
            btn.isEnabled = canSelectSource
            btn.alpha = if (canSelectSource) 1f else 0.45f
            btn.background = buttonBackground(enabled = canSelectSource)
        }

        mixLabelView?.text = if (realMicMixPreferred && paused && canSelectSource) {
            getString(R.string.float_mix_paused)
        } else {
            getString(R.string.float_mix_active)
        }

        mixSwitch?.let { sw ->
            val canMix = canSelectSource && rt.currentSourceType == SourceType.FILE
            val mixActive = canMix && !paused && !rt.realMicPassThrough
            if (lastMixSwitchChecked != realMicMixPreferred) {
                lastMixSwitchChecked = realMicMixPreferred
                mixSwitchUpdating = true
                sw.isChecked = realMicMixPreferred
                mixSwitchUpdating = false
            }
            sw.isEnabled = canMix
            sw.alpha = when {
                !canMix -> 0.45f
                mixActive -> 1f
                realMicMixPreferred -> 0.65f
                else -> 1f
            }
        }

        monitorSwitch?.let { sw ->
            val canMonitor = canSelectSource && rt.currentSourceType == SourceType.FILE
            if (lastMonitorSwitchChecked != localMonitorPreferred) {
                lastMonitorSwitchChecked = localMonitorPreferred
                monitorSwitchUpdating = true
                sw.isChecked = localMonitorPreferred
                monitorSwitchUpdating = false
            }
            sw.isEnabled = canMonitor
            sw.alpha = if (canMonitor) 1f else 0.45f
        }
    }

    private fun setExpanded(value: Boolean, updateWindow: Boolean = true) {
        if (expanded == value && updateWindow) return
        expanded = value

        val root = rootView ?: return
        val title = textView ?: return
        val button = playButton ?: return
        val seek = progressBar ?: return
        val time = timeView ?: return
        val srcBtn = sourceButton ?: return

        title.layoutParams = LinearLayout.LayoutParams(
            if (expanded) 0 else LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            weight = if (expanded) 1f else 0f
            gravity = Gravity.CENTER_VERTICAL
        }
        button.visibility = if (expanded) View.VISIBLE else View.GONE
        seek.visibility = if (expanded) View.VISIBLE else View.GONE
        time.visibility = if (expanded) View.VISIBLE else View.GONE
        monitorRow?.visibility = if (expanded) View.VISIBLE else View.GONE
        mixRow?.visibility = if (expanded) View.VISIBLE else View.GONE
        srcBtn.visibility = if (expanded) View.VISIBLE else View.GONE
        if (expanded) {
            applySize(currentSize)
        } else {
            applyCollapsedLayout()
        }

        val rt = runtime.value
        val activeFile = rt.currentSourceType == SourceType.FILE && rt.enabled && !rt.safeMode
        updateVisuals(activeFile, rt.paused, rt.positionMs, rt.durationMs, rt)

        if (updateWindow) {
            layoutParams?.let { params ->
                if (root.isAttachedToWindow) {
                    runCatching { windowManager?.updateViewLayout(root, params) }
                }
            }
        }
    }

    private fun activeBackground(paused: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(18f)
        setColor(Color.argb(0xEE, 0x1C, 0x1C, 0x20))
        setStroke(
            dp(1f).toInt(),
            if (paused) Color.argb(0x88, 0xFF, 0xCC, 0x33)
            else Color.argb(0x88, 0x34, 0xC7, 0x59)
        )
    }

    private fun idleBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(20f)
        setColor(Color.argb(0xCC, 0x1C, 0x1C, 0x20))
        setStroke(dp(1f).toInt(), Color.argb(0x40, 0xFF, 0xFF, 0xFF))
    }

    private fun capsuleBackground(paused: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(20f)
        setColor(Color.argb(0xEE, 0x1C, 0x1C, 0x20))
        setStroke(
            dp(1f).toInt(),
            if (paused) Color.argb(0x66, 0xFF, 0xCC, 0x33)
            else Color.argb(0x66, 0x34, 0xC7, 0x59)
        )
    }

    private fun dotBackground(active: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(
            if (active) Color.argb(0xFF, 0x34, 0xC7, 0x59)
            else Color.argb(0xFF, 0x9E, 0x9E, 0xA0)
        )
    }

    private fun buttonBackground(enabled: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(15f)
        setColor(
            if (enabled) Color.argb(0x44, 0xFF, 0xFF, 0xFF)
            else Color.argb(0x22, 0xFF, 0xFF, 0xFF)
        )
        setStroke(dp(1f).toInt(), Color.argb(0x44, 0xFF, 0xFF, 0xFF))
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun formatMs(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%02d:%02d".format(s / 60, s % 60)
    }

    companion object {
        fun start(ctx: Context): Boolean {
            if (!Settings.canDrawOverlays(ctx)) {
                Toast.makeText(ctx, "请授予悬浮窗权限", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:${ctx.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
                return false
            }
            ctx.startService(Intent(ctx, FloatingWindowService::class.java))
            return true
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, FloatingWindowService::class.java))
        }
    }
}
