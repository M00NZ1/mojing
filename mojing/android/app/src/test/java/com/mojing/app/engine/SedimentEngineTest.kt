package com.mojing.app.engine

import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.domain.engine.*
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SedimentEngineTest {
    private val llm = mockk<LlmRetry>()
    private val store = mockk<SedimentStore>(relaxed = true)
    private val engine = SedimentEngine(llm, store)
    private val source = listOf(MessageEntity(id = 3, sessionId = 7, content = "线索"))

    private fun prepare() {
        coEvery { store.read(9, 7, "A", source) } returns SedimentSnapshot(9, 7, "A", 0, source)
    }

    @Test fun invalidLaterItemPreventsAllWrites() = runTest {
        prepare()
        coEvery { llm.chatCompletionWithRetry(any(), any(), any(), any(), any(), any(), any()) } returns """[{"title":"有效","content":"线索"},{"title":"空内容"}]"""
        engine.sedimentFromMessages(9, 7, "A", source, "key", "url", "model")
        coVerify(exactly = 0) { store.commit(any(), any()) }
    }

    @Test fun validBatchUsesOneCommit() = runTest {
        prepare()
        coEvery { llm.chatCompletionWithRetry(any(), any(), any(), any(), any(), any(), any()) } returns """[{"title":"甲","content":"事实一"},{"title":"乙","content":"事实二","entry_type":"character"}]"""
        engine.sedimentFromMessages(9, 7, "A", source, "key", "url", "model")
        coVerify(exactly = 1) { store.commit(match { it.branchId == "A" }, match { it.size == 2 }) }
    }

    @Test fun cancellationPropagatesWithoutSaving() = runTest {
        prepare()
        coEvery { llm.chatCompletionWithRetry(any(), any(), any(), any(), any(), any(), any()) } throws CancellationException("stop")
        try { engine.sedimentFromMessages(9, 7, "A", source, "key", "url", "model"); fail("must propagate") }
        catch (_: CancellationException) { }
        coVerify(exactly = 0) { store.commit(any(), any()) }
    }
}
