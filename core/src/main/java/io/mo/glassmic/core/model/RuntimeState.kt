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
    /** 虚拟音源仍保留，但 hook 侧透传真实麦克风（与 paused 等效，保留供内部状态）。 */
    val realMicPassThrough: Boolean = false,
    val playbackPolicy: PlaybackPolicy = PlaybackPolicy.LOOP,
    val floatingWindowVisible: Boolean = false,
    val lastError: String? = null,
    /**
     * 每次用户点「解除麦克风占用」递增。Xposed 侧在 resolve 里读到更大值时，
     * 对本进程已跟踪的 AudioRecord / AAudio / OpenSL 录音会话执行 stop/release。
     */
    val forceReleaseToken: Long = 0L,
    /**
     * 墙钟时间：在此之前，作用域内 App 的 startRecording / MediaRecorder.start 会被拒绝，
     * 并持续 stop/release，防止掐断后立刻重连占麦。
     */
    val recordingBlockUntilMs: Long = 0L,
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
