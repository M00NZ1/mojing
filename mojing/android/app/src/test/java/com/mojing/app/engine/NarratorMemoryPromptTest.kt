package com.mojing.app.engine

import com.mojing.app.data.local.entity.*
import com.mojing.app.data.remote.ChatRequest
import com.mojing.app.data.remote.LlmApiService
import com.mojing.app.domain.engine.*
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NarratorMemoryPromptTest {
    private val builder = PromptBuilder()

    @Test fun narratorAndNovelSummaryReachRequestOnceWithNarratorIdentity() = runTest {
        for (mode in listOf("自由对话", "小说创作")) {
            val context = PromptBuilder.PromptContext(
                character = CharacterEntity(name = "参与角色", personaPrompt = "不应借用的人设"),
                world = SessionWorldEntity(sessionId = 42L, narratorName = "讲述者", gameplayMode = mode),
                personaName = "旅人", sessionId = 42L,
                recentMemorySegments = listOf(SessionMemorySegmentEntity(sessionId = 42L,
                    summary = "{{char}}记下{{user}}在北塔等待。会话{{session_id}}，模型{{model}}。{{char_description}}")),
                memoryCorrections = listOf(SessionMemoryCorrectionEntity(sessionId = 42L, content = "北塔仍然封闭")),
                universalContextMemoryText = "通用记忆内容",
            )
            val narrator = builder.buildNarratorPrompt(context, model = "test-model", includeUserProfile = false)
            val api = mockk<LlmApiService>()
            val request = slot<ChatRequest>()
            every { api.streamChatCompletionWithUsage(any(), any(), capture(request), any()) } returns flowOf("完成")
            val engine = ChatEngine(api, mockk(relaxed = true), mockk(relaxed = true), builder, mockk(relaxed = true))
            engine.streamGenerate(42L, CharacterEntity(name = "讲述者", personaPrompt = narrator), emptyList(),
                "key", "https://api.test", "test-model", 0.8f, 100, "旅人").toList()
            val system = request.captured.messages.first { it.role == "system" }.content
            assertTrue(system.contains("讲述者记下旅人在北塔等待。会话42，模型test-model。"))
            assertEquals(1, Regex("近期记忆摘要：").findAll(system).count())
            assertFalse(system.contains("不应借用的人设"))
            assertFalse(system.contains("{{"))
            assertTrue(system.indexOf("用户锁定记忆") < system.indexOf("近期记忆摘要"))
            assertTrue(system.contains("通用记忆内容"))
        }
    }

    @Test fun narratorStructuredSummaryTakesPriorityOverTextFallback() {
        val prompt = builder.buildNarratorPrompt(PromptBuilder.PromptContext(character = CharacterEntity(),
            recentMemorySegments = listOf(SessionMemorySegmentEntity(sessionId = 1L, summary = "当前摘要")),
            memorySummary = "不应重复的旧摘要"))
        assertTrue(prompt.contains("- 当前摘要"))
        assertFalse(prompt.contains("不应重复的旧摘要"))
    }

    @Test fun narratorBlankSummaryOmitsBlock() {
        val prompt = builder.buildNarratorPrompt(PromptBuilder.PromptContext(character = CharacterEntity(), memorySummary = " \n\t"))
        assertFalse(prompt.contains("近期记忆摘要"))
    }
}
