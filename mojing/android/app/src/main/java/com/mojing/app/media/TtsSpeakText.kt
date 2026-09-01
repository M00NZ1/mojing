package com.mojing.app.media

/**
 * 将聊天正文整理为适合 TTS 的纯文本：去掉常见表情区段、角标/标签、部分 Markdown，减少「读符号」。
 */
object TtsSpeakText {

    private val angleTag = Regex("<[^>]{0,500}>", RegexOption.DOT_MATCHES_ALL)
    private val mdLink = Regex("\\[([^\\]]+)]\\([^)]+\\)")
    private val mdImage = Regex("!\\[[^\\]]*]\\([^)]+\\)")
    private val mdHeading = Regex("^#{1,6}\\s+", RegexOption.MULTILINE)
    private val fence = Regex("```[\\s\\S]*?```")
    private val inlineCode = Regex("`+([^`]+)`+")

    fun normalizeForSpeech(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return ""
        s = s.replace("\r\n", "\n")
        s = fence.replace(s, " ")
        s = angleTag.replace(s, " ")
        s = mdImage.replace(s, " ")
        s = mdLink.replace(s, "$1")
        s = mdHeading.replace(s, "")
        s = inlineCode.replace(s, "$1")
        s = s.replace("**", "").replace("__", "").replace("~~", "")
        s = s.replace("*", "").replace("_", "")
        s = stripEmojiAndSymbols(s)
        s = s.replace("[图片]", " ").replace("🖼", " ")
        s = s.replace(Regex("\\s+"), " ").trim()
        return s
    }

    private fun stripEmojiAndSymbols(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val charCount = Character.charCount(cp)
            if (shouldDropForSpeech(cp)) {
                i += charCount
                continue
            }
            sb.appendCodePoint(cp)
            i += charCount
        }
        return sb.toString()
    }

    private fun shouldDropForSpeech(cp: Int): Boolean {
        if (cp in 0xFE00..0xFE0F) return true
        if (cp == 0x200D || cp == 0x200C) return true
        if (cp in 0xD800..0xDFFF) return true
        val cat = Character.getType(cp)
        if (cat == Character.SURROGATE.toInt() || cat == Character.PRIVATE_USE.toInt()) return true
        if (Character.isISOControl(cp) && cp != '\n'.code && cp != '\t'.code) return true
        if (cp in 0x1F300..0x1FAFF) return true
        if (cp in 0x2600..0x27BF) return true
        val block = Character.UnicodeBlock.of(cp)
        return when (block) {
            Character.UnicodeBlock.EMOTICONS,
            Character.UnicodeBlock.DINGBATS,
            Character.UnicodeBlock.TRANSPORT_AND_MAP_SYMBOLS,
            Character.UnicodeBlock.MISCELLANEOUS_TECHNICAL,
            Character.UnicodeBlock.VARIATION_SELECTORS,
            Character.UnicodeBlock.VARIATION_SELECTORS_SUPPLEMENT,
            Character.UnicodeBlock.MUSICAL_SYMBOLS,
            Character.UnicodeBlock.MAHJONG_TILES,
            Character.UnicodeBlock.DOMINO_TILES,
            Character.UnicodeBlock.PLAYING_CARDS,
            Character.UnicodeBlock.YIJING_HEXAGRAM_SYMBOLS,
            Character.UnicodeBlock.TAI_XUAN_JING_SYMBOLS,
            -> true
            else -> false
        }
    }
}
