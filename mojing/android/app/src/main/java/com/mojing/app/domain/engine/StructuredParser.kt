package com.mojing.app.domain.engine

data class StructuredReply(
    val narrations: List<String> = emptyList(),
    val thoughts: List<String> = emptyList(),
    val speeches: List<SpeechSegment> = emptyList(),
    val choices: List<String> = emptyList(),
    /** 未包含在结构标签里的正文；用于兼容“普通正文 + 尾部选项”的真实模型输出。 */
    val plainText: String = "",
)

data class SpeechSegment(
    val characterName: String = "",
    val text: String = ""
)

object StructuredParser {

    private val xmlOptions = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    private val narrationRegex = Regex("<NARRATION>(.*?)</NARRATION>", xmlOptions)
    private val thoughtRegex = Regex("<THOUGHT>(.*?)</THOUGHT>", xmlOptions)
    private val speechRegex = Regex("<SPEECH(?:\\s+name=\"([^\"]*)\")?>(.*?)</SPEECH>", xmlOptions)
    private val choicesRegex = Regex("<CHOICES\\b[^>]*>(.*?)</CHOICES>", xmlOptions)
    private val optionRegex = Regex("<OPTION\\b[^>]*>(.*?)</OPTION>", xmlOptions)
    private val danglingChoicesTagRegex = Regex("</?CHOICES(?:\\s+[^>]*)?>", xmlOptions)

    fun parse(rawText: String): StructuredReply {
        val normalized = OutputProcessor.normalizeOptions(rawText)
        val narrations = narrationRegex.findAll(normalized).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
        val thoughts = thoughtRegex.findAll(normalized).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
        val speeches = speechRegex.findAll(normalized).mapNotNull { match ->
            val t = match.groupValues[2].trim()
            if (t.isEmpty()) null else SpeechSegment(
                characterName = match.groupValues[1].ifEmpty { "" },
                text = t,
            )
        }.toList()
        val choices = optionRegex.findAll(normalized)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        val plainText = residualText(normalized)

        return StructuredReply(narrations, thoughts, speeches, choices, plainText)
    }

    fun isStructured(text: String): Boolean {
        val normalized = OutputProcessor.normalizeOptions(text)
        return narrationRegex.containsMatchIn(normalized) || thoughtRegex.containsMatchIn(normalized) ||
            speechRegex.containsMatchIn(normalized) || optionRegex.containsMatchIn(normalized)
    }

    fun stripTags(text: String): String {
        val normalized = OutputProcessor.normalizeOptions(text)
        return normalized.replace(choicesRegex, "")
            .replace(optionRegex, "")
            .replace(danglingChoicesTagRegex, "")
            .replace(narrationRegex) { "\n" + it.groupValues[1] + "\n" }
            .replace(thoughtRegex, "")
            .replace(speechRegex) { "\n" + it.groupValues[2] + "\n" }
            .cleanSpacing()
    }

    private fun residualText(text: String): String = text
        .replace(choicesRegex, "")
        .replace(optionRegex, "")
        .replace(danglingChoicesTagRegex, "")
        .replace(narrationRegex, "")
        .replace(thoughtRegex, "")
        .replace(speechRegex, "")
        .cleanSpacing()

    private fun String.cleanSpacing(): String = trim()
        .replace(Regex("[ \\t]+\\n"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
}
