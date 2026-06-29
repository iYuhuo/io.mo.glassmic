#include "glass_audiorecord.h"
#include "glass_aaudio.h"   // glass::fill_pcm / SampleFmt / get_decision / Decision
#include "glass_log.h"

#include <shadowhook.h>

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <sys/types.h>

namespace glass {

// ssize_t android::AudioRecord::read(void* buffer, size_t size, bool blocking)
using AudioRecord_read_t = ssize_t (*)(void* /*this*/, void*, size_t, bool);
static AudioRecord_read_t g_orig_read = nullptr;
static void* g_read_stub = nullptr;
static std::atomic<bool> g_dumped{false};

static const int32_t kCommonRates[] = {
    8000, 11025, 12000, 16000, 22050, 24000, 32000,
    44100, 48000, 64000, 88200, 96000, 176400, 192000
};

static bool is_common_rate(uint32_t v) {
    for (int32_t r : kCommonRates) {
        if (v == static_cast<uint32_t>(r)) return true;
    }
    return false;
}

/**
 * AudioRecord 的 getSampleRate()/channelCount()/format() 在该平台是 inline、未导出，
 * hook read 时拿不到。改为按对象内存布局直接读：
 *
 *   实测 Android 16（libaudioclient）AudioRecord 成员布局：
 *     [sr_off + 0] uint32  mSampleRate    （落在常见采样率集合内，用于定位）
 *     [sr_off + 4] uint32  mFormat        （audio_format_t：PCM_16=1, PCM_FLOAT=5 …）
 *     [sr_off + 8] uint32  mChannelCount
 *   再往后是 mAttributes（含字符串 tags[]）。
 *
 * 先扫出 mSampleRate 的偏移（值唯一可判别），再按 +4/+8 取 format / channelCount，
 * 并做范围校验。校验失败时回退到安全默认。
 *
 * 仅 FILE 模式需要精确格式；SILENCE 模式按字节清零，与此无关。
 */
static void detect_format(
    const void* thiz,
    int32_t* out_sr, int32_t* out_ch, SampleFmt* out_fmt, int* out_sr_off
) {
    int32_t sr = 0, ch = 0;
    SampleFmt fmt = SampleFmt::S16;
    int sr_off = -1;
    const auto* base = reinterpret_cast<const uint8_t*>(thiz);
    constexpr int kScan = 768;

    for (int off = 0; off + 12 <= kScan; off += 4) {
        uint32_t v;
        __builtin_memcpy(&v, base + off, sizeof(v));
        if (is_common_rate(v)) {
            sr = static_cast<int32_t>(v);
            sr_off = off;
            uint32_t fmt_field = 0, ch_field = 0;
            __builtin_memcpy(&fmt_field, base + off + 4, sizeof(fmt_field));
            __builtin_memcpy(&ch_field, base + off + 8, sizeof(ch_field));
            if (ch_field >= 1 && ch_field <= 8) ch = static_cast<int32_t>(ch_field);
            if (fmt_field == 5 /*AUDIO_FORMAT_PCM_FLOAT*/) fmt = SampleFmt::FLOAT;
            break;
        }
    }

    *out_sr = sr > 0 ? sr : 48'000;
    *out_ch = ch;  // 0 表示未取到，调用方据 read 字节数兜底
    *out_fmt = fmt;
    if (out_sr_off) *out_sr_off = sr_off;
}

static ssize_t my_read(void* thiz, void* buffer, size_t size, bool blocking) {
    if (!buffer || !thiz || size == 0) {
        return g_orig_read(thiz, buffer, size, blocking);
    }

    const Decision decision = get_decision();
    if (decision == Decision::REAL_MIC) {
        return g_orig_read(thiz, buffer, size, blocking);
    }

    int32_t sr = 48'000, ch = 0;
    SampleFmt fmt = SampleFmt::S16;
    int sr_off = -1;
    detect_format(thiz, &sr, &ch, &fmt, &sr_off);

    int32_t bps = (fmt == SampleFmt::FLOAT) ? 4 : 2;
    if (ch <= 0) {
        ch = (size % (bps * 2) == 0) ? 2 : 1;
    }

    if (!g_dumped.exchange(true)) {
        LOGI("AudioRecord::read first hit: size=%zu sr=%d@%d ch=%d fmt=%d decision=%d",
             size, sr, sr_off, ch, static_cast<int>(fmt), static_cast<int>(decision));
    }

    int32_t frame_bytes = ch * bps;
    int32_t frames = static_cast<int32_t>(size) / frame_bytes;
    if (frames <= 0) {
        return g_orig_read(thiz, buffer, size, blocking);
    }

    if (decision == Decision::SILENCE) {
        std::memset(buffer, 0, size);
        return static_cast<ssize_t>(size);
    }

    if (decision == Decision::FILE && is_mix_enabled()) {
        ssize_t n = g_orig_read(thiz, buffer, size, blocking);
        if (n <= 0) return n;
        int32_t mix_frames = static_cast<int32_t>(n) / frame_bytes;
        if (mix_frames > 0) {
            mix_virtual_into(buffer, fmt, ch, sr, mix_frames);
        }
        return n;
    }

    // FILE 替麦：不先读真实麦，直接填虚拟轨；未就绪则静音
    if (fill_pcm(buffer, fmt, ch, sr, frames)) {
        return static_cast<ssize_t>(size);
    }
    std::memset(buffer, 0, size);
    return static_cast<ssize_t>(size);
}

static bool hook_read_in_lib(const char* lib_name) {
    if (g_orig_read != nullptr) return true;

    void* stub = shadowhook_hook_sym_name(
        lib_name,
        "_ZN7android11AudioRecord4readEPvmb",
        reinterpret_cast<void*>(my_read),
        reinterpret_cast<void**>(&g_orig_read)
    );
    if (!stub) {
        int err = shadowhook_get_errno();
        const char* msg = shadowhook_to_errmsg(err);
        LOGW("hook AudioRecord::read in %s failed: err=%d %s", lib_name, err, msg ? msg : "?");
        return false;
    }

    g_read_stub = stub;
    LOGI("AudioRecord::read hook installed via %s", lib_name);
    return true;
}

bool is_audiorecord_hooked() {
    return g_orig_read != nullptr;
}

bool install_audiorecord_hook() {
    if (g_orig_read != nullptr) return true;

    if (hook_read_in_lib("libaudioclient.so")) {
        return true;
    }
    if (hook_read_in_lib("libmedia.so")) {
        return true;
    }
    return false;
}

} // namespace glass
