package com.mojing.app.ui.encyclopedia

import com.mojing.app.data.SecureStorage
import com.mojing.app.data.EntryDraftSnapshot
import com.mojing.app.data.EntryEditDraftStore
import com.mojing.app.data.local.dao.EncyclopediaDao
import com.mojing.app.data.local.dao.EncyclopediaEntryDao
import com.mojing.app.data.local.dao.EntryVersionDao
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.EncyclopediaEntryEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.repository.ImageRepository
import com.mojing.app.domain.engine.AiCompleter
import com.mojing.app.domain.encyclopedia.messageSourceFingerprint
import com.mojing.app.domain.usecase.SaveCharacterEntryUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EntryEditViewModelTest {
    private fun edge(id: Long) = com.mojing.app.data.local.dao.EntryRelatedItem(id, 8L, id + 100L,
        "守护", "备注", id + 100L, "条目$id", "location")

    @Test fun relatedPagesRetainOnly24AndRetryTheFailedCursor() = runTest(dispatcher) {
        val entries = mockk<EncyclopediaEntryDao> { coEvery { getById(8L) } returns EncyclopediaEntryEntity(id = 8L, encyclopediaId = 3L) }
        val relations = mockk<com.mojing.app.data.local.dao.EntryRelationDao>()
        coEvery { relations.getEntryPage(3L, 8L, Long.MAX_VALUE, 25) } returns (50L downTo 26L).map(::edge)
        coEvery { relations.getEntryPage(3L, 8L, 27L, 25) } throws IllegalStateException("read")
        val vm = createViewModel(encyclopediaDao(), entries, relationDao = relations)
        vm.load(3L, 8L); vm.refreshRelatedEntries(); advanceUntilIdle()
        assertEquals(24, vm.state.value.relatedEntries.size); assertTrue(vm.state.value.relatedHasNext)
        vm.nextRelatedPage(); advanceUntilIdle()
        assertEquals(0, vm.state.value.relatedPageIndex); assertNotNull(vm.state.value.relatedError)
        coEvery { relations.getEntryPage(3L, 8L, 27L, 25) } returns listOf(edge(26L))
        vm.retryRelatedPage(); advanceUntilIdle()
        assertEquals(listOf(26L), vm.state.value.relatedEntries.map { it.id }); assertEquals(1, vm.state.value.relatedPageIndex)
        assertFalse(vm.state.value.relatedHasNext)
        vm.previousRelatedPage(); advanceUntilIdle(); assertEquals(24, vm.state.value.relatedEntries.size)
    }

    @Test fun relatedBoundaryCountsAndZeroEntryNeverQueryRelations() = runTest(dispatcher) {
        val entries = mockk<EncyclopediaEntryDao> { coEvery { getById(8L) } returns EncyclopediaEntryEntity(id = 8L, encyclopediaId = 3L) }
        val relations = mockk<com.mojing.app.data.local.dao.EntryRelationDao>(relaxed = true)
        val vm = createViewModel(encyclopediaDao(), entries, relationDao = relations)
        vm.load(3L, 0L); vm.refreshRelatedEntries(); advanceUntilIdle()
        coVerify(exactly = 0) { relations.getEntryPage(any(), any(), any(), any()) }
        vm.load(3L, 8L)
        for (count in listOf(0, 1, 23, 24, 25)) {
            coEvery { relations.getEntryPage(3L, 8L, Long.MAX_VALUE, 25) } returns (1L..count.toLong()).map(::edge)
            vm.refreshRelatedEntries(); advanceUntilIdle()
            assertEquals(count.coerceAtMost(24), vm.state.value.relatedEntries.size)
            assertEquals(count > 24, vm.state.value.relatedHasNext)
        }
    }

    @Test fun lateRelationsCannotReplaceAnotherEntry() = runTest(dispatcher) {
        val entries = mockk<EncyclopediaEntryDao> {
            coEvery { getById(any()) } answers { EncyclopediaEntryEntity(id = firstArg(), encyclopediaId = 3L) }
        }
        val deferred = CompletableDeferred<List<com.mojing.app.data.local.dao.EntryRelatedItem>>()
        val relations = mockk<com.mojing.app.data.local.dao.EntryRelationDao>()
        coEvery { relations.getEntryPage(3L, 8L, any(), 25) } coAnswers {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { deferred.await() }
        }
        coEvery { relations.getEntryPage(3L, 9L, any(), 25) } returns listOf(edge(9L))
        val vm = createViewModel(encyclopediaDao(), entries, relationDao = relations)
        vm.load(3L, 8L);vm.refreshRelatedEntries()
        vm.load(3L, 9L);vm.refreshRelatedEntries()
        deferred.complete(listOf(edge(8L)));advanceUntilIdle()
        assertEquals(9L, vm.state.value.persistedEntryId)
        assertEquals(listOf(9L), vm.state.value.relatedEntries.map { it.id })
    }

    @Test fun discardedEditorCanReturnFromManagementWithoutStaleDirtyOrBusyState() = runTest(dispatcher) {
        val entries = mockk<EncyclopediaEntryDao> { coEvery { getById(8L) } returns EncyclopediaEntryEntity(id = 8L, encyclopediaId = 3L, content = "已保存正文") }
        val vm = createViewModel(encyclopediaDao(), entries)
        vm.load(3L, 8L);vm.updateContent("应放弃正文")
        var left = false;vm.discardChangesAndLeave { left = true };advanceUntilIdle()
        assertTrue(left);assertEquals("已保存正文", vm.state.value.content)
        assertFalse(vm.state.value.isDirty);assertFalse(vm.state.value.isDiscardingDraft)
        vm.updateContent("回来继续编辑");assertTrue(vm.state.value.isDirty)
    }

    @Test fun directNewEntryRestoresDraftBeforeAcceptingInput() = runTest(dispatcher) {
        val store = mockk<EntryEditDraftStore>(relaxed = true) {
            coEvery { load(3L, 0L) } returns EntryDraftSnapshot(title = "旧开篇", content = "未保存正文")
        }
        val vm = createViewModel(encyclopediaDao(), mockk(relaxed = true), draftStore = store)
        vm.load(3L, 0L)
        assertNotNull(vm.state.value.recoverableDraft)
        vm.updateContent("覆盖正文")
        assertEquals("", vm.state.value.content)
        vm.restoreDraft()
        advanceUntilIdle()
        assertEquals("旧开篇", vm.state.value.title)
        assertEquals("未保存正文", vm.state.value.content)
        coVerify { store.save(3L, 0L, match { it.content == "未保存正文" }) }
        var left = false
        vm.discardChangesAndLeave { left = true }
        advanceUntilIdle()
        assertTrue(left)
        coVerify { store.clear(3L, 0L) }
    }

    @Test fun firstSaveMovesDirectNewDraftAndRetryDoesNotCreateAnotherEntry() = runTest(dispatcher) {
        val saved = EncyclopediaEntryEntity(id = 12L, encyclopediaId = 3L, title = "潮汐钟")
        val store = mockk<EntryEditDraftStore>(relaxed = true) { coEvery { load(3L, 0L) } returns null }
        var transfers = 0
        coEvery { store.syncAfterFirstSave(3L, 12L, null) } coAnswers {
            if (++transfers == 1) throw IllegalStateException("write")
        }
        val saver = mockk<SaveCharacterEntryUseCase> { coEvery { saveEdited(any()) } returns saved }
        val versions = mockk<EntryVersionDao> { coEvery { getPage(12L, any(), any()) } returns emptyList() }
        val vm = createViewModel(encyclopediaDao(), mockk(relaxed = true), versions, saver, draftStore = store)
        vm.load(3L, 0L)
        vm.updateTitle("潮汐钟")
        vm.save()
        advanceUntilIdle()
        assertTrue(vm.state.value.isPersisted)
        assertNotNull(vm.state.value.draftError)
        vm.retryDraftSave()
        advanceUntilIdle()
        assertEquals(null, vm.state.value.draftError)
        assertEquals(2, transfers)
        coVerify(exactly = 1) { saver.saveEdited(any()) }
    }

    @Test fun firstSaveTransfersEditsMadeWhileDatabaseWriteWasPending() = runTest(dispatcher) {
        val saved = EncyclopediaEntryEntity(id = 12L, encyclopediaId = 3L, title = "潮汐钟")
        val gate = CompletableDeferred<Unit>()
        val saver = mockk<SaveCharacterEntryUseCase> {
            coEvery { saveEdited(any()) } coAnswers { gate.await(); saved }
        }
        val store = mockk<EntryEditDraftStore>(relaxed = true) { coEvery { load(3L, 0L) } returns null }
        val versions = mockk<EntryVersionDao> { coEvery { getPage(12L, any(), any()) } returns emptyList() }
        val vm = createViewModel(encyclopediaDao(), mockk(relaxed = true), versions, saver, draftStore = store)
        vm.load(3L, 0L)
        vm.updateTitle("潮汐钟")
        vm.save()
        vm.updateContent("保存期间新写的正文")
        vm.updateCoverPromptHint("保存期间的新生图提示")
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.state.value.isDirty)
        assertEquals(12L, vm.state.value.persistedEntryId)
        assertEquals("保存期间新写的正文", vm.state.value.content)
        coVerify { store.syncAfterFirstSave(3L, 12L, match {
            it.content == "保存期间新写的正文" && it.coverPromptHint == "保存期间的新生图提示"
        }) }
    }

    @Test fun waitsForRecoveryChoiceBeforeEditing() = runTest(dispatcher) {
        val entry = EncyclopediaEntryEntity(id = 8L, encyclopediaId = 3L, title = "已保存")
        val dao = mockk<EncyclopediaEntryDao> { coEvery { getById(8L) } returns entry }
        val pending = CompletableDeferred<EntryDraftSnapshot?>()
        val store = mockk<EntryEditDraftStore>(relaxed = true) {
            coEvery { load(3L, 8L) } coAnswers { pending.await() }
        }
        val vm = createViewModel(encyclopediaDao(), dao, draftStore = store)
        vm.load(3L, 8L)
        assertFalse(vm.state.value.isLoaded)
        vm.updateContent("读取期间输入")
        assertEquals("", vm.state.value.content)
        pending.complete(EntryDraftSnapshot(title = "未保存", content = "长篇草稿"))
        advanceUntilIdle()
        assertTrue(vm.state.value.isLoaded)
        assertEquals("已保存", vm.state.value.title)
        assertNotNull(vm.state.value.recoverableDraft)
        vm.restoreDraft()
        advanceUntilIdle()
        assertEquals("未保存", vm.state.value.title)
        assertEquals("长篇草稿", vm.state.value.content)
        coVerify { store.save(3L, 8L, match { it.content == "长篇草稿" }) }
    }

    @Test fun unreadableDraftStaysProtectedUntilExplicitDiscard() = runTest(dispatcher) {
        val entry = EncyclopediaEntryEntity(id = 8L, encyclopediaId = 3L, title = "已保存")
        val dao = mockk<EncyclopediaEntryDao> { coEvery { getById(8L) } returns entry }
        val store = mockk<EntryEditDraftStore>(relaxed = true) {
            coEvery { load(3L, 8L) } throws IllegalStateException("bad draft")
        }
        val vm = createViewModel(encyclopediaDao(), dao, draftStore = store)
        vm.load(3L, 8L)
        assertTrue(vm.state.value.draftUnreadable)
        vm.updateTitle("不可覆盖")
        assertEquals("已保存", vm.state.value.title)
        vm.discardStoredDraft()
        advanceUntilIdle()
        assertFalse(vm.state.value.draftUnreadable)
        coVerify(exactly = 1) { store.clear(3L, 8L) }
    }

    @Test fun saveKeepsOldDraftErrorVisibleUntilCleared() = runTest(dispatcher) {
        val entry = EncyclopediaEntryEntity(id = 8L, encyclopediaId = 3L, title = "旧标题")
        val dao = mockk<EncyclopediaEntryDao> { coEvery { getById(8L) } returns entry }
        val store = mockk<EntryEditDraftStore>(relaxed = true) {
            coEvery { load(3L, 8L) } returns null
            coEvery { clear(3L, 8L) } throws IllegalStateException("disk")
        }
        val saver = mockk<SaveCharacterEntryUseCase> {
            coEvery { this@mockk.saveEdited(any()) } answers { firstArg<EncyclopediaEntryEntity>() }
        }
        val vm = createViewModel(encyclopediaDao(), dao, saveEntry = saver, draftStore = store)
        vm.load(3L, 8L)
        vm.updateTitle("新标题")
        vm.save()
        advanceUntilIdle()
        assertFalse(vm.state.value.isDirty)
        assertNotNull(vm.state.value.draftError)
        var left = false
        vm.discardChangesAndLeave { left = true }
        advanceUntilIdle()
        assertFalse(left)
    }

    @Test fun confirmingConversationNoteKeepsSourcesAndRetriesFailedSave() = runTest(dispatcher) {
        val entry = EncyclopediaEntryEntity(id = 8, encyclopediaId = 3, title = "雾港线索", confidence = "inferred",
            sourceSessionId = 42, sourceMessageId = 9, metaJson = """{"source_message_ids":[6,9]}""")
        val dao = mockk<EncyclopediaEntryDao> { coEvery { getById(8) } returns entry }
        val saver = mockk<SaveCharacterEntryUseCase>()
        coEvery { saver.saveEdited(any()) } throws IllegalStateException("write")
        val vm = createViewModel(encyclopediaDao(), dao, saveEntry = saver)
        vm.load(3, 8)
        vm.updateConfidence("confirmed")
        vm.save()
        assertTrue(vm.state.value.isDirty)
        assertNotNull(vm.state.value.saveError)
        vm.consumeSnackbar()
        assertNotNull(vm.state.value.saveError)
        assertEquals("confirmed", vm.state.value.confidence)
        coEvery { saver.saveEdited(any()) } answers { firstArg<EncyclopediaEntryEntity>() }
        vm.save()
        assertFalse(vm.state.value.isDirty)
        assertEquals(null, vm.state.value.saveError)
        assertTrue(vm.state.value.isConversationNote)
        coVerify(exactly = 2) { saver.saveEdited(match {
            it.confidence == "confirmed" && it.sourceSessionId == 42L && it.sourceMessageId == 9L && it.metaJson == entry.metaJson
        }) }
    }

    @Test fun sourcePagesReadIndividuallyAndSkipMissingSource() = runTest(dispatcher) {
        val entry = EncyclopediaEntryEntity(id = 8, encyclopediaId = 3, sourceSessionId = 42, sourceMessageId = 9,
            metaJson = """{"source_message_ids":[6,7,9],"source_branch_id":"story"}""")
        val dao = mockk<EncyclopediaEntryDao> { coEvery { getById(8) } returns entry }
        val messages = mockk<com.mojing.app.data.local.dao.MessageDao>()
        coEvery { messages.getByIdInSession(any(), 42) } answers {
            val id = firstArg<Long>()
            if (id == 7L) null else com.mojing.app.data.local.entity.MessageEntity(id = id, sessionId = 42, content = "原文$id")
        }
        val vm = createViewModel(encyclopediaDao(), dao, messageDao = messages)
        vm.load(3, 8)
        vm.openSourcePreview()
        assertEquals(listOf(6L, 7L, 9L), vm.state.value.sourceMessageIds)
        assertEquals(2, vm.state.value.sourceIndex)
        coVerify(exactly = 0) { messages.getByIdInSession(6, 42) }
        vm.showSourceMessage(1)
        assertEquals(null, vm.state.value.sourceTarget)
        assertNotNull(vm.state.value.sourceError)
        vm.showSourceMessage(0)
        assertEquals("原文6", vm.state.value.sourceContent)
        assertEquals(EntrySourceTarget(42, 6, "story"), vm.state.value.sourceTarget)
        vm.showSourceMessage(-1)
        assertEquals(0, vm.state.value.sourceIndex)
        vm.closeSourcePreview()
        vm.showSourceMessage(2)
        assertFalse(vm.state.value.sourcePreviewOpen)
    }

    @Test fun versionedSourcePreviewMarksCurrentContent() = runTest(dispatcher) {
        val message = MessageEntity(id = 6, sessionId = 42, content = "当前原文")
        val fingerprint = messageSourceFingerprint(message)
        val entry = EncyclopediaEntryEntity(id = 8, encyclopediaId = 3, sourceSessionId = 42, sourceMessageId = 6,
            metaJson = """{"source_message_ids":[6],"source_branch_id":"main","source_fingerprint_version":1,"source_message_fingerprints":[{"message_id":6,"fingerprint":"$fingerprint"}]}""")
        val entries = mockk<EncyclopediaEntryDao> { coEvery { getById(8) } returns entry }
        val messages = mockk<com.mojing.app.data.local.dao.MessageDao>(relaxed = true)
        coEvery { messages.getMainEventSources(42, listOf(6)) } returns listOf(message)
        val vm = createViewModel(encyclopediaDao(), entries, messageDao = messages)
        vm.preparationDispatcher = dispatcher
        vm.load(3, 8); vm.openSourcePreview(); advanceUntilIdle()
        assertEquals(EntrySourceVerification.CURRENT, vm.state.value.sourceVerification)
        coVerify(exactly = 0) { messages.getByIdInSession(6, 42) }
    }

    @Test fun versionedSourcePreviewMarksChangedContent() = runTest(dispatcher) {
        val original = MessageEntity(id = 6, sessionId = 42, content = "原始版本")
        val current = original.copy(content = "已编辑版本")
        val entry = EncyclopediaEntryEntity(id = 8, encyclopediaId = 3, sourceSessionId = 42, sourceMessageId = 6,
            metaJson = """{"source_message_ids":[6],"source_fingerprint_version":1,"source_message_fingerprints":[{"message_id":6,"fingerprint":"${messageSourceFingerprint(original)}"}]}""")
        val entries = mockk<EncyclopediaEntryDao> { coEvery { getById(8) } returns entry }
        val messages = mockk<com.mojing.app.data.local.dao.MessageDao>(relaxed = true)
        coEvery { messages.getMainEventSources(42, listOf(6)) } returns listOf(current)
        val vm = createViewModel(encyclopediaDao(), entries, messageDao = messages)
        vm.preparationDispatcher = dispatcher
        vm.load(3, 8); vm.openSourcePreview(); advanceUntilIdle()
        assertEquals(EntrySourceVerification.CHANGED, vm.state.value.sourceVerification)
    }

    @Test fun versionedSourcePreviewMarksMessageOutsideCurrentContext() = runTest(dispatcher) {
        val message = MessageEntity(id = 6, sessionId = 42, content = "分支原文")
        val entry = EncyclopediaEntryEntity(id = 8, encyclopediaId = 3, sourceSessionId = 42, sourceMessageId = 6,
            metaJson = """{"source_message_ids":[6],"source_branch_id":"story","source_fingerprint_version":1,"source_message_fingerprints":[{"message_id":6,"fingerprint":"${messageSourceFingerprint(message)}"}]}""")
        val entries = mockk<EncyclopediaEntryDao> { coEvery { getById(8) } returns entry }
        val messages = mockk<com.mojing.app.data.local.dao.MessageDao>(relaxed = true)
        coEvery { messages.getVisibleEventSources(42, "story", listOf(6)) } returns emptyList()
        coEvery { messages.getByIdInSession(6, 42) } returns message
        val vm = createViewModel(encyclopediaDao(), entries, messageDao = messages)
        vm.preparationDispatcher = dispatcher
        vm.load(3, 8); vm.openSourcePreview(); advanceUntilIdle()
        assertEquals(EntrySourceVerification.NOT_IN_ORIGINAL_LINE, vm.state.value.sourceVerification)
        assertEquals(null, vm.state.value.sourceError)
    }

    @Test fun sourcePreviewKeepsDraftAndAllowsRetry() = runTest(dispatcher) {
        val entry = EncyclopediaEntryEntity(id = 8, encyclopediaId = 3, sourceSessionId = 42, sourceMessageId = 6,
            metaJson = """{"source_branch_id":"story-2"}""")
        val dao = mockk<EncyclopediaEntryDao> { coEvery { getById(8) } returns entry }
        val messages = mockk<com.mojing.app.data.local.dao.MessageDao>()
        coEvery { messages.getByIdInSession(6, 42) } throws IllegalStateException("read")
        val vm = createViewModel(encyclopediaDao(), dao, messageDao = messages)
        vm.load(3, 8)
        vm.updateTitle("未保存修改")
        assertTrue(vm.state.value.hasSourceMessage)
        vm.openSourcePreview()
        assertEquals("原文读取失败，请重试。", vm.state.value.sourceError)
        coEvery { messages.getByIdInSession(6, 42) } returns com.mojing.app.data.local.entity.MessageEntity(id = 6, sessionId = 42, content = "原始剧情")
        vm.openSourcePreview()
        assertEquals("原始剧情", vm.state.value.sourceContent)
        assertEquals(EntrySourceTarget(42, 6, "story-2"), vm.state.value.sourceTarget)
        vm.closeSourcePreview()
        assertFalse(vm.state.value.sourcePreviewOpen)
        assertEquals("未保存修改", vm.state.value.title)
        assertTrue(vm.state.value.isDirty)
        coEvery { messages.getByIdInSession(6, 42) } returns null
        vm.openSourcePreview()
        assertEquals("原始对话已不存在，百科内容仍保留。", vm.state.value.sourceError)
    }

    @Test fun closingSourcePreviewRejectsLateContent() = runTest(dispatcher) {
        val entry = EncyclopediaEntryEntity(id = 8, encyclopediaId = 3, sourceSessionId = 42, sourceMessageId = 6)
        val dao = mockk<EncyclopediaEntryDao> { coEvery { getById(8) } returns entry }
        val messages = mockk<com.mojing.app.data.local.dao.MessageDao>()
        val gate = CompletableDeferred<Unit>()
        coEvery { messages.getByIdInSession(6, 42) } coAnswers {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() }
            com.mojing.app.data.local.entity.MessageEntity(id = 6, sessionId = 42, content = "迟到原文")
        }
        val vm = createViewModel(encyclopediaDao(), dao, messageDao = messages)
        vm.load(3, 8)
        vm.openSourcePreview()
        vm.openSourcePreview()
        coVerify(exactly = 1) { messages.getByIdInSession(6, 42) }
        vm.closeSourcePreview()
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.state.value.sourcePreviewOpen)
        assertEquals(null, vm.state.value.sourceContent)
    }

    @Test
    fun loadingVersionRequiresDraftReplacementAndRejectsOtherEntries() = runTest(dispatcher) {
        val entry = EncyclopediaEntryEntity(id = 8, encyclopediaId = 3, title = "当前正文",
            entryType = "location", confidence = "pending", isFeatured = true, coverImagePath = "current-cover.png")
        val version = com.mojing.app.data.local.entity.EntryVersionEntity(id = 6, entryId = 8,
            title = "历史正文", summary = "历史摘要", content = "历史全文", tags = "历史标签",
            metaSnapshotJson = "{\"region\":\"旧港\"}")
        val dao = mockk<EncyclopediaEntryDao> { coEvery { getById(8) } returns entry }
        val versions = mockk<EntryVersionDao> { coEvery { getPage(8, any(), 11) } returns listOf(version) }
        val viewModel = createViewModel(encyclopediaDao(), dao, versions)
        viewModel.load(3, 8)
        viewModel.updateTitle("未保存正文")
        assertFalse(viewModel.applyVersionToForm(version))
        assertEquals("未保存正文", viewModel.state.value.title)
        assertEquals(version, viewModel.state.value.pendingVersion)
        viewModel.dismissVersionReplacement()
        assertEquals(null, viewModel.state.value.pendingVersion)
        assertFalse(viewModel.applyVersionToForm(version.copy(entryId = 99), true))
        assertTrue(viewModel.applyVersionToForm(version, true))
        assertEquals("历史正文", viewModel.state.value.title)
        assertEquals(version.summary, viewModel.state.value.summary)
        assertEquals(version.content, viewModel.state.value.content)
        assertEquals(version.tags, viewModel.state.value.tags)
        assertEquals(version.metaSnapshotJson, viewModel.state.value.metaJson)
        assertEquals(entry.entryType, viewModel.state.value.entryType)
        assertEquals(entry.confidence, viewModel.state.value.confidence)
        assertEquals(entry.isFeatured, viewModel.state.value.isFeatured)
        assertEquals(entry.coverImagePath, viewModel.state.value.coverImagePath)
        assertTrue(viewModel.state.value.isDirty)
        assertEquals(null, viewModel.state.value.pendingVersion)
    }

    @Test
    fun versionPagesReplaceWindowAndKeepDraft() = runTest(dispatcher) {
        val entry = EncyclopediaEntryEntity(id = 8, encyclopediaId = 3, title = "百科")
        val dao = mockk<EncyclopediaEntryDao> { coEvery { getById(8) } returns entry }
        val rows = (25L downTo 1L).map {
            com.mojing.app.data.local.entity.EntryVersionEntity(id = it, entryId = 8, version = it.toInt())
        }
        val versions = mockk<EntryVersionDao> {
            coEvery { getPage(8, any(), 11) } coAnswers {
                rows.filter { it.id < secondArg<Long>() }.take(11)
            }
        }
        val viewModel = createViewModel(encyclopediaDao(), dao, versions)
        viewModel.load(3, 8)
        assertEquals(10, viewModel.state.value.versions.size)
        viewModel.updateTitle("未保存标题")
        viewModel.loadVersionPage(true)
        assertEquals(15L, viewModel.state.value.versions.first().id)
        assertEquals(10, viewModel.state.value.versions.size)
        viewModel.loadVersionPage(true)
        assertEquals(5, viewModel.state.value.versions.size)
        assertFalse(viewModel.state.value.hasOlderVersions)
        viewModel.loadVersionPage(false)
        assertEquals(25L, viewModel.state.value.versions.first().id)
        assertEquals("未保存标题", viewModel.state.value.title)
        assertTrue(viewModel.state.value.isDirty)
    }

    @Test
    fun repeatedSaveCreatesOneEntryAndFailureAllowsRetry() = runTest(dispatcher) {
        val release = CompletableDeferred<Unit>()
        var failSave = true
        val save = mockk<SaveCharacterEntryUseCase> {
            coEvery { this@mockk.saveEdited(any()) } coAnswers {
                release.await()
                if (failSave) error("write failed")
                firstArg<EncyclopediaEntryEntity>().copy(id = 12)
            }
        }
        val viewModel = createViewModel(encyclopediaDao(), mockk(relaxed = true), saveEntry = save)
        viewModel.load(3, 0)
        viewModel.updateTitle("潮汐钟")
        viewModel.save()
        viewModel.save()
        coVerify(exactly = 1) { save.saveEdited(any()) }
        release.complete(Unit)
        advanceUntilIdle()
        assertFalse(viewModel.state.value.isSaving)
        assertTrue(viewModel.state.value.isDirty)
        failSave = false
        viewModel.save()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.isPersisted)
        assertFalse(viewModel.state.value.isDirty)
        viewModel.save()
        coVerify(exactly = 2) { save.saveEdited(any()) }
    }

    @Test
    fun loadedConversationNoteKeepsItsLabelWhileEditingConfidence() = runTest {
        val entry = EncyclopediaEntryEntity(id = 8, encyclopediaId = 3,
            title = "线索", sourceSessionId = 7, confidence = "inferred")
        val dao = mockk<EncyclopediaEntryDao> { coEvery { getById(8) } returns entry }
        val viewModel = createViewModel(encyclopediaDao(), dao)
        viewModel.load(3, 8)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.isConversationNote)
        viewModel.updateConfidence("confirmed")
        assertTrue(viewModel.state.value.isConversationNote)
    }

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        encyclopediaDao: EncyclopediaDao,
        entryDao: EncyclopediaEntryDao,
        versionDao: EntryVersionDao = mockk(relaxed = true),
        saveEntry: SaveCharacterEntryUseCase = mockk(relaxed = true),
        aiCompleter: AiCompleter = mockk(relaxed = true),
        publicKey: String = "",
        storage: SecureStorage? = null,
        messageDao: com.mojing.app.data.local.dao.MessageDao = mockk(relaxed = true),
        draftStore: EntryEditDraftStore = mockk(relaxed = true) {
            coEvery { load(any(), any()) } returns null
        },
        relationDao: com.mojing.app.data.local.dao.EntryRelationDao = mockk(relaxed = true),
    ): EntryEditViewModel {
        val secureStorage = storage ?: mockk<SecureStorage>(relaxed = true).also { every { it.publicApiKey } returns publicKey }
        return EntryEditViewModel(
            entryDao = entryDao,
            saveCharacterEntry = saveEntry,
            encyclopediaDao = encyclopediaDao,
            entryVersionDao = versionDao,
            aiCompleter = aiCompleter,
            secureStorage = secureStorage,
            imageRepository = mockk<ImageRepository>(relaxed = true),
            messageDao = messageDao,
            draftStore = draftStore,
            relationDao = relationDao,
        )
    }

    private fun encyclopediaDao(entity: EncyclopediaEntity? = EncyclopediaEntity(id = 3L, name = "雾海")) =
        mockk<EncyclopediaDao> {
            coEvery { getById(3L) } returns entity
        }

    @Test
    fun missingEncyclopediaShowsLoadError() = runTest(dispatcher) {
        val viewModel = createViewModel(
            encyclopediaDao = encyclopediaDao(null),
            entryDao = mockk(relaxed = true),
        )

        viewModel.load(3L, 0L)

        assertTrue(viewModel.state.value.isLoaded)
        assertNotNull(viewModel.state.value.loadError)
        assertFalse(viewModel.state.value.isPersisted)
    }

    @Test
    fun missingExistingEntryDoesNotOpenANewDraft() = runTest(dispatcher) {
        val entryDao = mockk<EncyclopediaEntryDao> {
            coEvery { getById(9L) } returns null
        }
        val viewModel = createViewModel(encyclopediaDao(), entryDao)

        viewModel.load(3L, 9L)

        assertNotNull(viewModel.state.value.loadError)
        assertFalse(viewModel.state.value.isPersisted)
        assertFalse(viewModel.state.value.isDirty)
    }

    @Test
    fun existingEntryTracksRealDraftChangesAndClearsAfterSave() = runTest(dispatcher) {
        val original = EncyclopediaEntryEntity(id = 9L, encyclopediaId = 3L, title = "雾港")
        val persisted = original.copy(title = "雾港城")
        val entryDao = mockk<EncyclopediaEntryDao> {
            coEvery { getById(9L) } returnsMany listOf(original, original)
        }
        val versionDao = mockk<EntryVersionDao> {
            coEvery { getPage(9L, any(), any()) } returns emptyList()
            coEvery { maxVersionForEntry(9L) } returns 0
            coEvery { insert(any()) } returns 1L
        }
        val save = mockk<SaveCharacterEntryUseCase> {
            coEvery { this@mockk.saveEdited(any()) } returns persisted
        }
        val viewModel = createViewModel(encyclopediaDao(), entryDao, versionDao, save)

        viewModel.load(3L, 9L)
        assertFalse(viewModel.state.value.isDirty)
        viewModel.updateTitle("雾港城")
        assertTrue(viewModel.state.value.isDirty)
        viewModel.updateTitle("雾港")
        assertFalse(viewModel.state.value.isDirty)

        viewModel.updateTitle("雾港城")
        viewModel.save()
        assertTrue(viewModel.state.value.isPersisted)
        assertFalse(viewModel.state.value.isDirty)
    }

    @Test
    fun openingEntryDoesNotWaitForHistoryAndHistoryFailureKeepsDraft() = runTest(dispatcher) {
        val entry = EncyclopediaEntryEntity(id = 9L, encyclopediaId = 3L, title = "雾港", content = "已保存正文")
        val entries = mockk<EncyclopediaEntryDao> { coEvery { getById(9L) } returns entry }
        val release = CompletableDeferred<Unit>()
        val versions = mockk<EntryVersionDao> {
            coEvery { getPage(9L, any(), any()) } coAnswers {
                release.await()
                throw IllegalStateException("history unavailable")
            }
        }
        val vm = createViewModel(encyclopediaDao(), entries, versions)
        vm.load(3L, 9L)
        assertTrue(vm.state.value.isLoaded)
        assertTrue(vm.state.value.isPersisted)
        assertTrue(vm.state.value.isLoadingVersions)
        assertEquals("已保存正文", vm.state.value.content)
        vm.updateContent("读取期间的修改")
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(null, vm.state.value.loadError)
        assertNotNull(vm.state.value.versionError)
        assertEquals("读取期间的修改", vm.state.value.content)
        assertTrue(vm.state.value.isDirty)
        coEvery { versions.getPage(9L, any(), any()) } returns emptyList()
        vm.loadVersionPage(older = false)
        assertEquals(null, vm.state.value.versionError)
        assertEquals("读取期间的修改", vm.state.value.content)
        coVerify(exactly = 1) { entries.getById(9L) }
    }

    @Test
    fun versionRefreshFailureKeepsCommittedEntryAndRetriesOnlyRead() = runTest(dispatcher) {
        val saved = EncyclopediaEntryEntity(id = 12L, encyclopediaId = 3L, title = "潮汐钟")
        val versions = mockk<EntryVersionDao> {
            coEvery { getPage(12L, any(), any()) } throws IllegalStateException("read")
        }
        val saver = mockk<SaveCharacterEntryUseCase> {
            coEvery { saveEdited(any()) } returns saved
        }
        val vm = createViewModel(encyclopediaDao(), mockk(relaxed = true), versions, saver)
        vm.load(3L, 0L)
        vm.updateTitle("潮汐钟")
        vm.save()
        assertTrue(vm.state.value.isPersisted)
        assertFalse(vm.state.value.isDirty)
        assertFalse(vm.state.value.isSaving)
        assertEquals(null, vm.state.value.saveError)
        assertNotNull(vm.state.value.versionError)
        vm.save()
        vm.updateContent("刷新时保留的新修改")
        coEvery { versions.getPage(12L, any(), any()) } returns emptyList()
        vm.loadVersionPage(older = false)
        assertEquals(null, vm.state.value.versionError)
        assertEquals("刷新时保留的新修改", vm.state.value.content)
        assertTrue(vm.state.value.isDirty)
        coVerify(exactly = 1) { saver.saveEdited(any()) }
    }

    @Test
    fun newEntryBecomesPersistedAfterFirstSave() = runTest(dispatcher) {
        val saved = EncyclopediaEntryEntity(id = 12L, encyclopediaId = 3L, title = "潮汐钟")
        val entryDao = mockk<EncyclopediaEntryDao>(relaxed = true)
        val versionDao = mockk<EntryVersionDao> {
            coEvery { getPage(12L, any(), any()) } returns emptyList()
        }
        val save = mockk<SaveCharacterEntryUseCase> {
            coEvery { this@mockk.saveEdited(any()) } returns saved
        }
        val viewModel = createViewModel(encyclopediaDao(), entryDao, versionDao, save)

        viewModel.load(3L, 0L)
        viewModel.updateTitle("潮汐钟")
        viewModel.save()

        assertTrue(viewModel.state.value.isPersisted)
        assertFalse(viewModel.state.value.isDirty)
        assertEquals("潮汐钟", viewModel.state.value.title)
    }

    @Test
    fun saveKeepsChangesMadeWhilePersistenceIsInFlight() = runTest(dispatcher) {
        val original = EncyclopediaEntryEntity(id = 9L, encyclopediaId = 3L, title = "雾港")
        val persisted = original.copy(title = "雾港城")
        val saveRelease = CompletableDeferred<Unit>()
        val entryDao = mockk<EncyclopediaEntryDao> {
            coEvery { getById(9L) } returnsMany listOf(original, original)
        }
        val versionDao = mockk<EntryVersionDao> {
            coEvery { getPage(9L, any(), any()) } returns emptyList()
            coEvery { maxVersionForEntry(9L) } returns 0
            coEvery { insert(any()) } returns 1L
        }
        val save = mockk<SaveCharacterEntryUseCase> {
            coEvery { this@mockk.saveEdited(any()) } coAnswers {
                saveRelease.await()
                persisted
            }
        }
        val viewModel = createViewModel(encyclopediaDao(), entryDao, versionDao, save)

        viewModel.load(3L, 9L)
        viewModel.updateTitle("雾港城")
        viewModel.save()
        assertTrue(viewModel.state.value.isSaving)
        viewModel.updateTitle("雾港城·北区")
        saveRelease.complete(Unit)
        advanceUntilIdle()

        assertEquals("雾港城·北区", viewModel.state.value.title)
        assertTrue(viewModel.state.value.isPersisted)
        assertTrue(viewModel.state.value.isDirty)
    }

    @Test
    fun aiCompletionFillsBlankContentWithoutOverwritingUserText() = runTest(dispatcher) {
        val ai = mockk<AiCompleter> {
            every { fieldKeysFor("encyclopedia_entry", "character") } returns setOf("alias", "race")
            coEvery { complete(any(), any(), any(), any()) } returns mapOf(
                "alias" to "潮生",
                "race" to "人类",
            )
        }
        val viewModel = createViewModel(
            encyclopediaDao = encyclopediaDao(),
            entryDao = mockk(relaxed = true),
            aiCompleter = ai,
            publicKey = "test-key",
        )

        viewModel.load(3L, 0L)
        viewModel.updateTitle("潮汐钟守")
        viewModel.aiComplete()

        assertTrue(viewModel.state.value.content.contains("【alias】潮生"))
        assertTrue(viewModel.state.value.content.contains("【race】人类"))
        assertTrue(viewModel.state.value.isDirty)

        viewModel.updateContent("用户写下的正文")
        viewModel.aiComplete()
        assertEquals("用户写下的正文", viewModel.state.value.content)
    }

    @Test
    fun coverPromptHintParticipatesInDraftDirtyAndRecovery() = runTest(dispatcher) {
        val store = mockk<EntryEditDraftStore>(relaxed = true) {
            coEvery { load(3L, 0L) } returns null
        }
        val vm = createViewModel(encyclopediaDao(), mockk(relaxed = true), draftStore = store)
        vm.load(3L, 0L)
        vm.updateCoverPromptHint("冷色月光，避免文字")
        advanceUntilIdle()

        assertTrue(vm.state.value.isDirty)
        coVerify { store.save(3L, 0L, match { it.coverPromptHint == "冷色月光，避免文字" }) }

        val recoveredStore = mockk<EntryEditDraftStore>(relaxed = true) {
            coEvery { load(3L, 0L) } returns EntryDraftSnapshot(coverPromptHint = "冷色月光，避免文字")
        }
        val recovered = createViewModel(encyclopediaDao(), mockk(relaxed = true), draftStore = recoveredStore)
        recovered.load(3L, 0L)
        assertEquals("", recovered.state.value.coverPromptHint)
        assertTrue(recovered.state.value.recoverableDraft?.coverPromptHint == "冷色月光，避免文字")
        recovered.restoreDraft()
        assertEquals("冷色月光，避免文字", recovered.state.value.coverPromptHint)
    }

    @Test
    fun saveDraftAndLeaveWaitsForDraftWriteBeforeLeaving() = runTest(dispatcher) {
        val write = CompletableDeferred<Unit>()
        val store = mockk<EntryEditDraftStore>(relaxed = true) {
            coEvery { load(3L, 0L) } returns null
            coEvery { save(any(), any(), any()) } coAnswers { write.await() }
        }
        val vm = createViewModel(encyclopediaDao(), mockk(relaxed = true), draftStore = store)
        vm.load(3L, 0L)
        vm.updateCoverPromptHint("保留这条提示")
        var left = false
        vm.saveDraftAndLeave { left = true }
        assertFalse(left)
        write.complete(Unit)
        advanceUntilIdle()

        assertTrue(left)
        assertFalse(vm.state.value.isDiscardingDraft)
        coVerify { store.save(3L, 0L, match { it.coverPromptHint == "保留这条提示" }) }
    }
    @Test fun capacityFailureKeepsDraftAndRetryCapturesNewSettings() = runTest(dispatcher) {
        val ai = mockk<AiCompleter>(relaxed = true)
        val storage = mockk<SecureStorage>(relaxed = true)
        var capacity = 2000
        every { storage.publicApiKey } returns "key"
        every { storage.publicBaseUrl } returns "https://one.test/v1"
        every { storage.publicModel } returns "model"
        every { storage.modelPlatforms() } answers { listOf(com.mojing.app.data.ModelPlatform(
            "one", "one", "https://one.test/v1", "key", listOf("model"), "model", mapOf("model" to capacity))) }
        coEvery { ai.complete(any(), any(), any(), any()) } coAnswers {
            val request = arg<AiCompleter.CompleteRequest>(3)
            if (request.contextWindow == 2000) throw com.mojing.app.domain.engine.RequestContextLimitException(
                com.mojing.app.domain.engine.RequestContextBudget.Result.TooLarge(4000, 3000, 2000))
            emptyMap()
        }
        val store = mockk<EntryEditDraftStore>(relaxed = true) { coEvery { load(any(), any()) } returns null }
        val vm = createViewModel(encyclopediaDao(), mockk(relaxed = true), aiCompleter = ai, storage = storage, draftStore = store)
        vm.load(3L, 0L); vm.updateTitle("完整标题"); vm.updateContent("完整草稿正文")
        vm.aiComplete(); advanceUntilIdle()
        assertFalse(vm.state.value.isAiCompleting)
        assertTrue(vm.state.value.snackbar!!.contains("未发送请求"))
        assertFalse(vm.state.value.snackbar!!.startsWith("请求失败"))
        assertEquals("完整草稿正文", vm.state.value.content)
        assertTrue(vm.state.value.isDirty)
        coVerify { store.save(3L, 0L, match { it.content == "完整草稿正文" }) }
        capacity = 20000
        vm.aiComplete(); advanceUntilIdle()
        coVerify(exactly = 1) { ai.complete("key", "https://one.test/v1", "model", match { it.contextWindow == 20000 && it.currentData["content"] == "完整草稿正文" }) }
        assertEquals("完整草稿正文", vm.state.value.content)
    }

    @Test fun duplicateCompletionIsRejectedAndCancelKeepsEditsForSaveAndLeave() = runTest(dispatcher) {
        val gate = CompletableDeferred<Map<String, Any>>()
        val ai = mockk<AiCompleter>(relaxed = true) { coEvery { complete(any(), any(), any(), any()) } coAnswers { gate.await() } }
        val store = mockk<EntryEditDraftStore>(relaxed = true) { coEvery { load(any(), any()) } returns null }
        val vm = createViewModel(encyclopediaDao(), mockk(relaxed = true), aiCompleter = ai, publicKey = "key", draftStore = store)
        vm.load(3L, 0L); vm.updateTitle("潮生"); vm.updateContent("原文")
        vm.aiComplete(); vm.aiComplete()
        assertTrue(vm.state.value.isAiCompleting)
        coVerify(exactly = 1) { ai.complete(any(), any(), any(), any()) }
        vm.cancelAiComplete(); gate.complete(mapOf("content" to "过期生成")); advanceUntilIdle()
        assertFalse(vm.state.value.isAiCompleting)
        assertEquals("原文", vm.state.value.content)
        vm.updateContent("取消后的编辑")
        var left = false
        vm.saveDraftAndLeave { left = true }; advanceUntilIdle()
        assertTrue(left)
        coVerify { store.save(3L, 0L, match { it.content == "取消后的编辑" }) }
    }

    @Test fun completionCannotWriteIntoAnotherLoadedEntry() = runTest(dispatcher) {
        val gate = CompletableDeferred<Map<String, Any>>()
        val ai = mockk<AiCompleter>(relaxed = true) { coEvery { complete(any(), any(), any(), any()) } coAnswers { gate.await() } }
        val entries = mockk<EncyclopediaEntryDao>(relaxed = true) {
            coEvery { getById(9L) } returns EncyclopediaEntryEntity(id = 9L, encyclopediaId = 3L, title = "另一个目标", content = "另一个正文")
        }
        val vm = createViewModel(encyclopediaDao(), entries, aiCompleter = ai, publicKey = "key")
        vm.load(3L, 0L); vm.updateTitle("旧目标"); vm.aiComplete()
        vm.load(3L, 9L); gate.complete(mapOf("content" to "旧目标返回")); advanceUntilIdle()
        assertEquals("另一个目标", vm.state.value.title)
        assertEquals("另一个正文", vm.state.value.content)
        assertFalse(vm.state.value.isAiCompleting)
    }

}
