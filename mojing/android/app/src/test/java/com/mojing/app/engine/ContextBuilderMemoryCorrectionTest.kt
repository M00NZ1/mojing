package com.mojing.app.engine

import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.SessionMemoryCorrectionEntity
import com.mojing.app.data.local.entity.SessionWorldEntity
import com.mojing.app.domain.engine.CharacterBookSearcher
import com.mojing.app.domain.engine.ContextBuilder
import com.mojing.app.domain.engine.EncyclopediaSearcher
import com.mojing.app.domain.engine.LoreSearcher
import com.mojing.app.domain.engine.PromptBuilder
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextBuilderMemoryCorrectionTest {
    @Test
    fun encyclopediaFoundationIsAvailableWithoutSearchHitsAndAvoidsSnapshotDuplication() = runTest {
        val dao = mockk<com.mojing.app.data.local.dao.EncyclopediaDao>()
        coEvery { dao.getById(7L) } returns com.mojing.app.data.local.entity.EncyclopediaEntity(
            id = 7L, description = "港口城市", worldPrompt = "此世界不存在魔法",
        )
        val searcher = mockk<EncyclopediaSearcher>(relaxed = true)
        val builder = ContextBuilder(PromptBuilder(), searcher, mockk(relaxed = true), mockk(relaxed = true), dao)
        val world = SessionWorldEntity(sessionId = 1L, encyclopediaId = 7L)
        val shared = builder.searchWorldContext(world, "你好")
        assertEquals("港口城市\n\n此世界不存在魔法", shared.encyclopediaFoundation)
        assertEquals(emptyList<String>(), shared.encyclopediaHits)
        val promptContext = PromptBuilder.PromptContext(
            character = CharacterEntity(id = 2L, name = "林汐"), world = world,
            encyclopediaHits = shared.encyclopediaHits, encyclopediaFoundation = shared.encyclopediaFoundation,
        )
        org.junit.Assert.assertTrue(PromptBuilder().buildForCharacter(promptContext, "model").contains("此世界不存在魔法"))
        org.junit.Assert.assertTrue(PromptBuilder().buildNarratorPrompt(promptContext, "继续", "model").contains("此世界不存在魔法"))
        assertEquals("", builder.encyclopediaFoundation(world.copy(worldPrompt = "港口城市\n此世界不存在魔法")))
        assertEquals("", builder.encyclopediaFoundation(world.copy(encyclopediaId = null)))
        coEvery { dao.getById(7L) } returns null
        assertEquals("", builder.encyclopediaFoundation(world))
    }

    @Test
    fun passesCorrectionsThroughToPromptContext() = runTest {
        val promptBuilder = mockk<PromptBuilder>()
        val contextSlot = slot<PromptBuilder.PromptContext>()
        every { promptBuilder.buildCharacterDocument(capture(contextSlot), any(), any()) } returns com.mojing.app.domain.engine.PromptDocument.protected("prompt")
        val encyclopedia = mockk<EncyclopediaSearcher>(relaxed = true)
        val lore = mockk<LoreSearcher>(relaxed = true)
        val characterBook = mockk<CharacterBookSearcher>(relaxed = true)
        coEvery { encyclopedia.search(any(), any(), any(), any()) } returns emptyList()
        coEvery { lore.search(any(), any(), any()) } returns emptyList()
        coEvery { characterBook.search(any(), any(), any()) } returns emptyList()

        val corrections = listOf(SessionMemoryCorrectionEntity(sessionId = 1L, content = "纠正"))
        ContextBuilder(promptBuilder, encyclopedia, lore, characterBook, mockk(relaxed = true)).buildFullContext(
            character = CharacterEntity(id = 2L),
            world = null,
            personaName = "玩家",
            userDescription = "",
            userMessage = "你好",
            memorySummary = "",
            memoryCorrections = corrections,
            activeCharacterNames = emptyList(),
            sessionId = 1L,
            effectiveModelName = "model",
        )

        assertEquals(corrections, contextSlot.captured.memoryCorrections)
    }

    @Test
    fun searchesSharedWorldContextForNarratorAndCharacterPrompts() = runTest {
        val promptBuilder = mockk<PromptBuilder>(relaxed = true)
        val encyclopedia = mockk<EncyclopediaSearcher>()
        val lore = mockk<LoreSearcher>()
        val characterBook = mockk<CharacterBookSearcher>(relaxed = true)
        coEvery { encyclopedia.search(any(), any(), any(), any()) } returns listOf(
            EncyclopediaSearcher.HitEntry("旧城", "城门在午夜关闭", 3.0),
        )
        coEvery { lore.search(any(), any(), any()) } returns listOf(
            LoreSearcher.LoreHit("禁令", "雨夜不得点燃蓝灯", 2.0),
        )

        val result = ContextBuilder(promptBuilder, encyclopedia, lore, characterBook, mockk(relaxed = true)).searchWorldContext(
            world = SessionWorldEntity(
                sessionId = 1L,
                templateId = "rain-city",
                encyclopediaId = 7L,
            ),
            recallQueryText = "雨夜来到旧城",
        )

        assertEquals(listOf("[旧城] 城门在午夜关闭"), result.encyclopediaHits)
        assertEquals(listOf("[禁令] 雨夜不得点燃蓝灯"), result.loreHits)
    }
}
