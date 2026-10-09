package com.mojing.app.engine

import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.SessionMemorySegmentEntity
import com.mojing.app.data.remote.ChatRequest
import com.mojing.app.data.remote.LlmApiService
import com.mojing.app.domain.billing.CostRecorder
import com.mojing.app.domain.engine.*
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ContextBuilderMemoryPromptTest {
    private val character = CharacterEntity(id = 9L, name = "守灯人")
    private val prompts = PromptBuilder()
    private val builder = ContextBuilder(prompts, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
    private suspend fun context(summary: String) = builder.buildFullContext(
        character = character, world = null, personaName = "旅人", userDescription = "",
        userMessage = "继续", memorySummary = summary, activeCharacterNames = listOf(character.name),
        sessionId = 1L, effectiveModelName = "model",
    )

    @Test fun persistedSummaryReachesActualCharacterRequestOnceWithMacros() = runTest {
        val summary = "- {{char}}已答应带{{user}}去北塔。\n- 钥匙在石桥下。"
        val context = context(summary)
        val api = mockk<LlmApiService>()
        val request = slot<ChatRequest>()
        every { api.streamChatCompletionWithUsage(any(), any(), capture(request), any()) } returns flowOf("完成")
        val engine = ChatEngine(api, mockk(relaxed = true), mockk<CostRecorder>(relaxed = true), prompts, mockk(relaxed = true))
        engine.streamGenerateWithMemory(1L, character, emptyList(), context.systemPrompt, null,
            TokenBudgetManager().calculateBudget(8192, 100), "key", "https://api.test", "model").toList()
        val system = request.captured.messages.first { it.role == "system" }.content
        assertTrue(system.contains("近期记忆摘要："))
        assertTrue(system.contains("守灯人已答应带旅人去北塔。"))
        assertEquals(1, Regex("钥匙在石桥下。").findAll(system).count())
        assertEquals(summary, context.memorySummary)
    }

    @Test fun emptyOrBlankSummaryDoesNotCreateMemoryBlock() = runTest {
        for (summary in listOf("", " \n\t")) {
            assertFalse(context(summary).systemPrompt.contains("近期记忆摘要："))
        }
    }

    @Test fun structuredSegmentsRemainAuthoritativeWithoutDuplicatingTextSummary() {
        val system = prompts.buildForCharacter(PromptBuilder.PromptContext(
            character = character,
            recentMemorySegments = listOf(SessionMemorySegmentEntity(sessionId = 1L, summary = "当前剧情事实")),
            memorySummary = "旧摘要不应重复加入",
        ))
        assertTrue(system.contains("- 当前剧情事实"))
        assertFalse(system.contains("旧摘要不应重复加入"))
        assertEquals(1, Regex("近期记忆摘要：").findAll(system).count())
    }
}
