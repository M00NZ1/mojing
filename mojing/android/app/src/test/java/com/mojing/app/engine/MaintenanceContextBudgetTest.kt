package com.mojing.app.engine

import com.mojing.app.data.local.entity.*
import com.mojing.app.data.remote.*
import com.mojing.app.domain.billing.CostRecorder
import com.mojing.app.domain.engine.*
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MaintenanceContextBudgetTest {
    @Test fun finalMaintenanceInputAndUnclampedOutputRejectBeforeBillingNetworkAndRetry() = runTest {
        val api = mockk<LlmApiService>()
        val costs = mockk<CostRecorder>(relaxed = true)
        val retry = LlmRetry(api, costs)
        val messages = listOf(ChatMessage("system", "全部系统资料".repeat(100)), ChatMessage("user", "全部维护正文".repeat(100)))
        val error = runCatching { retry.chatCompletionWithRetry("synthetic", "https://example.test", "model", messages,
            maxTokens = 2000, maxRetries = 5, contextWindow = 1000) }.exceptionOrNull()
        assertTrue(error is RequestContextLimitException)
        assertEquals(2000, (error as RequestContextLimitException).limit.outputTokens)
        assertEquals(RequestContextBudget.estimateInput(messages), error.limit.estimatedInput)
        verify { api wasNot Called; costs wasNot Called }
    }

    @Test fun configuredCapacityAndUnknownSendIdenticalFinalMessagesAndOutput() = runTest {
        val api = mockk<LlmApiService>()
        val requests = mutableListOf<ChatRequest>()
        coEvery { api.chatCompletion(any(), any(), capture(requests)) } returns ChatCompletionResult("完整结果")
        val retry = LlmRetry(api, mockk(relaxed = true))
        val messages = listOf(ChatMessage("system", "完整规则"), ChatMessage("user", "完整资料"))
        val capacity = RequestContextBudget.estimateInput(messages).toInt() + 2000
        for (window in listOf(null, capacity)) assertEquals("完整结果", retry.chatCompletionWithRetry("synthetic",
            "https://example.test", "model", messages, maxTokens = 2000, jsonOutput = true, contextWindow = window))
        assertEquals(requests.first(), requests.last())
        assertEquals(messages, requests.last().messages)
        assertEquals(2000, requests.last().max_tokens)
    }

    @Test fun snapshotRejectionReturnsNullAndUnknownStillExtractsWithoutChangingMessages() = runTest {
        val api = mockk<LlmApiService>()
        val costs = mockk<CostRecorder>(relaxed = true)
        val extractor = CharacterSnapshotExtractor(LlmRetry(api, costs))
        val messages = listOf(MessageEntity(id = 1, sessionId = 1, content = "当前事实".repeat(100)))
        val character = CharacterEntity(name = "甲")
        assertNull(extractor.extract(messages, character, "synthetic", "https://example.test", "model", contextWindow = 2000))
        verify { api wasNot Called; costs wasNot Called }
        coEvery { api.chatCompletion(any(), any(), any()) } returns ChatCompletionResult("""{"mood":"保持既有连续性"}""")
        assertEquals("保持既有连续性", extractor.extract(messages, character, "synthetic", "https://example.test", "model")!!.mood)
        coVerify(exactly = 1) { api.chatCompletion(any(), any(), match { it.max_tokens == 2000 }) }
        coEvery { api.chatCompletion(any(), any(), any()) } throws CancellationException("cancelled")
        assertTrue(runCatching { extractor.extract(messages, character, "synthetic", "https://example.test", "model") }.exceptionOrNull() is CancellationException)
    }

    @Test fun compactionRejectionPreservesSnapshotCheckpointAndOriginalRows() = runTest {
        val api = mockk<LlmApiService>()
        val costs = mockk<CostRecorder>(relaxed = true)
        val store = mockk<MemoryCompactionStore>(relaxed = true)
        val source = MessageEntity(id = 2, sessionId = 1, content = "完整原文".repeat(2000))
        val base = MemoryCompactionSnapshot(1, "main", emptyList(), 0, listOf(source), 1)
        val checkpoint = MemoryCompactionCheckpoint(sourceFingerprint = base.sourceFingerprint(), nextChunkIndex = 1, carriedSummary = "先前压缩恢复点")
        val snapshot = base.copy(checkpoint = checkpoint)
        coEvery { store.read(1, "main", 1) } returns snapshot
        val compactor = MemoryCompactor(LlmRetry(api, costs), store)
        assertFalse(compactor.compactIfNeeded(1, "main", "synthetic", "https://example.test", "model", threshold = 1, contextWindow = 600))
        verify { api wasNot Called; costs wasNot Called }
        coVerify(exactly = 0) { store.commit(any(), any()) }
        coVerify(exactly = 0) { store.saveCheckpoint(any(), any()) }
        assertEquals("先前压缩恢复点", snapshot.checkpoint!!.carriedSummary)
        assertEquals("完整原文".repeat(2000), snapshot.sources.single().content)
    }
}
