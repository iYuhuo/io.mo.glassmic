package io.mo.glassmic.service

object FloatingWindowConstants {
    // 未保存位置时的占位p(roto 默认 -1）
    const val POSITION_UNSET = -1

    // 默认位置
    const val DEFAULT_X = 24
    const val DEFAULT_Y = 300

    // 不透明度
    const val DEFAULT_OPACITY = 0.85f
    const val MIN_OPACITY = 0.2f
    const val MAX_OPACITY = 1.0f

    // 交互参数
    const val CLICK_TIMEOUT_MS = 400L
    const val EDGE_MARGIN_DP = 8f

    // 尺寸配置 (dp)
    const val WIDTH_SMALL_DP = 200f
    const val WIDTH_STANDARD_DP = 240f
    const val WIDTH_LARGE_DP = 280f

    const val PADDING_SMALL_DP = 12f
    const val PADDING_STANDARD_DP = 14f
    const val PADDING_LARGE_DP = 16f

    const val SOURCE_PICKER_WIDTH_DP = 260f

    // UI元素尺寸 (dp)
    const val STATUS_DOT_SIZE_DP = 8f
    const val BUTTON_SIZE_DP = 30f
    const val SEEK_BAR_HEIGHT_DP = 32f

    // 节流时间 (ms)
    const val UI_UPDATE_THROTTLE_MS = 100L
}
