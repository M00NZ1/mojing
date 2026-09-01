package com.mojing.app.domain.workbench

/**
 * 世界模板的 **离线** 质量概览，规则简化自后端 `review_world_package`（无 Lore、不调 LLM）。
 */
data class LocalTemplateQualityOverview(
    val score: Int,
    val verdict: String,
    val strengths: List<String>,
    val risks: List<String>,
    val improvements: List<String>,
    /** 非扣分说明（如跨端能力提示） */
    val notes: List<String>,
)

object LocalWorldTemplateQuality {

    private val genericPhrases = listOf(
        "丰富多彩", "非常精彩", "无限可能", "各种各样", "很多不同",
        "十分有趣", "极具魅力", "充满神秘",
    )

    fun compute(
        label: String,
        summary: String,
        worldPrompt: String,
        antiCheatPrompt: String,
    ): LocalTemplateQualityOverview {
        val strengths = mutableListOf<String>()
        val risks = mutableListOf<String>()
        val improvements = mutableListOf<String>()
        val notes = mutableListOf<String>()

        notes.add("这是本机按规则生成的概览，未包含设定细则，和完整检查结果可能不同。")
        notes.add("如需结合设定细则，请在模板编辑页使用「检查完整设定」（需连接本机服务）。")

        var score = 100

        val sTrim = summary.trim()
        val wTrim = worldPrompt.trim()
        val aTrim = antiCheatPrompt.trim()

        if (label.isNotBlank()) {
            strengths.add("已填写模板名称。")
        } else {
            score -= 5
            improvements.add("建议填写模板名称，便于在列表中识别。")
        }

        when {
            sTrim.length >= 20 -> strengths.add("世界摘要长度基本够用。")
            sTrim.length >= 10 -> {
                score -= 5
                improvements.add("摘要可以再写长一点：点出冲突、氛围或玩法钩子（约 20 字以上更佳）。")
            }
            else -> {
                score -= 10
                improvements.add("摘要过短：建议写出核心冲突、氛围或卖点（约 20 字以上）。")
            }
        }

        when {
            wTrim.length >= 180 -> strengths.add("世界设定的信息量基本足够。")
            wTrim.length >= 120 -> {
                score -= 5
                improvements.add("世界观还可再写具体些：势力、地点、规则或当前局势（约 180 字以上更佳）。")
            }
            wTrim.length >= 60 -> {
                score -= 10
                improvements.add("世界观主设定偏薄：建议补充规则、阵营、地点或当前局势。")
            }
            wTrim.isNotEmpty() -> {
                score -= 15
                improvements.add("世界观主设定偏薄：建议补充规则、阵营、地点或当前局势（约 180 字以上）。")
            }
            else -> {
                score -= 15
                improvements.add("请填写世界设定正文。")
            }
        }

        if (aTrim.isNotEmpty()) {
            strengths.add("已填写固定规则。")
        } else {
            score -= 8
            risks.add("固定规则为空，剧情更容易接受与世界设定冲突的捷径。")
            improvements.add("写明不能被临时改写的规则，例如身份、情报和资源不能凭一句话获得。")
        }

        if (wTrim.length >= 80 && looksGeneric(wTrim)) {
            score -= 12
            risks.add("世界设定中空话、套话偏多。")
            improvements.add("增加具体势力、地点、资源、规则与代价，减少泛化形容。")
        }

        if (wTrim.isNotEmpty() && !mentionsPlayerPriority(wTrim)) {
            score -= 3
            improvements.add("可写明「你的手写设定优先，自动补全只填空白」，避免既有内容被覆盖。")
        }

        val finalScore = score.coerceIn(0, 100)
        val verdict = when {
            finalScore >= 85 -> "可直接试玩"
            finalScore >= 70 -> "可用但建议补强"
            else -> "不建议直接投入主流程"
        }

        return LocalTemplateQualityOverview(
            score = finalScore,
            verdict = verdict,
            strengths = strengths,
            risks = risks,
            improvements = improvements,
            notes = notes,
        )
    }

    private fun mentionsPlayerPriority(text: String): Boolean {
        val t = text.lowercase()
        return text.contains("用户") || text.contains("玩家") || text.contains("手填") || text.contains("原始设定") ||
            t.contains("player") || t.contains("user-defined") || t.contains("user defined")
    }

    private fun looksGeneric(text: String): Boolean {
        var hits = 0
        for (p in genericPhrases) {
            if (text.contains(p)) hits++
        }
        return hits >= 3
    }
}
