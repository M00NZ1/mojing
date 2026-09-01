package com.mojing.app.engine

import com.mojing.app.domain.engine.UniversalCharacterState
import com.mojing.app.domain.engine.UniversalContextMemory
import com.mojing.app.domain.engine.UniversalContextMemoryFormatter
import com.mojing.app.domain.engine.UniversalRelationshipState
import com.mojing.app.domain.engine.UniversalTimelineItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalContextMemoryFormatterTest {
    @Test
    fun formatIncludesCriticalContinuitySections() {
        val memory = UniversalContextMemory(
            globalSummary = "用户正在调查一件长期事件。",
            characterStates = listOf(
                UniversalCharacterState(
                    name = "角色A",
                    attitudeToUser = "警惕",
                    knownFacts = listOf("知道用户曾违背建议"),
                    unknownFacts = listOf("不知道用户持有关键证据"),
                ),
            ),
            relationshipStates = listOf(
                UniversalRelationshipState(
                    subject = "角色A",
                    objectName = "用户",
                    relation = "不信任",
                    evidence = "用户曾冒险行动",
                    stability = "稳定",
                ),
            ),
            recentCompressedTimeline = listOf(
                UniversalTimelineItem(
                    event = "用户请求协助",
                    cause = "想推进调查",
                    result = "角色A拒绝",
                    impact = "关系更紧张",
                ),
            ),
            continuityRules = listOf("不得把角色A写成用户好友"),
        )

        val text = UniversalContextMemoryFormatter.format(memory)

        assertTrue(text.contains("【通用高密度剧情记忆】"))
        assertTrue(text.contains("角色A"))
        assertTrue(text.contains("不得把角色A写成用户好友"))
        assertFalse(text.contains("null"))
    }
}
