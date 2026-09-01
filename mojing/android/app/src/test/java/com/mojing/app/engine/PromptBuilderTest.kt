package com.mojing.app.engine

import com.mojing.app.domain.engine.PromptBuilder
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.SessionWorldEntity
import com.mojing.app.data.local.entity.SessionMemoryCorrectionEntity
import org.junit.Assert.*
import org.junit.Test

class PromptBuilderTest {

    private val builder = PromptBuilder()

    @Test
    fun `buildForCharacter includes persona prompt`() {
        val character = CharacterEntity(name = "李白", personaPrompt = "你是唐代诗人李白，好饮酒作诗。")
        val result = builder.buildForCharacter(PromptBuilder.PromptContext(character = character))
        assertTrue(result.contains("你是唐代诗人李白"))
    }

    @Test
    fun `buildForCharacter includes world prompt`() {
        val character = CharacterEntity(name = "侠客")
        val world = SessionWorldEntity(sessionId = 1L, worldPrompt = "这是一个武侠世界，江湖门派林立。")
        val result = builder.buildForCharacter(PromptBuilder.PromptContext(character = character, world = world))
        assertTrue(result.contains("武侠世界"))
    }

    @Test
    fun `buildForCharacter includes persona name`() {
        val character = CharacterEntity(name = "NPC")
        val result = builder.buildForCharacter(PromptBuilder.PromptContext(character = character, personaName = "小明"))
        assertTrue(result.contains("小明"))
    }

    @Test
    fun `buildNarratorPrompt includes narrator name`() {
        val world = SessionWorldEntity(sessionId = 1L, narratorEnabled = true, narratorName = "神秘旁白")
        val result = builder.buildNarratorPrompt(PromptBuilder.PromptContext(character = CharacterEntity(), world = world))
        assertTrue(result.contains("神秘旁白"))
    }

    @Test
    fun `choices are generated per reply without template fallback`() {
        val world = SessionWorldEntity(
            sessionId = 1L,
            choiceGenerationEnabled = true,
            maxChoiceCount = 3,
            suggestedChoicesJson = "[\"固定选项不应注入\"]",
        )

        val result = builder.buildForCharacter(
            PromptBuilder.PromptContext(character = CharacterEntity(name = "NPC"), world = world),
        )

        assertTrue(result.contains("<CHOICES>"))
        assertTrue(result.contains("每轮重新生成"))
        assertFalse(result.contains("固定选项不应注入"))
    }

    @Test
    fun `corrections are injected once before automatic memory`() {
        val correction = SessionMemoryCorrectionEntity(sessionId = 1L, content = "用户已经知道这件事")
        val result = builder.buildForCharacter(
            PromptBuilder.PromptContext(
                character = CharacterEntity(name = "NPC"),
                memoryCorrections = listOf(correction),
                recentMemorySegments = listOf(
                    com.mojing.app.data.local.entity.SessionMemorySegmentEntity(
                        sessionId = 1L,
                        summary = "自动摘要",
                    ),
                ),
                universalContextMemoryText = "通用自动记忆",
            ),
        )

        assertEquals(1, result.split("用户锁定记忆（冲突时优先）").size - 1)
        assertTrue(result.indexOf("用户锁定记忆（冲突时优先）") < result.indexOf("近期记忆摘要"))
        assertTrue(result.indexOf("用户锁定记忆（冲突时优先）") < result.indexOf("通用自动记忆"))
    }

    @Test
    fun `narrator injects corrections once before universal memory`() {
        val result = builder.buildNarratorPrompt(
            PromptBuilder.PromptContext(
                character = CharacterEntity(),
                memoryCorrections = listOf(SessionMemoryCorrectionEntity(sessionId = 1L, content = "旁白纠正")),
                universalContextMemoryText = "旁白自动记忆",
            ),
        )

        assertEquals(1, result.split("用户锁定记忆（冲突时优先）").size - 1)
        assertTrue(result.indexOf("用户锁定记忆（冲突时优先）") < result.indexOf("旁白自动记忆"))
    }
}
