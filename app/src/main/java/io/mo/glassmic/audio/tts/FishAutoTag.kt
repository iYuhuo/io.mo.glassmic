package io.mo.glassmic.audio.tts

/**
 * Fish Audio 自动标签：合成前让 LLM 给文本插入 Fish S2 内置的情绪 / 音效标签。
 *
 * 只允许 Fish 标签面板里的官方标签（[TONE_TAGS] + [EFFECT_TAGS]），
 * LLM 自造的其它 [xxx] 会被 [keepFishTags] 过滤掉，避免 Fish 把它当文字读出来。
 * 这里只负责提示词与清洗，网络请求由 [AiTtsSynthesizer] 发起。
 */
internal object FishAutoTag {

    /** 情绪语气标签（Fish 标签面板 Emotional Tone）。 */
    val TONE_TAGS = listOf(
        "angry", "sad", "embarrassed", "emphasis", "whispering", "soft", "breathy", "excited"
    )

    /** 音效标签（Fish 标签面板 Audio Effects）。群体笑声类不适合一对一语音，未列入。 */
    val EFFECT_TAGS = listOf(
        "laughing", "chuckling", "moaning", "clear throat", "sobbing", "crying loudly",
        "sighing", "panting", "groaning", "pause", "long pause"
    )

    private val allowed = (TONE_TAGS + EFFECT_TAGS).map { it.lowercase() }.toSet()
    private val tagRegex = Regex("\\[([^\\]\\n]{1,60})]")
    private val emojiRegex = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]+")
    private val actionRegex = Regex("\\*[^*\\n]{1,80}\\*")
    private val spacesRegex = Regex("[ \\t]{2,}")

    fun systemPrompt(humanize: Boolean): String = buildString {
        append(
            "You are a voice director preparing a script for an ultra-realistic expressive " +
                "text-to-speech model (Fish Audio S2). It understands delivery tags in square " +
                "brackets — each tag affects the speech that follows it.\n\n" +
                "Make the text sound like a real person talking casually — warm, natural, " +
                "human, never robotic.\n\n" +
                "ALLOWED TAGS (use ONLY these, spelled exactly like this, nothing else):\n"
        )
        append("- Emotional tone: ").append(TONE_TAGS.joinToString(", ") { "[$it]" }).append('\n')
        append("- Audio effects: ").append(EFFECT_TAGS.joinToString(", ") { "[$it]" }).append("\n\n")
        append(
            "RULES:\n" +
                "1. Keep the same language as the input text.\n" +
                "2. Start with ONE emotional tone tag that fits the overall mood.\n" +
                "3. Add tags where a real person would react — roughly one per sentence, never " +
                "two in a row. Use [pause] for natural beats and switch tone tags when the mood shifts.\n" +
                "4. Drop emojis, markdown and URLs. If an *asterisk action* is a sound " +
                "(e.g. *giggles*, *sighs*), turn it into the closest allowed tag.\n" +
                "5. Output ONLY the final tagged script — no quotes, no explanations.\n"
        )
        if (humanize) {
            append(
                "6. Humanize the wording: contractions, light natural fillers (mm, um, like) " +
                    "sparingly, trailing ... for hesitation. Keep the SAME meaning and roughly the " +
                    "same length — do NOT add new ideas or content.\n"
            )
        } else {
            append(
                "6. Keep the EXACT same words in the same order. Only add tags and punctuation " +
                    "(..., !, ?) — do NOT rephrase, add or remove words.\n"
            )
        }
    }

    /**
     * 去掉 Fish 会照读的内容：emoji、多余空白，[actions] 为 true 时连同 *动作* 一起去掉。
     * 未开自动标签时传 false：用户原文里的 *…* 可能是算式（2*3*4）等正文。
     */
    fun stripUnspeakable(text: String, actions: Boolean = true): String =
        (if (actions) text.replace(actionRegex, "") else text)
            .replace(emojiRegex, "").replace(spacesRegex, " ").trim()

    /**
     * 只保留 Fish 内置标签（统一写成小写 [tag]），其余 [xxx] 删除；
     * 落单的 [ 或 ] 也去掉——LLM 输出被截断时会留下半个括号，Fish 会照读甚至返回空音频。
     */
    fun keepFishTags(text: String): String {
        val out = StringBuilder()
        var last = 0
        for (m in tagRegex.findAll(text)) {
            out.append(text, last, m.range.first)
            val name = m.groupValues[1].trim().lowercase()
            if (name in allowed) out.append('\u0000').append(name).append('\u0001')
            last = m.range.last + 1
        }
        out.append(text, last, text.length)
        return out.toString()
            .replace("[", "").replace("]", "")
            .replace('\u0000', '[').replace('\u0001', ']')
            .replace(spacesRegex, " ").trim()
    }

    /** 文本里是否已经带有 Fish 内置标签（例如已优化过的文本被再次生成）。 */
    fun hasFishTags(text: String): Boolean =
        tagRegex.findAll(text).any { it.groupValues[1].trim().lowercase() in allowed }

    /** LLM 输出可能带换行 / 多余空白，Fish 会读坏，压成单行。 */
    fun singleLine(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    /** 去掉全部标签后是否还有可读内容（用来判断 LLM 输出是否可用）。 */
    fun hasSpeakableText(text: String): Boolean = tagRegex.replace(text, "").isNotBlank()
}
