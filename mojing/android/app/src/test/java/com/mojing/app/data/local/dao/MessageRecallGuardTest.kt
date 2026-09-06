package com.mojing.app.data.local.dao

import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionBranchEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MessageRecallGuardTest {
    private val queries = mockk<MessageDao>(relaxed = true)
    private val owner = object : MessageDao by queries {
        override suspend fun previewRecallInSession(sessionId: Long, id: Long) = super<MessageDao>.previewRecallInSession(sessionId, id)
        override suspend fun recallInSession(sessionId: Long, id: Long) = super<MessageDao>.recallInSession(sessionId, id)
        override suspend fun delete(id: Long) = super<MessageDao>.delete(id)
    }
    private val target = MessageEntity(id = 8, sessionId = 42, branchId = "main", content = "原文")

    @Test fun sourceProtectionRunsBeforeRecallDeletesAnything() = runTest {
        coEvery { queries.getByIdInSession(8, 42) } returns target
        coEvery { queries.countRecallReferences(42, listOf(8)) } returns 1
        coEvery { queries.getRecallReferences(42, listOf(8)) } returns listOf(SessionBranchEntity(sessionId = 42, branchId = "A", sourceMessageId = 8, label = "另一种结局"))
        val impact = owner.previewRecallInSession(42, 8)
        assertFalse(impact.canRecall)
        assertEquals("另一种结局", impact.references.single().label)
        try { owner.recallInSession(42, 8); fail("source must be preserved") } catch (_: MessageRecallBlockedException) { }
        coVerify(exactly = 0) { queries.deleteRaw(any()) }
        coVerify(exactly = 0) { queries.deleteMemorySegmentsCovering(any(), any()) }
    }

    @Test fun referencedDerivedMediaProtectsTheWholeRecall() = runTest {
        val child = target.copy(id = 9, parentMessageId = 8, includeInContext = false, structuredContentJson = """{"derived_media_version":1,"derived_media_kind":"image"}""")
        coEvery { queries.getByIdInSession(8, 42) } returns target
        coEvery { queries.getDerivedChildrenInSession(42, 8) } returns listOf(child)
        coEvery { queries.countRecallReferences(42, listOf(9, 8)) } returns 1
        assertFalse(owner.previewRecallInSession(42, 8).canRecall)
        try { owner.recallInSession(42, 8); fail("child source must be preserved") } catch (_: MessageRecallBlockedException) { }
        coVerify(exactly = 0) { queries.deleteRaw(any()) }
    }

    @Test fun editedVersionCannotExposeItsOldSourceAgain() = runTest {
        val edit = target.copy(id = 10, branchId = "edit-A", regeneratedFromMessageId = 8)
        coEvery { queries.getByIdInSession(10, 42) } returns edit
        coEvery { queries.getByIdInSession(8, 42) } returns target
        assertFalse(owner.previewRecallInSession(42, 10).canRecall)
        coEvery { queries.getById(10) } returns edit
        try { owner.delete(10); fail("raw owner must also protect edits") } catch (_: MessageRecallBlockedException) { }
        coVerify(exactly = 0) { queries.deleteRaw(any()) }
    }

    @Test fun sameStorylineLegacyReplyGroupKeepsItsExistingFallbackContract() = runTest {
        val variant = target.copy(id = 10, swipeGroupId = "reply", regeneratedFromMessageId = 8)
        coEvery { queries.getByIdInSession(10, 42) } returns variant
        coEvery { queries.getByIdInSession(8, 42) } returns target
        val impact = owner.previewRecallInSession(42, 10)
        assertTrue(impact.canRecall)
        assertTrue(impact.maySelectRemainingReply)
    }

    @Test fun mutationRechecksReferencesAfterAnAllowedPreview() = runTest {
        coEvery { queries.getByIdInSession(8, 42) } returns target
        assertTrue(owner.previewRecallInSession(42, 8).canRecall)
        coEvery { queries.countCrossBranchReplacements(42, listOf(8)) } returns 1
        try { owner.recallInSession(42, 8); fail("late reference must reject") } catch (_: MessageRecallBlockedException) { }
        coVerify(exactly = 0) { queries.deleteRaw(any()) }
    }

    @Test fun branchInsertionRejectsMissingOrForeignSourceBeforeWriting() = runTest {
        val branchQueries = mockk<SessionBranchDao>(relaxed = true)
        val branchOwner = object : SessionBranchDao by branchQueries {
            override suspend fun insert(entity: SessionBranchEntity) = super<SessionBranchDao>.insert(entity)
        }
        val branch = SessionBranchEntity(sessionId = 42, branchId = "A", sourceMessageId = 8)
        for (sourceSession in listOf(null, 99L)) {
            coEvery { branchQueries.sourceSessionId(8) } returns sourceSession
            try { branchOwner.insert(branch); fail("invalid source must reject") } catch (_: IllegalArgumentException) { }
        }
        coVerify(exactly = 0) { branchQueries.insertRaw(any()) }
    }
}
