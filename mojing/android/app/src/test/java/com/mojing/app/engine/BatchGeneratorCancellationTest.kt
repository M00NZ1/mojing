package com.mojing.app.engine

import com.mojing.app.domain.engine.BatchGenerator
import com.mojing.app.domain.engine.LlmRetry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BatchGeneratorCancellationTest {
    private val retry = mockk<LlmRetry>()
    private val generator = BatchGenerator(retry)
    private fun response(error: Exception) {
        coEvery { retry.chatCompletionWithRetry(any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws error
    }
    @Test fun entriesRethrowCancellationWithoutReturningEmptyResult() = runTest {
        val stop = CancellationException("stop"); response(stop)
        assertSame(stop, runCatching { generator.generateBatch("synthetic", "url", "model", 1, "location", 1) }.exceptionOrNull())
        coVerify(exactly = 1) { retry.chatCompletionWithRetry(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }
    @Test fun timelineRethrowsCancellationWithoutReturningEmptyResult() = runTest {
        val stop = CancellationException("stop"); response(stop)
        assertSame(stop, runCatching { generator.generateTimelineEventsBatch("synthetic", "url", "model", 1, 1) }.exceptionOrNull())
    }
    @Test fun ordinaryFailureKeepsExistingEmptyResultContract() = runTest {
        response(IllegalStateException("bad result"))
        assertTrue(generator.generateBatch("synthetic", "url", "model", 1, "location", 1).isEmpty())
        assertTrue(generator.generateTimelineEventsBatch("synthetic", "url", "model", 1, 1).isEmpty())
    }
}
