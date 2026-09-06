package com.mojing.app.data.local.dao

import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionEventNodeEntity
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DerivedEventCommitTest {
    private val queries = mockk<MessageDao>(relaxed = true)
    private val owner = object : MessageDao by queries {
        override suspend fun commitDerivedEvents(sessionId: Long, branchId: String, sources: List<MessageEntity>, events: List<SessionEventNodeEntity>) =
            super<MessageDao>.commitDerivedEvents(sessionId, branchId, sources, events)
    }
    private val source = MessageEntity(id = 8, sessionId = 42, content = "原始剧情")
    private val event = SessionEventNodeEntity(sessionId = 42, messageId = 8, title = "发现钥匙")

    @Test fun removedOrEditedOrDeselectedSourceRejectsBeforeInsertion() = runTest {
        for (current in listOf(emptyList(), listOf(source.copy(content = "改写剧情")), listOf(source.copy(structuredContentJson = "{\"speech\":\"新对白\"}")))) {
            coEvery { queries.getMainEventSources(42, listOf(8)) } returns current
            assertTrue(owner.commitDerivedEvents(42, "main", listOf(source), listOf(event)).isEmpty())
        }
        coVerify(exactly = 0) { queries.insertDerivedEvent(any()) }
    }

    @Test fun branchContextIsRecheckedAndEquivalentEventsAreNotDuplicated() = runTest {
        coEvery { queries.getVisibleEventSources(42, "A", listOf(8)) } returns listOf(source)
        coEvery { queries.findEquivalentEvent(42, "A", null, 8, "发现钥匙") } returns 90L
        val result = owner.commitDerivedEvents(42, "A", listOf(source), listOf(event.copy(branchId = "A")))
        assertEquals(90L, result.single().id)
        coVerify(exactly = 0) { queries.getMainEventSources(any(), any()) }
        coVerify(exactly = 0) { queries.insertDerivedEvent(any()) }
    }

    @Test fun invalidEventScopeIsRejectedBeforeWriting() = runTest {
        var rejected = false
        try { owner.commitDerivedEvents(42, "main", listOf(source), listOf(event.copy(sessionId = 99))) }
        catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        coVerify(exactly = 0) { queries.insertDerivedEvent(any()) }
    }
}
