package com.mojing.app.engine

import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.domain.engine.*
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.remote.ChatRequest
import com.mojing.app.data.remote.LlmApiService
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SlidingWindowBuilderTest {
    private val builder = SlidingWindowBuilder()
    private fun message(id: Long, role: String, content: String) =
        MessageEntity(id = id, sessionId = 1L, speakerType = role, content = content)

    @Test fun longCurrentInputRemainsWholeBeyondHistoryQuota() {
        val current = message(2, "user", "当前完整输入".repeat(800) + "唯一尾标记")
        assertEquals(listOf(current), builder.buildWindow(listOf(message(1, "character", "旧回复"), current), 100))
    }

    @Test fun multiCharacterContinuationRetainsLatestUserAndAllFollowingReplies() {
        val currentTurn = listOf(message(2, "user", "用户输入".repeat(100)),
            message(3, "character", "角色甲回复".repeat(100)), message(4, "narrator", "旁白"))
        assertEquals(currentTurn, builder.buildWindow(listOf(message(1, "user", "旧轮次")) + currentTurn, 30))
    }

    @Test fun optionalOlderHistoryUsesRemainingQuotaInOrder() {
        val history = listOf(message(1, "user", "很早的输入".repeat(100)),
            message(2, "character", "上次回复"), message(3, "user", "新输入"), message(4, "character", "甲"))
        val quota = history.drop(1).sumOf { TokenCounter.estimate(it.content) }
        assertEquals(history.drop(1), builder.buildWindow(history, quota))
        assertEquals(history.drop(2), builder.buildWindow(history, quota - 1))
    }

    @Test fun olderOversizedMessageDoesNotSkipToUnrelatedEarlierHistory() {
        val history = listOf(message(1, "user", "旧"), message(2, "character", "过长旧回复".repeat(100)),
            message(3, "user", "当前"))
        assertEquals(history.takeLast(1), builder.buildWindow(history, 50))
    }

    @Test fun continuationWithoutUserRetainsLastMessageWhole() {
        val history = listOf(message(1, "narrator", "旧开篇"), message(2, "character", "当前开篇".repeat(100)))
        assertEquals(history.takeLast(1), builder.buildWindow(history, 10))
    }

    @Test fun emptyAndNonpositiveQuotasKeepCurrentTurnWithoutInventingMessages() {
        assertTrue(builder.buildWindow(emptyList(), 0).isEmpty())
        val history = listOf(message(1, "character", "旧"), message(2, "user", "当前"))
        for (quota in listOf(0, -100)) assertEquals(history.takeLast(1), builder.buildWindow(history, quota))
    }

    @Test fun completeInputAndMultiCharacterChainReachActualRequestBoundary() = runTest {
        val user = message(2, "user", "LONG_START\n" + "长".repeat(2700) + "\nLONG_END")
        val firstReply = message(3, "character", "角色甲完整回复".repeat(100))
        val api = mockk<LlmApiService>()
        val request = slot<ChatRequest>()
        every { api.streamChatCompletionWithUsage(any(), any(), capture(request), any()) } returns flowOf("完成")
        val engine = ChatEngine(api, mockk(relaxed = true), mockk(relaxed = true), PromptBuilder(), mockk(relaxed = true))
        val character = CharacterEntity(id = 9L, name = "乙")
        val budget = TokenBudgetManager().calculateBudget(8192, character.maxTokens)
        assertTrue(TokenCounter.estimate(user.content) > budget.shortTermWindow)
        assertTrue(TokenCounter.estimate(user.content) < 8192 - character.maxTokens)
        for (turn in listOf(listOf(user), listOf(user, firstReply))) {
            val history = builder.buildWindow(listOf(message(1, "character", "旧回复")) + turn, budget.shortTermWindow)
            engine.streamGenerateWithMemory(1, character, history, "核心规则完整保留", null,
                budget, "local-only", "https://api.test", "model").toList()
            assertEquals(listOf("核心规则完整保留") + turn.map { it.content }, request.captured.messages.map { it.content })
            assertEquals(listOf("system") + turn.map { if (it.speakerType == "user") "user" else "assistant" },
                request.captured.messages.map { it.role })
        }
    }
}
