package com.mojing.app.domain.engine

/** Keep received prose while excluding unfinished protocol and media instructions. */
object InterruptedReply {
    private val options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    private val media = Regex("<GEN_(?:IMAGE|SPEECH)\\b[^>]*>.*?(?:</GEN_(?:IMAGE|SPEECH)>|$)", options)
    private val incompleteChoices = Regex("<(CHOICES|OPTION)\\b[^>]*>(?:(?!</\\1>).)*$", options)
    private val displayTags = Regex("</?(?:NARRATION|THOUGHT|SPEECH)\\b[^>]*>", options)
    private val danglingTag = Regex("</?[A-Za-z_]+(?:\\s[^>]*)?$", options)

    fun normalize(raw: String): String {
        val prose = raw.replace(media, "")
            .replace(incompleteChoices, "")
            .replace(displayTags, "")
            .replace(danglingTag, "")
        return OutputProcessor.normalizeOptions(prose).trim()
    }
}
