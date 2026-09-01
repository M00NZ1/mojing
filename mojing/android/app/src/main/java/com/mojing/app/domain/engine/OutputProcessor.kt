package com.mojing.app.domain.engine

object OutputProcessor {
    private val xmlOptions = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    private val completeOptionRegex = Regex("<OPTION\\b[^>]*>.*?</OPTION>", xmlOptions)
    private val choicesBlockRegex = Regex("<CHOICES\\b[^>]*>(.*?)</CHOICES>", xmlOptions)
    private val choiceHeaderRegex = Regex(
        "^(?:#{1,6}\\s*)?(?:\\*\\*|__)?\\s*" +
            "(?:可选行动|行动选项|后续选项|选项|下一步(?:行动)?|可供选择(?:的行动)?|你可以选择|choices?|options?)" +
            "\\s*(?:（[^）\\r\\n]{0,16}）|\\([^\\)\\r\\n]{0,16}\\))?\\s*[:：]?\\s*(?:\\*\\*|__)?$",
        RegexOption.IGNORE_CASE,
    )
    private val numberedChoiceRegex = Regex(
        "^(?:" +
            "\\(?\\d{1,2}\\)?[\\.)）、：:]" +
            "|\\(\\d{1,2}\\)" +
            "|（\\d{1,2}）" +
            "|\\([一二三四五六七八]\\)" +
            "|（[一二三四五六七八]）" +
            "|[一二三四五六七八][、.)）]" +
            "|[A-Ha-h][\\.)、：:]" +
            "|[-*•·]" +
            ")\\s*(.+)$",
    )
    private val labelledChoiceRegex = Regex(
        "^(?:选项|选择)\\s*(?:\\d{1,2}|[一二三四五六七八])\\s*[-\\.)）、：:]\\s*(.+)$",
    )

    fun process(rawText: String): String {
        var processed = rawText

        processed = processed.replace(Regex("\\b(?i)as an AI\\b"), "")
        processed = processed.replace(Regex("\\b(?i)as a language model\\b"), "")
        processed = processed.replace(Regex("\\b(?i)I cannot\\b"), "我不便")

        processed = processed.replace(
            Regex("(?<![a-zA-Z])I am an (AI|artificial intelligence)(?![a-zA-Z])"),
            "我是"
        )

        return processed.trim()
    }

    fun normalizeOptions(text: String): String {
        var normalized = text.trim()
        if (normalized.isEmpty()) return ""

        normalized = choicesBlockRegex.replace(normalized) { match ->
            if (completeOptionRegex.containsMatchIn(match.value)) return@replace match.value
            val choices = extractChoiceLines(match.groupValues[1], insideExplicitBlock = true)
            if (choices.isEmpty()) match.value else canonicalChoicesBlock(choices)
        }
        if (completeOptionRegex.containsMatchIn(normalized)) return normalized

        val lines = normalized.lines()
        normalizeExplicitChoiceSection(lines)?.let { return it }

        val picked = mutableListOf<String>()
        var i = lines.lastIndex

        while (i >= 0) {
            val line = lines[i].trim()
            if (line.isBlank()) {
                i--
                continue
            }
            val choice = cleanChoiceLine(line)
            if (choice != null) {
                picked.add(choice)
                i--
                continue
            }
            if (picked.isNotEmpty() && isChoiceHeader(line)) {
                i--
            }
            break
        }

        if (picked.size < 2) return normalized

        val body = lines.take(i + 1).joinToString("\n").trimEnd()
        val choices = picked.asReversed().distinct().take(8)
        return listOf(body, canonicalChoicesBlock(choices)).filter(String::isNotBlank).joinToString("\n").trim()
    }

    fun extractChoices(text: String): List<String> {
        return completeOptionRegex.findAll(normalizeOptions(text))
            .map { it.value.replace(Regex("^<OPTION\\b[^>]*>|</OPTION>$", xmlOptions), "").trim() }
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
    }

    private fun extractChoiceLines(text: String, insideExplicitBlock: Boolean): List<String> {
        return text.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .filterNot(::isChoiceHeader)
            .mapNotNull { line -> cleanChoiceLine(line) ?: line.takeIf { insideExplicitBlock }?.let(::trimMarkdown) }
            .filter(String::isNotEmpty)
            .distinct()
            .take(8)
            .toList()
    }

    /**
     * 明确标题后的选项不必位于回复绝对末尾。模型偶尔会在列表后补一句“请选择”，
     * 旧的尾部扫描会因此把整组行动当成正文。标题提供了足够强的边界，因此单项也可恢复；
     * 没有标题的普通剧情列表仍由后面的保守尾部规则处理，至少需要两项且必须位于末尾。
     */
    private fun normalizeExplicitChoiceSection(lines: List<String>): String? {
        for (headerIndex in lines.indices.reversed()) {
            if (!isChoiceHeader(lines[headerIndex])) continue

            val consumed = mutableSetOf(headerIndex)
            val choices = mutableListOf<String>()
            var sawChoice = false
            for (index in (headerIndex + 1)..lines.lastIndex) {
                val line = lines[index].trim()
                if (line.isEmpty() || line.matches(Regex("^```[A-Za-z0-9_-]*$"))) {
                    consumed += index
                    continue
                }
                val choice = cleanChoiceLine(line)
                if (choice != null) {
                    choices += choice
                    consumed += index
                    sawChoice = true
                    continue
                }
                if (!sawChoice) break
                // 列表后的解释或提问仍属于正文，不阻止已识别的选项成为可点击入口。
                break
            }
            val unique = choices.distinct().take(8)
            if (unique.isEmpty()) continue

            val body = lines.filterIndexed { index, _ -> index !in consumed }
                .joinToString("\n")
                .trim()
                .replace(Regex("\n{3,}"), "\n\n")
            return listOf(body, canonicalChoicesBlock(unique))
                .filter(String::isNotBlank)
                .joinToString("\n")
                .trim()
        }
        return null
    }

    private fun cleanChoiceLine(raw: String): String? {
        val line = trimMarkdown(raw)
        if (line.isEmpty()) return null
        val match = labelledChoiceRegex.matchEntire(line) ?: numberedChoiceRegex.matchEntire(line)
        return match?.groupValues?.get(1)?.let(::trimMarkdown)?.takeIf(String::isNotEmpty)
    }

    private fun isChoiceHeader(raw: String): Boolean = choiceHeaderRegex.matches(raw.trim())

    private fun trimMarkdown(raw: String): String {
        var value = raw.trim()
        while (value.startsWith(">")) value = value.removePrefix(">").trimStart()
        if ((value.startsWith("**") && value.endsWith("**")) ||
            (value.startsWith("__") && value.endsWith("__"))
        ) {
            value = value.substring(2, value.length - 2).trim()
        }
        return value
    }

    private fun canonicalChoicesBlock(choices: List<String>): String =
        choices.distinct().take(8).joinToString("", "<CHOICES>", "</CHOICES>") { "<OPTION>$it</OPTION>" }
}
