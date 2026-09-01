package com.mojing.app.domain.workbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorldTemplateQualityTest {

    @Test
    fun richTemplate_scoresHigh() {
        val o = LocalWorldTemplateQuality.compute(
            label = "修仙大世界",
            summary = "这是一段足够长的世界摘要，描述核心冲突与修仙资源争夺。",
            worldPrompt = "x".repeat(200) + "具体宗门青云门与魔道血河教对立，灵石矿脉在苍梧山，规则为天道誓言不可违。玩家自定义设定优先。",
            antiCheatPrompt = "禁止一句话获得渡劫修为。",
        )
        assertTrue(o.score >= 85)
        assertEquals("可直接试玩", o.verdict)
        assertTrue(o.strengths.isNotEmpty())
        assertTrue(o.strengths.any { it.contains("固定规则") })
        assertFalse(o.strengths.any { it.contains("防越权") })
    }

    @Test
    fun emptyTemplate_scoresLow() {
        val o = LocalWorldTemplateQuality.compute(
            label = "",
            summary = "短",
            worldPrompt = "短",
            antiCheatPrompt = "",
        )
        assertTrue(o.score < 70)
        assertTrue(o.improvements.isNotEmpty())
        assertTrue(o.risks.any { it.contains("固定规则") })
        assertFalse((o.risks + o.improvements).any { it.contains("防越权") })
    }
}
