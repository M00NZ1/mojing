package com.mojing.app.engine

import com.mojing.app.data.local.dao.*
import com.mojing.app.data.local.entity.*
import com.mojing.app.domain.engine.SedimentSnapshot
import com.mojing.app.domain.engine.SedimentStore
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SedimentStoreTest {
    private val messages = mockk<MessageDao>(relaxed = true)
    private val worlds = mockk<SessionWorldDao>(relaxed = true)
    private val encyclopedias = mockk<EncyclopediaDao>(relaxed = true)
    private val entries = mockk<EncyclopediaEntryDao>(relaxed = true)
    private val source = MessageEntity(id = 3, sessionId = 7, content = "新线索")
    private val snapshot = SedimentSnapshot(9, 7, "main", 0, listOf(source))
    private val result = EncyclopediaEntryEntity(encyclopediaId = 9, title = "沈照", entryType = "character", content = "新的推断")

    private fun prepare() {
        coEvery { worlds.getBySession(7) } returns SessionWorldEntity(sessionId = 7, encyclopediaId = 9, autoSedimentEnabled = true)
        coEvery { encyclopedias.getById(9) } returns EncyclopediaEntity(id = 9, name = "雾港")
        coEvery { messages.getMainEventSources(7, listOf(3)) } returns listOf(source)
        coEvery { entries.findSedimentDuplicate(any(), any(), any(), any(), any()) } returns null
    }

    @Test fun characterFactsBecomeNewInferredEntriesWithBranchSources() = runTest {
        prepare()
        val saved = slot<EncyclopediaEntryEntity>()
        coEvery { entries.upsert(capture(saved)) } returns 10
        assertEquals(1, SedimentStore.persistValidated(snapshot, listOf(result), messages, worlds, encyclopedias, entries))
        assertEquals(0L, saved.captured.id)
        assertEquals("inferred", saved.captured.confidence)
        assertEquals(7L, saved.captured.sourceSessionId)
        assertEquals(3L, saved.captured.sourceMessageId)
        assertTrue(saved.captured.metaJson.contains("\"source_branch_id\":\"main\""))
        assertTrue(saved.captured.metaJson.contains("\"source_message_fingerprints\""))
        assertFalse(saved.captured.metaJson.contains("linkedCharacterId"))
    }

    @Test fun changedSourcesRevisionOrWorldSettingsRejectLateResults() = runTest {
        prepare()
        coEvery { messages.getMainEventSources(7, listOf(3)) } returns listOf(source.copy(content = "已编辑"))
        assertEquals(0, SedimentStore.persistValidated(snapshot, listOf(result), messages, worlds, encyclopedias, entries))
        coEvery { messages.getMainEventSources(7, listOf(3)) } returns listOf(source)
        coEvery { messages.getContextMemoryForInvalidation(7, "main") } returns SessionContextMemoryEntity(sessionId = 7, revision = 1)
        assertEquals(0, SedimentStore.persistValidated(snapshot, listOf(result), messages, worlds, encyclopedias, entries))
        coEvery { messages.getContextMemoryForInvalidation(7, "main") } returns null
        coEvery { worlds.getBySession(7) } returns SessionWorldEntity(sessionId = 7, encyclopediaId = 10, autoSedimentEnabled = true)
        assertEquals(0, SedimentStore.persistValidated(snapshot, listOf(result), messages, worlds, encyclopedias, entries))
        coEvery { worlds.getBySession(7) } returns SessionWorldEntity(sessionId = 7, encyclopediaId = 9, autoSedimentEnabled = false)
        assertEquals(0, SedimentStore.persistValidated(snapshot, listOf(result), messages, worlds, encyclopedias, entries))
        coVerify(exactly = 0) { entries.upsert(any()) }
    }

    @Test fun duplicatesAreNotWrittenAgainAndFailureEscapesTransaction() = runTest {
        prepare()
        coEvery { entries.findSedimentDuplicate(any(), any(), any(), any(), any()) } returns 11
        assertEquals(0, SedimentStore.persistValidated(snapshot, listOf(result), messages, worlds, encyclopedias, entries))
        coVerify(exactly = 0) { entries.upsert(any()) }
        coEvery { entries.findSedimentDuplicate(any(), any(), any(), any(), any()) } returns null
        coEvery { entries.upsert(any()) } throws IllegalStateException("write failed")
        try { SedimentStore.persistValidated(snapshot, listOf(result), messages, worlds, encyclopedias, entries); fail("must abort") }
        catch (_: IllegalStateException) { }
    }

    @Test fun legacyVersionOneDuplicateIsSkippedAfterMetadataUpgrade() = runTest {
        prepare()
        coEvery { entries.findSedimentDuplicate(any(), any(), any(), any(), any()) } answers {
            if (arg<String>(4).contains("\"sediment_version\":1")) 17L else null
        }
        assertEquals(0, SedimentStore.persistValidated(snapshot, listOf(result), messages, worlds, encyclopedias, entries))
        coVerify(exactly = 0) { entries.upsert(any()) }
        coVerify(exactly = 1) { entries.findSedimentDuplicate(any(), any(), any(), any(), match { it.contains("\"sediment_version\":1") }) }
    }

    @Test fun branchUsesItsOwnVisibleSources() = runTest {
        prepare()
        coEvery { messages.getVisibleEventSources(7, "A", listOf(3)) } returns emptyList()
        assertEquals(0, SedimentStore.persistValidated(snapshot.copy(branchId = "A"), listOf(result), messages, worlds, encyclopedias, entries))
        coVerify(exactly = 0) { entries.upsert(any()) }
    }
}
