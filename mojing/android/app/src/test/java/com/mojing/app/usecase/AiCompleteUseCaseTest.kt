package com.mojing.app.usecase

import com.mojing.app.domain.engine.AiCompleter
import com.mojing.app.domain.engine.LlmRetry
import com.mojing.app.domain.usecase.AiCompleteUseCase
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCompleteUseCaseTest {

    private val llmRetry = mockk<LlmRetry>(relaxed = true)
    private val aiCompleter = AiCompleter(llmRetry)
    private val useCase = AiCompleteUseCase(aiCompleter)

    @Test
    fun invokeReturnsNonEmptyMap() = runTest {
        coEvery {
            llmRetry.chatCompletionWithRetry(any(), any(), any(), any(), any(), any(), any())
        } returns """{"title":"龙族首领"}"""
        val result = useCase(
            apiKey = "sk-test",
            baseUrl = "https://api.test.com",
            model = "gpt-4",
            targetType = "encyclopedia_entry",
            entryType = "character",
            currentData = mapOf("name" to "黑龙"),
            extraContext = "修仙世界"
        )
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun invokeReturnsEmptyMapOnEmptyResponse() = runTest {
        coEvery {
            llmRetry.chatCompletionWithRetry(any(), any(), any(), any(), any(), any(), any())
        } returns ""
        val result = useCase(
            apiKey = "sk-test",
            baseUrl = "https://api.test.com",
            model = "gpt-4",
            targetType = "encyclopedia_entry",
            entryType = "concept",
            currentData = emptyMap(),
            extraContext = ""
        )
        assertFalse(result.isNotEmpty())
    }
}
