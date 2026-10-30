package io.mo.glassmic.audio.tts

/**
 * 用户自定义的 Fish Audio 音色预设（设置页「音色预设」，逗号分隔的 `名称:声音ID`）。
 * 悬浮窗 TTS 面板据此显示音色切换按钮；选中项写回「音色」字段。默认为空。
 */
object FishVoices {

    /** "名称:ID, 名称:ID" → [(名称, ID)]。缺名称时用 ID 本身作名称，空项与重复 ID 忽略。 */
    fun parse(raw: String): List<Pair<String, String>> =
        raw.split(',', '，', '\n')
            .mapNotNull { entry ->
                val item = entry.trim()
                if (item.isEmpty()) return@mapNotNull null
                val sep = item.lastIndexOfAny(charArrayOf(':', '：'))
                val name = if (sep > 0) item.substring(0, sep).trim() else ""
                val id = (if (sep >= 0) item.substring(sep + 1) else item).trim()
                if (id.isEmpty()) null else name.ifEmpty { id } to id
            }
            .distinctBy { it.second }
}
