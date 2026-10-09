package com.mojing.app.engine

import com.mojing.app.data.local.dao.*
import com.mojing.app.data.local.entity.*
import com.mojing.app.data.remote.*
import com.mojing.app.domain.billing.CostRecorder
import com.mojing.app.domain.engine.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PostMaintenanceContextBudgetTest {
    @Test fun ucmUpdateAndRebuildRejectWithoutReplacingExistingMemory() = runTest {
        val api = mockk<LlmApiService>()
        val costs = mockk<CostRecorder>(relaxed = true)
        val dao = mockk<SessionContextMemoryDao>(relaxed = true)
        val messages = mockk<MessageDao>()
        val existing = SessionContextMemoryEntity(sessionId = 7, branchId = "main", globalSummary = "既有记忆", revision = 4, sourceEndMessageId = 1)
        coEvery { dao.getBySessionAndBranch(7, "main") } returns existing
        coEvery { messages.getNextStoryContextBatch(7, "main", any(), any()) } returns listOf(MessageEntity(id = 2, sessionId = 7, content = "原文"))
        val manager = UniversalContextMemoryManager(dao, messages, LlmRetry(api, costs))
        assertEquals(UniversalContextMemoryUpdateResult.FAILED, manager.updateAfterMessages(7, "main", 4, "synthetic", "https://example.test", "model", "完整世界".repeat(2000), listOf("甲"), contextWindow = 3200))
        assertFalse(manager.rebuild(7, "main", 4, "synthetic", "https://example.test", "model", "世界", listOf("甲"), contextWindow = 3200))
        verify { api wasNot Called; costs wasNot Called }
        coVerify(exactly = 0) { dao.replaceIfRevisionMatches(any(), any()) }
        assertEquals("既有记忆", existing.globalSummary)
        assertEquals(1L, existing.sourceEndMessageId)
    }

    @Test fun ucmLaterPageRejectionDoesNotCommitEarlierStagedResult() = runTest {
        val api = mockk<LlmApiService>()
        val requests = mutableListOf<ChatRequest>()
        coEvery { api.chatCompletion(any(), any(), capture(requests)) } returns ChatCompletionResult("""{"globalSummary":"${"新摘要".repeat(4000)}"}""")
        val dao = mockk<SessionContextMemoryDao>(relaxed = true)
        coEvery { dao.getBySessionAndBranch(7, "main") } returns SessionContextMemoryEntity(sessionId = 7, globalSummary = "旧记忆", revision = 4)
        val messages = mockk<MessageDao>()
        coEvery { messages.getNextStoryContextBatch(7, "main", 0, any()) } returns (1L..41L).map { MessageEntity(id = it, sessionId = 7, content = "短原文") }
        val manager = UniversalContextMemoryManager(dao, messages, LlmRetry(api, mockk(relaxed = true)))
        assertEquals(UniversalContextMemoryUpdateResult.FAILED, manager.updateAfterMessages(7, "main", 4, "synthetic", "https://example.test", "model", "世界", listOf("甲"), contextWindow = 6500))
        assertEquals(1, requests.size)
        assertEquals(3200, requests.single().max_tokens)
        coVerify(exactly = 0) { dao.replaceIfRevisionMatches(any(), any()) }
    }

    @Test fun eventAndSedimentRejectionSkipWritesAndUnknownStillSendsOriginalOutputs() = runTest {
        val api = mockk<LlmApiService>()
        val costs = mockk<CostRecorder>(relaxed = true)
        val retry = LlmRetry(api, costs)
        val messages = mockk<MessageDao>(relaxed = true)
        val source = listOf(MessageEntity(id = 2, sessionId = 7, content = "完整原文"))
        val store = mockk<SedimentStore>(relaxed = true)
        coEvery { store.read(9, 7, "main", source) } returns SedimentSnapshot(9, 7, "main", 4, source)
        val events = MemoryV2Manager(retry, messages)
        val sediment = SedimentEngine(retry, store)
        assertTrue(events.extractEventNodes(7, "main", null, source, "synthetic", "https://example.test", "model", contextWindow = 1000).isEmpty())
        sediment.sedimentFromMessages(9, 7, "main", source, "synthetic", "https://example.test", "model", contextWindow = 2000)
        verify { api wasNot Called; costs wasNot Called }
        coVerify(exactly = 0) { messages.commitDerivedEvents(any(), any(), any(), any()); store.commit(any(), any()) }
        val requests = mutableListOf<ChatRequest>()
        coEvery { api.chatCompletion(any(), any(), capture(requests)) } returns ChatCompletionResult("[]")
        for (window in listOf(null, 8000)) {
            events.extractEventNodes(7, "main", null, source, "synthetic", "https://example.test", "model", contextWindow = window)
            sediment.sedimentFromMessages(9, 7, "main", source, "synthetic", "https://example.test", "model", contextWindow = window)
        }
        assertEquals(requests[0], requests[2]); assertEquals(requests[1], requests[3])
        assertEquals(listOf(1000, 2000, 1000, 2000), requests.map { it.max_tokens })
        assertEquals("完整原文", source.single().content)
    }
}
