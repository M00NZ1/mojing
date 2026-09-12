package com.mojing.app.domain.story

/** Incremental JSON string reader; only chapter content enters the bounded preview. */
class StoryStreamingPreviewParser {
    private data class Frame(val type: Char, val chapters: Boolean = false, val chapter: Boolean = false,
        var expectsKey: Boolean = type == '{', var key: String = "")
    private val frames = ArrayDeque<Frame>()
    private val preview = StringBuilder()
    private val keyText = StringBuilder()
    private var inString = false
    private var readingKey = false
    private var capture = false
    private var escaped = false
    private var unicodeDigits = 0
    private var unicodeValue = 0
    private var sections = 0
    var receivedChars = 0
        private set
    var hasInvalidEscape = false
        private set

    fun append(delta: String) {
        if (hasInvalidEscape) return
        for (c in delta) {
            if (inString) {
                if (unicodeDigits > 0) {
                    val digit = c.digitToIntOrNull(16)
                    if (digit == null) { hasInvalidEscape = true; break }
                    unicodeValue = unicodeValue * 16 + digit
                    if (--unicodeDigits == 0) accept(unicodeValue.toChar())
                } else if (escaped) {
                    escaped = false
                    when (c) {
                        'u' -> { unicodeDigits = 4; unicodeValue = 0 }
                        'n' -> accept('\n')
                        'r' -> accept('\r')
                        't' -> accept('\t')
                        'b' -> accept('\b')
                        'f' -> accept('\u000C')
                        '"', '\\', '/' -> accept(c)
                        else -> { hasInvalidEscape = true; break }
                    }
                } else when (c) {
                    '\\' -> escaped = true
                    '"' -> {
                        inString = false
                        if (readingKey) frames.lastOrNull()?.let { it.key = keyText.toString(); it.expectsKey = false }
                        else frames.lastOrNull()?.key = ""
                        capture = false
                    }
                    else -> accept(c)
                }
            } else when (c) {
                '"' -> {
                    inString = true
                    readingKey = frames.lastOrNull()?.let { it.type == '{' && it.expectsKey } == true
                    keyText.setLength(0)
                    capture = !readingKey && frames.lastOrNull()?.let { it.chapter && it.key in setOf("content", "narrative") } == true
                    if (capture && sections++ > 0) preview.append("\n\n")
                }
                '{', '[' -> {
                    val parent = frames.lastOrNull()
                    val chaptersValue = parent?.type == '{' && parent.key == "chapters"
                    frames.addLast(Frame(c, chapters = c == '[' && chaptersValue,
                        chapter = c == '{' && (chaptersValue || parent?.chapters == true)))
                }
                '}', ']' -> { if (frames.isNotEmpty()) frames.removeLast(); frames.lastOrNull()?.key = "" }
                ',' -> frames.lastOrNull()?.let { if (it.type == '{') { it.expectsKey = true; it.key = "" } }
            }
        }
        if (preview.length > MAX_PREVIEW_CHARS) preview.delete(0, preview.length - MAX_PREVIEW_CHARS)
    }

    private fun accept(c: Char) {
        if (readingKey && keyText.length < 128) keyText.append(c)
        if (capture) { preview.append(c); receivedChars++ }
    }

    fun previewText(): String = preview.toString()
    private companion object { const val MAX_PREVIEW_CHARS = 12_000 }
}
