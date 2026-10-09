package com.mojing.app.engine

import com.mojing.app.data.remote.ChatCompletionResult
import com.mojing.app.data.remote.LlmApiService
import com.mojing.app.domain.billing.CostRecorder
import com.mojing.app.domain.engine.AiCompleter
import com.mojing.app.domain.engine.LlmRetry
import com.mojing.app.domain.engine.RequestContextLimitException
import com.mojing.app.domain.usecase.AiCompleteUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AiCompleterBudgetTest {
    private val api = mockk<LlmApiService>()
    private val costs = mockk<CostRecorder>(relaxed = true)
    private val completer = AiCompleter(LlmRetry(api, costs))

    @Test fun allCompletionTypesRejectBeforeHttpOrBillingWithoutReducingOutput() = runTest {
        for (type in listOf("character", "world_template", "encyclopedia_entry", "encyclopedia_entry_meta")) {
            val error = runCatching { completer.complete("key", "url", "model",
                AiCompleter.CompleteRequest(type, "location", mapOf("content" to "完整原文".repeat(2000)), contextWindow = 4000)) }.exceptionOrNull()
            assertTrue(error is RequestContextLimitException)
            assertEquals(if (type == "encyclopedia_entry_meta") 8000 else 3000,
                (error as RequestContextLimitException).limit.outputTokens)
        }
        coVerify(exactly = 0) { api.chatCompletion(any(), any(), any()) }
        coVerify(exactly = 0) { costs.capture(any(), any(), any()) }
    }

    @Test fun adequateAndUnknownCapacitiesSendEntireInputAndOriginalOutput() = runTest {
        val content = "完整末尾".repeat(2000)
        val extra = "世界原文".repeat(2000)
        coEvery { api.chatCompletion(any(), any(), any()) } returns ChatCompletionResult("{\"region\":\"雾海\"}")
        for (capacity in listOf(100000, null)) {
            assertEquals("雾海", completer.complete("key", "url", "model",
                AiCompleter.CompleteRequest("encyclopedia_entry_meta", "location", mapOf("content" to content), extra, capacity))["region"])
        }
        coVerify(exactly = 2) { api.chatCompletion("key", "url", match {
            it.max_tokens == 8000 && it.messages.last().content.contains(content) && it.messages.last().content.contains(extra)
        }) }
    }

    @Test fun useCasePassesCapacityToTheSameGuard() = runTest {
        val error = runCatching { AiCompleteUseCase(completer)("key", "url", "model",
            "character", null, mapOf("name" to "潮生"), "", contextWindow = 2000) }.exceptionOrNull()
        assertTrue(error is RequestContextLimitException)
        coVerify(exactly = 0) { api.chatCompletion(any(), any(), any()) }
    }
}
