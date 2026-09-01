package com.mojing.app.engine

import com.mojing.app.domain.engine.UniversalContextMemoryPrompt
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalContextMemoryPromptTest {
    @Test
    fun updatePromptRequiresJsonOnlyAndNoFabrication() {
        val prompt = UniversalContextMemoryPrompt.buildUpdatePrompt(
            oldMemoryText = "旧记忆",
            conversationText = "user: 你好\ncharacter: 你好",
            worldText = "世界设定",
            activeCharacterNames = listOf("角色A", "角色B"),
        )

        assertTrue(prompt.contains("只返回 JSON 对象"))
        assertTrue(prompt.contains("不得编造"))
        assertTrue(prompt.contains("characterStates"))
        assertTrue(prompt.contains("relationshipStates"))
        assertTrue(prompt.contains("continuityRules"))
    }
}
