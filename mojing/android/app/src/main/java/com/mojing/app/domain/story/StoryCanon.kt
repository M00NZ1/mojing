package com.mojing.app.domain.story

/** 小说创作的统一设定约束与轻量输出守卫。 */
object StoryCanon {
    val promptRules: String = """
        【设定一致性（最高优先级）】
        1. 用户写下的背景、否定描述和知情范围都是不可自行改写的事实；不得为了制造冲突擅自增加力量体系、超自然现象或隐藏组织。
        2. 严格区分“作者知道”“主角知道”和“其他人物知道”。秘密只能由已经获得合理线索的人物知晓；不得让配角凭空说出、理解或利用秘密。
        3. 如果设定说明只有主角拥有或知道某事，除非用户后来明确要求揭露，否则正文和后续选项都必须继续保密。
        4. 后续选项只能描述从当前情节自然可达的行动或方向，不得把尚未发生的发现、关系或能力当成既成事实。
    """.trimIndent()

    fun modelInstruction(model: String): String = if (model.contains("deepseek", ignoreCase = true)) {
        "DeepSeek 写作适配：先在内部核对设定、人物知情范围和章节连续性，再只输出最终结果；不要展示推理过程。"
    } else {
        ""
    }

    fun temperatureFor(model: String): Float = if (model.contains("deepseek", ignoreCase = true)) 0.76f else 0.82f

    fun persistentWorldPrompt(premise: String, supplementalContext: String): String = buildString {
        appendLine("【小说核心设定（持续生效）】")
        appendLine(premise.trim())
        supplementalContext.trim().takeIf(String::isNotEmpty)?.let {
            appendLine()
            appendLine("【补充世界与人物资料】")
            appendLine(it)
        }
        appendLine()
        append(promptRules)
    }.trim()

    fun filterChoices(choices: List<String>, canonText: String): List<String> = choices
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .filterNot { violatesExplicitCanon(it, canonText) }
        .toList()

    fun sanitizeMessageChoices(content: String, canonText: String): String {
        if (!hasSecrecyConstraint(canonText) && !hasMundaneWorldConstraint(canonText)) return content
        val optionRegex = Regex("<OPTION>(.*?)</OPTION>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val blockRegex = Regex("<CHOICES>.*?</CHOICES>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val block = blockRegex.find(content)
        if (block != null) {
            val original = optionRegex.findAll(block.value).map { it.groupValues[1] }.toList()
            val kept = filterChoices(original, canonText)
            if (kept.size == original.size) return content
            val replacement = if (kept.isEmpty()) "" else kept.joinToString("", "<CHOICES>", "</CHOICES>") {
                "<OPTION>${escapeXml(it)}</OPTION>"
            }
            return content.replaceRange(block.range, replacement).trim()
        }
        return optionRegex.replace(content) { match ->
            match.takeIf { !violatesExplicitCanon(it.groupValues[1], canonText) }?.value.orEmpty()
        }.trim()
    }

    private fun violatesExplicitCanon(choice: String, canonText: String): Boolean =
        leaksProtectedKnowledge(choice, canonText) || introducesForbiddenPower(choice, canonText)

    private fun hasSecrecyConstraint(text: String): Boolean {
        val normalized = text.replace(" ", "")
        return listOf("只有", "仅主角", "别人都不知道", "其他人不知道", "无人知道", "无人知晓", "保密", "秘密")
            .any(normalized::contains)
    }

    private fun leaksProtectedKnowledge(choice: String, canonText: String): Boolean {
        if (!hasSecrecyConstraint(canonText)) return false
        val compact = choice.replace(" ", "")
        val protectedTopic = "系统|能力|力量|身份|秘密|真相|穿越|重生"
        return listOf(
            Regex("(告诉|告知|坦白|公开|暴露|透露).{0,12}($protectedTopic)"),
            Regex("($protectedTopic).{0,12}(公开|暴露|被发现|被知晓|泄露)"),
            Regex("(众人|其他人|别人|同学|同事|朋友|家人|父母|老师|警察|女主|男主|她|他).{0,12}(知道|得知|发现|识破|察觉).{0,12}($protectedTopic)"),
        ).any { it.containsMatchIn(compact) }
    }

    private fun hasMundaneWorldConstraint(text: String): Boolean {
        val normalized = text.replace(" ", "")
        return listOf("普通都市", "普通现代", "现实世界", "没有超自然", "没有额外的力量", "无超凡", "无异能")
            .any(normalized::contains)
    }

    private fun introducesForbiddenPower(choice: String, canonText: String): Boolean {
        if (!hasMundaneWorldConstraint(canonText)) return false
        val forbidden = listOf("魔法", "修仙", "灵气", "异能", "超能力", "血脉觉醒", "神明", "鬼怪", "妖怪")
        return forbidden.any { it in choice && it !in canonText }
    }

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
