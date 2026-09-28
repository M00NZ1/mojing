package com.mojing.app.engine

import com.mojing.app.data.remote.ChatMessage
import com.mojing.app.data.remote.LlmApiService
import com.mojing.app.data.remote.LlmHttpException
import com.mojing.app.data.remote.TokenUsage
import com.mojing.app.domain.billing.CostRecorder
import com.mojing.app.domain.engine.LlmRetry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

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
    fun truncatedNonStreamingResponseKeepsUsageAndOutputInFailureRecord() = runTest {
        coEvery { api.chatCompletion(any(), any(), any()) } returns
            com.mojing.app.data.remote.ChatCompletionResult("partial", 8, 4, 12, "length", 2, true)
        runCatching { retry.chatCompletionWithRetry("key", "url", "model", messages) }
        coVerify(exactly = 1) {
            costs.recordLlm(null, null, "model", "llm_json", 8, 4, any(), false, any(), "partial", any(), true, 2, "failed")
        }
    }

    @Test
    fun rateLimitRetriesAndReportsAttemptsWithNoFinalDelay() = runTest {
        every { api.streamStoryCompletionWithUsage(any(), any(), any(), any()) } returnsMany listOf(
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
        coVerify(exactly = 3) { costs.recordLlm(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun successfulStreamRecordsProviderUsageAndCachedTokens() = runTest {
        every { api.streamStoryCompletionWithUsage(any(), any(), any(), any()) } answers {
            val usageCallback = arg<(TokenUsage?) -> Unit>(3)
            flow {
                usageCallback(TokenUsage(promptTokens = 5, completionTokens = 3, cachedPromptTokens = 2))
                emit("正文")
            }
        }
        retry.chatCompletionStreamingWithRetry("key", "https://example.test", "model", messages)
        coVerify(exactly = 1) {
            costs.recordLlm(null, null, "model", "llm_stream", 5, 3, any(), true, any(), "正文", any(), true, 2, "success")
        }
    }

    @Test
    fun failedStreamKeepsPartialUsageForFailedAttempt() = runTest {
        every { api.streamStoryCompletionWithUsage(any(), any(), any(), any()) } answers {
            val callback = arg<(TokenUsage?) -> Unit>(3)
            flow {
                callback(TokenUsage(7, 3, 1))
                emit("部分")
                throw java.io.IOException("断流")
            }
        }
        runCatching { retry.chatCompletionStreamingWithRetry("key", "url", "model", messages) }
        coVerify(exactly = 1) {
            costs.recordLlm(null, null, "model", "llm_stream", 7, 3, any(), false, any(), "部分", any(), true, 1, "failed")
        }
    }

    @Test
    fun badRequestAndUnauthorizedAreNotRetried() = runTest {
        for (status in listOf(400, 401)) {
            clearMocks(api)
            every { api.streamStoryCompletionWithUsage(any(), any(), any(), any()) } returns flow {
                throw LlmHttpException(status)
            }
            val error = runCatching {
                retry.chatCompletionStreamingWithRetry("key", "url", "model", messages)
            }.exceptionOrNull()
            assertTrue(error is LlmHttpException)
            assertEquals(status, (error as LlmHttpException).status)
            verify(exactly = 1) { api.streamStoryCompletionWithUsage(any(), any(), any(), any()) }
        }
    }

    @Test
    fun failureAfter正文DoesNotRetryOrEmitDuplicate正文() = runTest {
        every { api.streamStoryCompletionWithUsage(any(), any(), any(), any()) } returns flow {
            emit("first")
            throw java.io.IOException("断流")
        }
        val chunks = mutableListOf<String>()
        val error = runCatching {
            retry.chatCompletionStreamingWithRetry("key", "url", "model", messages, onDelta = chunks::add)
        }.exceptionOrNull()
        assertTrue(error is java.io.IOException)
        assertEquals(listOf("first"), chunks)
        verify(exactly = 1) { api.streamStoryCompletionWithUsage(any(), any(), any(), any()) }
    }

    @Test
    fun activeStoryStreamCanContinueBeyondFiveMinutes() = runTest {
        every { api.streamStoryCompletionWithUsage(any(), any(), any(), any()) } returns flow {
            repeat(7) { index ->
                emit("第${index + 1}段")
                delay(60_000L)
            }
        }
        val chunks = mutableListOf<String>()
        val result = retry.chatCompletionStreamingWithRetry(
            "key", "url", "model", messages, onDelta = chunks::add,
        )
        assertEquals((1..7).joinToString("") { "第${it}段" }, result)
        assertEquals(7, chunks.size)
        verify(exactly = 1) { api.streamStoryCompletionWithUsage(any(), any(), any(), any()) }
    }

    @Test
    fun storyStreamWithoutContentTimesOutEvenIfConnectionRemainsOpen() = runTest {
        every { api.streamStoryCompletionWithUsage(any(), any(), any(), any()) } returns flow {
            delay(5 * 60 * 1_000L + 1)
            emit("太迟的正文")
        }
        val error = runCatching {
            retry.chatCompletionStreamingWithRetry("key", "url", "model", messages, maxRetries = 1)
        }.exceptionOrNull()
        assertTrue(error is SocketTimeoutException)
        verify(exactly = 1) { api.streamStoryCompletionWithUsage(any(), any(), any(), any()) }
    }

    @Test
    fun contentIdleTimeoutKeepsReceivedTextAndDoesNotRetry() = runTest {
        every { api.streamStoryCompletionWithUsage(any(), any(), any(), any()) } returns flow {
            emit("已收到正文")
            delay(5 * 60 * 1_000L + 1)
            emit("不应到达")
        }
        val chunks = mutableListOf<String>()
        val error = runCatching {
            retry.chatCompletionStreamingWithRetry("key", "url", "model", messages, onDelta = chunks::add)
        }.exceptionOrNull()
        assertTrue(error is SocketTimeoutException)
        assertEquals(listOf("已收到正文"), chunks)
        verify(exactly = 1) { api.streamStoryCompletionWithUsage(any(), any(), any(), any()) }
    }

    @Test
    fun cancellationPropagatesWithoutRetry() = runTest {
        val cancelled = CancellationException("stop")
        every { api.streamStoryCompletionWithUsage(any(), any(), any(), any()) } returns flow { throw cancelled }
        val error = runCatching {
            retry.chatCompletionStreamingWithRetry("key", "url", "model", messages)
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(cancelled.message, error?.message)
        verify(exactly = 1) { api.streamStoryCompletionWithUsage(any(), any(), any(), any()) }
    }

    @Test
    fun successful正文SurvivesBillingFailure() = runTest {
        every { api.streamStoryCompletionWithUsage(any(), any(), any(), any()) } returns flow { emit("正文") }
        coEvery { costs.recordLlm(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws IllegalStateException("db")
        assertEquals("正文", retry.chatCompletionStreamingWithRetry("key", "url", "model", messages))
        verify(exactly = 1) { api.streamStoryCompletionWithUsage(any(), any(), any(), any()) }
    }

    @Test
    fun originalFailureSurvivesFailureBillingFailure() = runTest {
        val original = LlmHttpException(400)
        every { api.streamStoryCompletionWithUsage(any(), any(), any(), any()) } returns flow { throw original }
        coEvery { costs.recordLlm(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws IllegalStateException("db")
        val error = runCatching {
            retry.chatCompletionStreamingWithRetry("key", "url", "model", messages)
        }.exceptionOrNull()
        assertSame(original, error)
        verify(exactly = 1) { api.streamStoryCompletionWithUsage(any(), any(), any(), any()) }
    }
}
