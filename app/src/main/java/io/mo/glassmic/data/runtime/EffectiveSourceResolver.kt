package io.mo.glassmic.data.runtime

import io.mo.glassmic.core.Constants
import io.mo.glassmic.core.model.ConfigSnapshot
import io.mo.glassmic.core.model.SourceType
import io.mo.glassmic.core.util.ScopeMatcher
import io.mo.glassmic.data.config.ConfigStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §11 状态优先级唯一权威实现。
 *
 * 优先级：
 *   安全模式 > 重启后默认关闭 > 首次启动门禁 > 全局总开关 > 生效范围 > 当前音源
 *
 * 任何调用方都不允许自行拼装这一逻辑——必须走这里。
 */
@Singleton
class EffectiveSourceResolver @Inject constructor(
    private val safeMode: SafeModeRepository,
    private val bootGate: BootGateRepository,
    private val configStore: ConfigStore,
    private val runtime: RuntimeStateHolder
) {

    /**
     * 返回 [callerPackage] 当前命中后实际应使用的音源类型。
     *
     * @return REAL_MIC 表示不拦截原始麦克风
     */
    fun resolve(callerPackage: String): SourceType = explain(callerPackage).source

    /**
     * 带原因说明的决策——供 Xposed 诊断「为何是 REAL_MIC」。
     */
    fun explain(callerPackage: String): ResolveExplanation {
        if (safeMode.isActive()) {
            return ResolveExplanation(SourceType.REAL_MIC, "safe_mode")
        }
        if (!bootGate.userEnabledAfterBoot()) {
            return ResolveExplanation(SourceType.REAL_MIC, "boot_gate")
        }
        val snap = configStore.snapshotBlocking()
        if (!snap.onboardingCompleted) {
            return ResolveExplanation(SourceType.REAL_MIC, "onboarding")
        }
        if (!ScopeMatcher.matches(callerPackage, snap)) {
            val reason = when (snap.scopeMode) {
                io.mo.glassmic.core.model.ScopeMode.WHITELIST -> "scope_whitelist"
                io.mo.glassmic.core.model.ScopeMode.BLACKLIST -> "scope_blacklist"
                else -> if (!snap.globalSwitch) "global_switch_off" else "scope"
            }
            return ResolveExplanation(SourceType.REAL_MIC, reason)
        }
        if (callerPackage == Constants.APP_PACKAGE) {
            return ResolveExplanation(SourceType.REAL_MIC, "self")
        }
        val rt = runtime.value
        if (!rt.enabled) {
            return ResolveExplanation(SourceType.REAL_MIC, "service_off")
        }
        if (rt.realMicPassThrough) {
            return ResolveExplanation(SourceType.REAL_MIC, "real_mic_passthrough")
        }
        // 暂停时 hook 透传真实麦
        if (rt.paused) {
            return ResolveExplanation(SourceType.REAL_MIC, "paused")
        }
        // TTS 与 FILE 一样都是"从 PCM 管线注入"，对 Xposed 侧统一按 FILE 处理
        val source = when (rt.currentSourceType) {
            SourceType.TTS -> SourceType.FILE
            else -> rt.currentSourceType
        }
        return ResolveExplanation(source, "runtime_${rt.currentSourceType.name.lowercase()}")
    }

    /** 给 UI / 通知/ 悬浮窗显示用——和 resolve() 不同，这里返回是否模块整体在运行 */
    fun isModuleActiveForUi(): Boolean {
        if (safeMode.isActive()) return false
        if (!bootGate.userEnabledAfterBoot()) return false
        return runtime.value.enabled
    }

    fun configSnapshot(): ConfigSnapshot = configStore.snapshotBlocking()
}

data class ResolveExplanation(
    val source: SourceType,
    val reason: String
)
