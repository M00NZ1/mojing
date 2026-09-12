package com.mojing.app.engine

import com.mojing.app.data.remote.ChatMessage
import com.mojing.app.data.remote.LlmApiService
import com.mojing.app.data.remote.LlmHttpException
import com.mojing.app.domain.billing.CostRecorder
import com.mojing.app.domain.engine.LlmRetry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.clearMocks
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmRetryTest {
    private val api = mockk<LlmApiService>()
    private val costs = mockk<CostRecorder>(relaxed = true)
    private val retry = LlmRetry(api, costs)
    private val messages = listOf(ChatMessage("user", "hello"))

    @Test
    fun truncatedNonStreamingOutputIsNotPassedToJsonParserOrRetried() = runTest {
        coEvery { api.chatCompletion(any(), any(), any()) } returns
            com.mojing.app.data.remote.ChatCompletionResult("", finishReason = "length")
        val error = runCatching { retry.chatCompletionWithRetry("key", "url", "model", messages) }.exceptionOrNull()
        assertEquals("output_limit", (error as com.mojing.app.data.remote.LlmProtocolException).reason)
        coVerify(exactly = 1) { api.chatCompletion(any(), any(), any()) }
    }

    @Test
    fun rateLimitRetriesAndReportsAttemptsWithNoFinalDelay() = runTest {
        coEvery { api.streamStoryCompletion(any(), any(), any()) } returnsMany listOf(
            flow { throw LlmHttpException(429, retryAfterMs = 1_000) },
            flow { throw LlmHttpException(429, retryAfterMs = 2_000) },
            flow { emit("done") },
        )
        val attempts = mutableListOf<Int>()
        val retries = mutableListOf<Pair<Int, Long>>()
        val result = retry.chatCompletionStreamingWithRetry(
            "key", "https://example.test", "model", messages,
            maxRetries = 3,
            onAttempt = attempts::add,
            onRetry = { next, delay -> retries += next to delay },
        )
        assertEquals("done", result)
        assertEquals(listOf(1, 2, 3), attempts)
        assertEquals(listOf(2 to 1_000L, 3 to 2_000L), retries)
        assertEquals(3_000L, testScheduler.currentTime)
    }

    @Test
    fun badRequestAndUnauthorizedAreNotRetried() = runTest {
        for (status in listOf(400, 401)) {
            clearMocks(api)
            coEvery { api.streamStoryCompletion(any(), any(), any()) } returns flow {
                throw LlmHttpException(status)
            }
            val error = runCatching {
                retry.chatCompletionStreamingWithRetry("key", "url", "model", messages)
            }.exceptionOrNull()
            assertTrue(error is LlmHttpException)
            assertEquals(status, (error as LlmHttpException).status)
            coVerify(exactly = 1) { api.streamStoryCompletion(any(), any(), any()) }
        }
    }

    @Test
    fun failureAfter正文DoesNotRetryOrEmitDuplicate正文() = runTest {
        coEvery { api.streamStoryCompletion(any(), any(), any()) } returns flow {
            emit("first")
            throw java.io.IOException("断流")
        }
        val chunks = mutableListOf<String>()
        val error = runCatching {
            retry.chatCompletionStreamingWithRetry("key", "url", "model", messages, onDelta = chunks::add)
        }.exceptionOrNull()
        assertTrue(error is java.io.IOException)
        assertEquals(listOf("first"), chunks)
        coVerify(exactly = 1) { api.streamStoryCompletion(any(), any(), any()) }
    }

    @Test
    fun cancellationPropagatesWithoutRetry() = runTest {
        val cancelled = CancellationException("stop")
        coEvery { api.streamStoryCompletion(any(), any(), any()) } returns flow { throw cancelled }
        val error = runCatching {
            retry.chatCompletionStreamingWithRetry("key", "url", "model", messages)
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(cancelled.message, error?.message)
        coVerify(exactly = 1) { api.streamStoryCompletion(any(), any(), any()) }
    }

    @Test
    fun successful正文SurvivesBillingFailure() = runTest {
        coEvery { api.streamStoryCompletion(any(), any(), any()) } returns flow { emit("正文") }
        coEvery { costs.recordLlm(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws IllegalStateException("db")
        assertEquals("正文", retry.chatCompletionStreamingWithRetry("key", "url", "model", messages))
        coVerify(exactly = 1) { api.streamStoryCompletion(any(), any(), any()) }
    }

    @Test
    fun originalFailureSurvivesFailureBillingFailure() = runTest {
        val original = LlmHttpException(400)
        coEvery { api.streamStoryCompletion(any(), any(), any()) } returns flow { throw original }
        coEvery { costs.recordLlm(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws IllegalStateException("db")
        val error = runCatching {
            retry.chatCompletionStreamingWithRetry("key", "url", "model", messages)
        }.exceptionOrNull()
        assertSame(original, error)
        coVerify(exactly = 1) { api.streamStoryCompletion(any(), any(), any()) }
    }
}
