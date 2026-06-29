package io.mo.glassmic.core.model

/**
 * 运行态——不持久化，仅在内存与跨进程查询。
 * 任何字段的变化都要经过 EffectiveSourceResolver。
 */
data class RuntimeState(
    val enabled: Boolean = false,
    val safeMode: Boolean = false,
    val currentSourceType: SourceType = SourceType.REAL_MIC,
    val currentGroupId: String? = null,
    val currentAudioId: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val paused: Boolean = false,
    /** 虚拟音源仍保留，但 hook 侧透传真实麦克风（悬浮窗「恢复真实麦克风」）。 */
    val realMicPassThrough: Boolean = false,
    /** 虚拟音频与真实麦同时输出，并对虚拟轨做闪避（悬浮窗「麦声闪避」）。 */
    val realMicMixWithDucking: Boolean = false,
    val playbackPolicy: PlaybackPolicy = PlaybackPolicy.LOOP,
    val floatingWindowVisible: Boolean = false,
    val lastError: String? = null
)

/**
 * 配置快照——给 Xposed 进程做 scope 匹配用，必须是 Serializable 且字段稳定。
 */
data class ConfigSnapshot(
    val globalSwitch: Boolean,
    val scopeMode: ScopeMode,
    val whitelist: Set<String>,
    val blacklist: Set<String>,
    val onboardingCompleted: Boolean
)
