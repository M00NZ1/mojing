package com.mojing.app.engine

import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.remote.ChatMessage
import com.mojing.app.data.remote.ChatRequest
import com.mojing.app.data.remote.LlmApiService
import com.mojing.app.domain.engine.*
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RequestContextBudgetTest {
    @Test fun trimsOnlyOldWholeMessagesAndRetainsCurrentMultiRoleChain() {
        val messages = listOf(ChatMessage("system", "核心身份与锁定设定"), ChatMessage("user", "旧问题".repeat(100)),
            ChatMessage("assistant", "旧回复".repeat(100)), ChatMessage("user", "完整当前输入"),
            ChatMessage("assistant", "甲的完整发言"), ChatMessage("assistant", "旁白完整发言"))
        val protected = listOf(messages.first()) + messages.takeLast(3)
        val capacity = RequestContextBudget.estimateInput(protected).toInt() + 100
        val result = RequestContextBudget.fit(messages, capacity, 100) as RequestContextBudget.Result.Ready
        assertEquals(protected, result.messages)
        assertEquals(2, result.removedMessages)
        assertEquals(capacity.toLong(), result.estimatedInput + 100)
        assertTrue(RequestContextBudget.fit(messages, capacity - 1, 100) is RequestContextBudget.Result.TooLarge)
    }

    @Test fun noUserRetainsFinalMessageAndSystemAndUnknownCapacityNeverRejects() {
        val messages = listOf(ChatMessage("system", "固定规则"), ChatMessage("assistant", "旧".repeat(100)),
            ChatMessage("assistant", "继续这段正文"))
        val expected = listOf(messages.first(), messages.last())
        val cap = RequestContextBudget.estimateInput(expected).toInt() + 50
        assertEquals(expected, (RequestContextBudget.fit(messages, cap, 50) as RequestContextBudget.Result.Ready).messages)
        assertEquals(messages, (RequestContextBudget.fit(messages, null, Int.MAX_VALUE) as RequestContextBudget.Result.Ready).messages)
        assertTrue(RequestContextBudget.fit(messages, Int.MAX_VALUE, Int.MAX_VALUE) is RequestContextBudget.Result.TooLarge)
    }

    @Test fun countWindowAlsoKeepsHumanTriggerOfMoreThanTwentyReplies() {
        val messages = listOf(MessageEntity(sessionId = 1, speakerType = "user", content = "触发本轮")) +
            (1..25).map { MessageEntity(sessionId = 1, speakerType = "character", content = "$it") }
        assertEquals(messages, SlidingWindowBuilder().takeRecentPreservingTurn(messages, 20))
        assertEquals(messages.takeLast(20), SlidingWindowBuilder().takeRecentPreservingTurn(messages.drop(1), 20))
    }

    @Test fun finalUserMacroAndSnapshotAreCountedBeforeNetworkOrBilling() = runTest {
        val api = mockk<LlmApiService>()
        val costs = mockk<com.mojing.app.domain.billing.CostRecorder>(relaxed = true)
        val engine = ChatEngine(api, mockk(relaxed = true), costs, PromptBuilder(), mockk(relaxed = true))
        val character = CharacterEntity(name = "甲", maxTokens = 100)
        val history = listOf(MessageEntity(sessionId = 1, speakerType = "user", content = "{{user_description}}"))
        val budget = TokenBudget(0, 0, 0, 0, 100, 1200)
        val states = engine.streamGenerateWithMemory(1, character, history, "核心规则", null, budget,
            "synthetic", "https://example.test", "model", userDescription = "宏增长".repeat(1000)).toList()
        assertTrue(states.single() is StreamState.Error)
        assertTrue((states.single() as StreamState.Error).contextLimit)
        verify { api wasNot Called }
        coVerify(exactly = 0) { costs.capture(any(), any(), any()) }
        coVerify(exactly = 0) { costs.recordLlm(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        val anchor = CharacterSnapshot(mood = "情绪".repeat(1000))
        assertTrue(engine.streamGenerateWithMemory(1, character, emptyList(), "核心规则", anchor, budget,
            "synthetic", "https://example.test", "model").toList().single() is StreamState.Error)
        verify { api wasNot Called }
    }

    @Test fun actualMemoryRequestContainsOnlyFittedMessagesAndPreservesOutput() = runTest {
        val api = mockk<LlmApiService>()
        val request = slot<ChatRequest>()
        every { api.streamChatCompletionWithUsage(any(), any(), capture(request), any()) } returns flowOf("完整回复")
        val engine = ChatEngine(api, mockk(relaxed = true), mockk(relaxed = true), PromptBuilder(), mockk(relaxed = true))
        val character = CharacterEntity(name = "甲", maxTokens = 100)
        val old = MessageEntity(sessionId = 1, speakerType = "character", content = "旧".repeat(1000))
        val current = MessageEntity(sessionId = 1, speakerType = "user", content = "完整本轮输入")
        val cap = RequestContextBudget.estimateInput(listOf(ChatMessage("system", "固定设定"), ChatMessage("user", current.content))).toInt() + 100
        val states = engine.streamGenerateWithMemory(1, character, listOf(old, current), "固定设定", null,
            TokenBudget(0, 0, 0, 0, 100, cap), "synthetic", "https://example.test", "model").toList()
        assertTrue(states.last() is StreamState.Done)
        assertEquals(listOf("固定设定", current.content), request.captured.messages.map { it.content })
        assertEquals(100, request.captured.max_tokens)
    }

    @Test fun narratorStyleFinalBuilderPathAndAnthropicAreAlsoGuarded() = runTest {
        val api = mockk<LlmApiService>()
        val adapter = mockk<AnthropicAdapter>()
        val engine = ChatEngine(api, mockk(relaxed = true), mockk(relaxed = true), PromptBuilder(), adapter)
        val character = CharacterEntity(name = "旁白", personaPrompt = "世界设定".repeat(1000))
        val states = engine.streamGenerate(1, character, emptyList(), "synthetic", "https://api.anthropic.com", "model",
            0.8f, 12000, contextWindow = 16000).toList()
        assertTrue(states.single() is StreamState.Error)
        verify { api wasNot Called; adapter wasNot Called }
    }
}
