package com.mojing.app.media

/** Splits speech input into UTF-16 safe chunks accepted by Android TTS. */
object SpeechChunks {
    const val DEFAULT_MAX_LENGTH = 3_500

    fun split(text: String, maxLength: Int = DEFAULT_MAX_LENGTH): List<String> {
        require(maxLength > 0) { "maxLength must be positive" }
        if (text.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            while (start < text.length && text[start].isWhitespace()) start++
            if (start >= text.length) break

            var end = start
            var utf16Length = 0
            while (end < text.length) {
                val codePointLength = Character.charCount(text.codePointAt(end))
                if (utf16Length + codePointLength > maxLength) break
                end += codePointLength
                utf16Length += codePointLength
            }
            // A single code point can technically exceed a tiny test limit. Keep it intact.
            if (end == start) end += Character.charCount(text.codePointAt(start))
            if (end < text.length) {
                val preferred = (start + ((end - start) * 0.82).toInt())
                var boundary = end
                for (i in end - 1 downTo preferred.coerceAtLeast(start + 1)) {
                    if (text[i - 1] in "。！？!?；;，,\n") {
                        boundary = i
                        break
                    }
                }
                end = boundary
            }
            text.substring(start, end).trim().takeIf { it.isNotEmpty() }?.let(result::add)
            start = end
        }
        return result
    }
}
