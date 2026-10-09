package com.mojing.app.engine

import com.mojing.app.data.remote.ChatMessage
import com.mojing.app.data.remote.LlmApiService
import com.mojing.app.domain.billing.CostRecorder
import com.mojing.app.domain.engine.LlmRetry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NonStreamingCancellationTest {
    private val api = mockk<LlmApiService>()
    private val costs = mockk<CostRecorder>(relaxed = true)
    private val retry = LlmRetry(api, costs)
    private val messages = listOf(ChatMessage("user", "synthetic"))

    @Test fun activeRequestCancellationRecordsCancelledAndNeverRetries() = runTest {
        coEvery { api.chatCompletion(any(), any(), any()) } coAnswers { awaitCancellation() }
        val job = launch { retry.chatCompletionWithRetry("synthetic", "url", "model", messages) }
        runCurrent(); job.cancel(); job.join()
        coVerify(exactly = 1) { api.chatCompletion(any(), any(), any()) }
        coVerify(exactly = 1) { costs.recordLlm(null, null, "model", "llm_json", 0, 0, any(), false, any(), any(), any(), false, 0, "cancelled") }
    }

    @Test fun cancellationDuringRetryDelayPreventsAnotherPaidAttempt() = runTest {
        coEvery { api.chatCompletion(any(), any(), any()) } throws java.io.IOException("fixture disconnect")
        val job = launch { retry.chatCompletionWithRetry("synthetic", "url", "model", messages) }
        runCurrent(); job.cancel(); job.join()
        coVerify(exactly = 1) { api.chatCompletion(any(), any(), any()) }
    }

    @Test fun cancellationExceptionIsNotConvertedIntoRemoteFailure() = runTest {
        val stop = CancellationException("stop")
        coEvery { api.chatCompletion(any(), any(), any()) } throws stop
        assertSame(stop, runCatching { retry.chatCompletionWithRetry("synthetic", "url", "model", messages) }.exceptionOrNull())
        coVerify(exactly = 1) { api.chatCompletion(any(), any(), any()) }
    }
}
