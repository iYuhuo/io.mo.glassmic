#pragma once

#include <cstdint>

namespace glass {

/** 决策枚举——和 Kotlin 侧 SourceType 一致。 */
enum class Decision : int32_t {
    REAL_MIC = 0,
    FILE     = 1,
    SILENCE  = 2
};

/** 目标缓冲的 PCM 采样格式（OpenSL ES / native AudioRecord 路径用）。 */
enum class SampleFmt : int32_t {
    S16   = 0,
    FLOAT = 1,
};

/** 在当前进程内安装 AAudio / OpenSL / native AudioRecord hook。线程安全，重复调用安全。 */
bool install_aaudio_hook();

/**
 * 把虚拟音源按目标 PCM 格式写进 buffer（覆盖模式）。
 * 返回 true 表示已用虚拟数据覆盖 buffer；false 表示应放行真实麦克风。
 */
bool fill_pcm(void* buffer, SampleFmt fmt, int32_t channels, int32_t sample_rate, int32_t frames);

/**
 * 真实麦 + 虚拟音源混音闪避（buffer 里已有真实麦克风数据）。
 * 与 Kotlin [RealMicMixer] 算法一致。返回 true 表示已混音。
 */
bool mix_virtual_into(void* buffer, SampleFmt fmt, int32_t channels, int32_t sample_rate, int32_t frames);

/** 由 Kotlin 侧轮询线程定期推送当前决策。原子写。 */
void set_decision(Decision d);
Decision get_decision();

/** 真实麦 + 虚拟音频混音闪避开关。 */
void set_mix_enabled(bool enabled);
bool is_mix_enabled();

/**
 * 设置当前可读取的 PCM pipe fd（由 Kotlin 通过 ContentResolver 打开后传下来）。
 * 传 -1 表示当前没有可用 fd。设置新 fd 会自动 close 旧 fd。
 */
void set_pcm_fd(int fd, int32_t sample_rate, int32_t channels);

/**
 * 拉出累计的劫持统计（reads, bytes），并清零。
 * 由 Kotlin 侧轮询线程调用，再上报给 ContentProvider。
 */
void drain_stats(uint64_t* out_reads, uint64_t* out_bytes,
                 int32_t* out_last_sr, int32_t* out_last_ch);

} // namespace glass
