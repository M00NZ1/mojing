package com.mojing.app.ui.encyclopedia

import com.mojing.app.data.SecureStorage
import com.mojing.app.data.local.dao.EncyclopediaDao
import com.mojing.app.data.local.dao.EncyclopediaEntryDao
import com.mojing.app.data.local.dao.EntryRelationDao
import com.mojing.app.data.local.dao.TimelineEventDao
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.EncyclopediaEntryEntity
import com.mojing.app.data.local.entity.EntryRelationEntity
import com.mojing.app.data.local.entity.TimelineEventEntity
import com.mojing.app.data.remote.LlmApiService
import com.mojing.app.domain.encyclopedia.WorldInfoAiConverter
import com.mojing.app.domain.generation.GenerationQueueProcessor
import com.mojing.app.domain.usecase.DeleteEncyclopediaEntryUseCase
import com.mojing.app.domain.usecase.SaveCharacterEntryUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
class EncyclopediaDetailViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Test
    fun openingEntriesDoesNotReadTimelineOrRelationBodies() = runTest(dispatcher) {
        val encyclopediaDao = mockk<EncyclopediaDao>(relaxed = true)
        val timelineDao = mockk<TimelineEventDao>(relaxed = true)
        val relationDao = mockk<EntryRelationDao>(relaxed = true)
        coEvery { encyclopediaDao.getById(3L) } returns EncyclopediaEntity(id = 3L, name = "世界")
        val vm = createViewModel(encyclopediaDao, relationDao = relationDao, timelineDao = timelineDao)

        vm.load(3L)

        assertTrue(vm.state.value.isLoaded)
        coVerify(exactly = 0) { timelineDao.getByEncyclopedia(any()) }
        coVerify(exactly = 0) { timelineDao.getPage(any(), any(), any(), any()) }
        coVerify(exactly = 0) { relationDao.getByEncyclopedia(any()) }
        coVerify(exactly = 0) { relationDao.getPage(any(), any(), any()) }
    }

    @Test
    fun timelinePageKeepsStableOrderAcrossBoundary() = runTest(dispatcher) {
        val encyclopediaDao = mockk<EncyclopediaDao>(relaxed = true)
        val timelineDao = mockk<TimelineEventDao>(relaxed = true)
        coEvery { encyclopediaDao.getById(3L) } returns EncyclopediaEntity(id = 3L, name = "世界")
        val events = (1L..25L).map { TimelineEventEntity(id = it, encyclopediaId = 3L) }
        coEvery { timelineDao.getPage(3L, null, null, 25) } returns events
        coEvery { timelineDao.getPage(3L, 0, 24L, 25) } returns events.drop(24)
        coEvery { timelineDao.maxSortOrder(3L) } returns 0
        val vm = createViewModel(encyclopediaDao, timelineDao = timelineDao)
        vm.load(3L)

        vm.setMainTab(EncyclopediaMainTab.TIMELINE)
        assertEquals(24, vm.state.value.timelineEvents.size)
        assertTrue(vm.state.value.timelineHasNext)
        vm.nextTimelinePage()
        assertEquals(listOf(25L), vm.state.value.timelineEvents.map { it.id })
        assertEquals(1, vm.state.value.timelinePageIndex)
        vm.previousTimelinePage()
        assertEquals(1L, vm.state.value.timelineEvents.first().id)
    }

    @Test
    fun failedTimelineSaveDoesNotConfirmOrDiscardTheDraft() = runTest(dispatcher) {
        val encyclopediaDao = mockk<EncyclopediaDao>(relaxed = true)
        val timelineDao = mockk<TimelineEventDao>(relaxed = true)
        coEvery { encyclopediaDao.getById(3L) } returns EncyclopediaEntity(id = 3L, name = "世界")
        coEvery { timelineDao.upsert(any()) } throws IllegalStateException("disk unavailable")
        val vm = createViewModel(encyclopediaDao, timelineDao = timelineDao)
        vm.load(3L)
        vm.setMainTab(EncyclopediaMainTab.TIMELINE)
        val results = mutableListOf<Boolean>()

        vm.addTimelineEvent("纪元开始", "元年", 1) { results += it }

        assertEquals(listOf(false), results)
        assertFalse(vm.state.value.timelineSaving)
        assertEquals("事件保存失败，输入已保留，请重试", vm.state.value.timelineSaveError)
        coEvery { timelineDao.upsert(any()) } returns 9L
        vm.addTimelineEvent("纪元开始", "元年", 1) { results += it }
        assertEquals(listOf(false, true), results)
        assertEquals(null, vm.state.value.timelineSaveError)
    }

    @Test
    fun timelineDeleteConfirmsOnlyAfterDatabaseWrite() = runTest(dispatcher) {
        val encyclopediaDao = mockk<EncyclopediaDao>(relaxed = true)
        val timelineDao = mockk<TimelineEventDao>(relaxed = true)
        coEvery { encyclopediaDao.getById(3L) } returns EncyclopediaEntity(id = 3L, name = "世界")
        coEvery { timelineDao.delete(7L) } throws IllegalStateException("disk unavailable")
        val vm = createViewModel(encyclopediaDao, timelineDao = timelineDao)
        vm.load(3L)
        vm.setMainTab(EncyclopediaMainTab.TIMELINE)
        val results = mutableListOf<Boolean>()

        vm.deleteTimelineEvent(7L) { results += it }
        assertEquals(listOf(false), results)
        assertEquals("删除失败，请重试", vm.state.value.timelineDeleteError)
        coEvery { timelineDao.delete(7L) } returns Unit
        vm.deleteTimelineEvent(7L) { results += it }
        assertEquals(listOf(false, true), results)
        assertEquals(null, vm.state.value.timelineDeleteError)
        assertEquals(null, vm.state.value.timelineDeletingId)
    }

    @Test
    fun relationNextPageFailureKeepsVisiblePageAndRetries() = runTest(dispatcher) {
        val encyclopediaDao = mockk<EncyclopediaDao>(relaxed = true)
        val relationDao = mockk<EntryRelationDao>(relaxed = true)
        coEvery { encyclopediaDao.getById(3L) } returns EncyclopediaEntity(id = 3L, name = "世界")
        val relations = (25L downTo 1L).map { EntryRelationEntity(id = it, encyclopediaId = 3L,
            fromEntryId = 1L, toEntryId = 2L) }
        coEvery { relationDao.getPage(3L, Long.MAX_VALUE, 25) } returns relations
        coEvery { relationDao.getPage(3L, 2L, 25) } throws IllegalStateException("read failed")
        val vm = createViewModel(encyclopediaDao, relationDao = relationDao)
        vm.load(3L)
        vm.setMainTab(EncyclopediaMainTab.GRAPH)

        vm.nextRelationPage()
        assertEquals(24, vm.state.value.relations.size)
        assertEquals(0, vm.state.value.relationPageIndex)
        assertEquals("关系读取失败，当前页已保留", vm.state.value.relationsLoadError)
        coEvery { relationDao.getPage(3L, 2L, 25) } returns relations.drop(24)
        vm.retryRelationPage()
        assertEquals(listOf(1L), vm.state.value.relations.map { it.id })
        assertEquals(1, vm.state.value.relationPageIndex)
    }

    @Test
    fun relationDeleteFailureKeepsDialogResultPendingAndAllowsRetry() = runTest(dispatcher) {
        val encyclopediaDao = mockk<EncyclopediaDao>(relaxed = true)
        val relationDao = mockk<EntryRelationDao>(relaxed = true)
        coEvery { encyclopediaDao.getById(3L) } returns EncyclopediaEntity(id = 3L, name = "世界")
        val relation = EntryRelationEntity(id = 7L, encyclopediaId = 3L, fromEntryId = 1L, toEntryId = 2L)
        var deleted = false
        coEvery { relationDao.getPage(3L, Long.MAX_VALUE, 25) } coAnswers {
            if (deleted) emptyList() else listOf(relation)
        }
        coEvery { relationDao.delete(7L) } throws IllegalStateException("disk unavailable")
        val vm = createViewModel(encyclopediaDao, relationDao = relationDao)
        vm.load(3L)
        vm.setMainTab(EncyclopediaMainTab.GRAPH)
        val results = mutableListOf<Boolean>()

        vm.deleteRelation(7L) { results += it }
        assertEquals(listOf(false), results)
        assertEquals("删除失败，请重试", vm.state.value.relationDeleteError)
        assertEquals(listOf(7L), vm.state.value.relations.map { it.id })
        coEvery { relationDao.delete(7L) } coAnswers { deleted = true }
        vm.deleteRelation(7L) { results += it }
        assertEquals(listOf(false, true), results)
        assertEquals(emptyList<Long>(), vm.state.value.relations.map { it.id })
        assertEquals(null, vm.state.value.relationDeleteError)
        assertEquals(null, vm.state.value.relationDeletingId)
    }

    @Test
    fun pendingRelationDeleteDoesNotConfirmAnOlderWorld() = runTest(dispatcher) {
        val encyclopediaDao = mockk<EncyclopediaDao>(relaxed = true)
        val relationDao = mockk<EntryRelationDao>(relaxed = true)
        coEvery { encyclopediaDao.getById(any()) } answers { EncyclopediaEntity(id = firstArg(), name = "世界") }
        val pending = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { relationDao.delete(7L) } coAnswers { pending.await() }
        val vm = createViewModel(encyclopediaDao, relationDao = relationDao)
        vm.load(3L)
        var confirmations = 0

        vm.deleteRelation(7L) { confirmations++ }
        vm.deleteRelation(7L) { confirmations++ }
        assertEquals(7L, vm.state.value.relationDeletingId)
        vm.load(4L)
        pending.complete(Unit)
        assertEquals(0, confirmations)
        assertEquals(null, vm.state.value.relationDeletingId)
        coVerify(exactly = 1) { relationDao.delete(7L) }
    }

    @Test
    fun createEntryReturnsPersistedIdAndRefreshesTheCurrentEncyclopedia() = runTest(dispatcher) {
        val dao = mockk<EncyclopediaDao>(relaxed = true)
        val entries = mockk<EncyclopediaEntryDao>(relaxed = true)
        coEvery { dao.getById(1L) } returns EncyclopediaEntity(id = 1L, name = "世界")
        val saved = mockk<SaveCharacterEntryUseCase>()
        val created = EncyclopediaEntryEntity(id = 42L, encyclopediaId = 1L, title = "新角色", entryType = "character")
        coEvery { saved.invoke(any()) } returns created
        val vm = createViewModel(dao, entries, saveEntry = saved)
        vm.load(1L)

        var createdId: Long? = null
        vm.createEntry("新角色", "character") { createdId = it }

        assertEquals(42L, createdId)
        assertFalse(vm.state.value.entryCreating)
        assertEquals(null, vm.state.value.createEntryError)
        io.mockk.coVerify(exactly = 1) { saved.invoke(match { it.encyclopediaId == 1L && it.title == "新角色" }) }
    }

    @Test fun sedimentPagingIsBoundedAndFilterChangesSupersedePendingPages() = runTest(dispatcher) {
        val dao = mockk<EncyclopediaDao>(relaxed = true)
        val entries = mockk<EncyclopediaEntryDao>(relaxed = true)
        coEvery { dao.getById(1) } returns EncyclopediaEntity(id = 1, name = "世界")
        val rows = (205L downTo 1L).map { EncyclopediaEntryEntity(id = it, encyclopediaId = 1, confidence = "inferred") }
        coEvery { entries.getSedimentPage(1, any(), any()) } answers { rows.filter { it.id < secondArg<Long>() }.take(101) }
        coEvery { entries.countSediment(1, false) } returns 205
        val savedState = androidx.lifecycle.SavedStateHandle()
        val vm = createViewModel(dao, entries, savedStateHandle = savedState)
        vm.load(1)
        assertEquals(100, vm.state.value.sedimentEntries.size)
        assertEquals(205, vm.state.value.sedimentTotal)
        assertTrue(vm.state.value.sedimentHasNext)
        val pending = kotlinx.coroutines.CompletableDeferred<List<EncyclopediaEntryEntity>>()
        coEvery { entries.getSedimentPage(1, 106, "all") } coAnswers { pending.await() }
        vm.nextSedimentPage()
        vm.nextSedimentPage()
        assertTrue(vm.state.value.sedimentLoading)
        assertEquals(listOf(Long.MAX_VALUE, 106L), vm.state.value.sedimentCursors)
        io.mockk.coVerify(exactly = 1) { entries.getSedimentPage(1, 106, "all") }
        coEvery { entries.getSedimentPage(1, Long.MAX_VALUE, "pending") } throws IllegalStateException("read failed")
        vm.setSedimentFilter("pending")
        assertFalse(vm.state.value.sedimentLoading)
        assertNotNull(vm.state.value.sedimentError)
        assertEquals(listOf(Long.MAX_VALUE), vm.state.value.sedimentCursors)
        pending.complete(rows.takeLast(10))
        coEvery { entries.getSedimentPage(1, Long.MAX_VALUE, "pending") } returns rows.takeLast(3)
        vm.reloadSediment()
        assertEquals(listOf(3L, 2L, 1L), vm.state.value.sedimentEntries.map { it.id })
        assertFalse(vm.state.value.sedimentHasNext)
        assertEquals(null, vm.state.value.sedimentError)
        coEvery { entries.getSedimentPage(1, Long.MAX_VALUE, "pending") } returns rows.take(101)
        vm.reloadSediment()
        vm.nextSedimentPage()
        assertEquals(105L, vm.state.value.sedimentEntries.first().id)
        vm.load(1)
        assertEquals("pending", vm.state.value.sedimentFilter)
        assertEquals(listOf(Long.MAX_VALUE, 106L), vm.state.value.sedimentCursors)
        assertEquals(105L, vm.state.value.sedimentEntries.first().id)
        val restored = createViewModel(dao, entries, savedStateHandle = androidx.lifecycle.SavedStateHandle(mapOf(
            "sediment_filter_1" to savedState.get<String>("sediment_filter_1"),
            "sediment_cursors_1" to savedState.get<LongArray>("sediment_cursors_1"),
        )))
        restored.load(1)
        assertEquals("pending", restored.state.value.sedimentFilter)
        assertEquals(listOf(Long.MAX_VALUE, 106L), restored.state.value.sedimentCursors)
        assertEquals(105L, restored.state.value.sedimentEntries.first().id)
        vm.previousSedimentPage()
        assertEquals(205L, vm.state.value.sedimentEntries.first().id)
    }

    @Test fun batchConfirmationPreservesFailureAndRefreshesOnlyEligibleEntries() = runTest(dispatcher) {
        val dao = mockk<EncyclopediaDao>(relaxed = true)
        val entries = mockk<EncyclopediaEntryDao>(relaxed = true)
        coEvery { dao.getById(1) } returns EncyclopediaEntity(id = 1, name = "世界")
        var rows = listOf(EncyclopediaEntryEntity(id = 7, encyclopediaId = 1, confidence = "inferred", sourceSessionId = 9),
            EncyclopediaEntryEntity(id = 8, encyclopediaId = 1, confidence = "confirmed", sourceSessionId = 9))
        coEvery { entries.getSedimentPage(1, any(), any()) } answers { rows }
        coEvery { entries.getEntryPage(1, any(), "") } answers { rows }
        coEvery { entries.confirmSedimentEntries(1, listOf(7), any()) } throws IllegalStateException("write failed")
        val vm = createViewModel(dao, entries)
        vm.load(1)
        var confirmed = false
        vm.confirmSedimentEntries(setOf(7, 8, 999)) { confirmed = true }
        assertFalse(confirmed)
        assertFalse(vm.state.value.sedimentConfirming)
        assertEquals("确认失败，选择已保留，请重试", vm.state.value.snackbar)
        coEvery { entries.confirmSedimentEntries(1, listOf(7), any()) } answers {
            rows = rows.map { it.copy(confidence = "confirmed") }; 1
        }
        vm.confirmSedimentEntries(setOf(7, 8, 999)) { confirmed = true }
        assertTrue(confirmed)
        assertTrue(vm.state.value.sedimentEntries.all { it.confidence == "confirmed" })
        assertEquals("已确认 1 条资料", vm.state.value.snackbar)
    }

    @Test fun lateBatchConfirmationDoesNotRefreshAnotherEncyclopedia() = runTest(dispatcher) {
        val dao = mockk<EncyclopediaDao>(relaxed = true)
        val entries = mockk<EncyclopediaEntryDao>(relaxed = true)
        coEvery { dao.getById(any()) } answers { EncyclopediaEntity(id = firstArg(), name = "世界${firstArg<Long>()}") }
        coEvery { entries.getSedimentPage(1, any(), any()) } returns listOf(EncyclopediaEntryEntity(id = 7, encyclopediaId = 1, confidence = "inferred"))
        val gate = kotlinx.coroutines.CompletableDeferred<Int>()
        coEvery { entries.confirmSedimentEntries(1, listOf(7), any()) } coAnswers { gate.await() }
        val vm = createViewModel(dao, entries)
        vm.load(1)
        var confirmed = false
        vm.confirmSedimentEntries(setOf(7)) { confirmed = true }
        assertTrue(vm.state.value.sedimentConfirming)
        vm.load(2)
        assertTrue(vm.state.value.sedimentConfirming)
        gate.complete(1)
        assertFalse(confirmed)
        assertFalse(vm.state.value.sedimentConfirming)
        assertEquals(2L, vm.state.value.encyclopedia?.id)
        assertEquals(null, vm.state.value.snackbar)
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun entryPagesStayBoundedAndRestoreCategoryAndCursor() = runTest(dispatcher) {
        val dao = mockk<EncyclopediaDao> {
            coEvery { getById(3L) } returns EncyclopediaEntity(id = 3L, name = "雾海")
        }
        val records = (1L..205L).map { EncyclopediaEntryEntity(id = it, encyclopediaId = 3,
            title = "条目$it", entryType = "location") }
        val entries = mockk<EncyclopediaEntryDao>(relaxed = true) {
            coEvery { getEntryPage(3L, any(), any()) } answers {
                records.filter { it.id > secondArg<Long>() }.take(101)
            }
            coEvery { countEntries(3L, any()) } returns records.size
        }
        val saved = androidx.lifecycle.SavedStateHandle()
        val vm = createViewModel(dao, entries, savedStateHandle = saved)
        vm.load(3L)
        assertEquals(100, vm.state.value.entries.size)
        assertEquals(205, vm.state.value.filteredEntryCount)
        vm.nextEntryPage()
        assertEquals(101L, vm.state.value.entries.first().id)
        vm.nextEntryPage()
        assertEquals(5, vm.state.value.entries.size)
        assertFalse(vm.state.value.entriesHasNext)
        vm.selectType("location")
        assertEquals(listOf(0L), vm.state.value.entryCursors)
        val gate = kotlinx.coroutines.CompletableDeferred<List<EncyclopediaEntryEntity>>()
        coEvery { entries.getEntryPage(3L, 100, "location") } coAnswers { gate.await() }
        vm.nextEntryPage()
        vm.nextEntryPage()
        assertEquals(listOf(0L, 100L), vm.state.value.entryCursors)
        gate.completeExceptionally(IllegalStateException("read failure"))
        assertNotNull(vm.state.value.entriesError)
        coEvery { entries.getEntryPage(3L, 100, "location") } returns records.drop(100).take(101)
        vm.reloadEntryPage()
        assertEquals(101L, vm.state.value.entries.first().id)
        vm.setPreviewEntry(101)
        vm.load(3L)
        assertEquals(101L, vm.state.value.previewEntryId)
        val restored = createViewModel(dao, entries, savedStateHandle = saved)
        restored.load(3L)
        assertEquals("location", restored.state.value.selectedType)
        assertEquals(listOf(0L, 100L), restored.state.value.entryCursors)
        assertEquals(101L, restored.state.value.entries.first().id)
        restored.previousEntryPage()
        assertEquals(1L, restored.state.value.entries.first().id)
    }

    @Test
    fun metaFillSnapshotsAllCategoryIdsAndBlocksDuplicateSubmission() = runTest(dispatcher) {
        val dao = mockk<EncyclopediaDao> {
            coEvery { getById(3L) } returns EncyclopediaEntity(id = 3L, name = "雾海")
        }
        val ids = (1L..205L).toList()
        val gate = kotlinx.coroutines.CompletableDeferred<List<Long>>()
        val entries = mockk<EncyclopediaEntryDao>(relaxed = true) {
            coEvery { countEntries(3L, any()) } returns 205
            coEvery { getEntryIdsForType(3L, "location") } coAnswers { gate.await() }
        }
        val queue = mockk<GenerationQueueProcessor>(relaxed = true)
        val vm = createViewModel(dao, entries, apiKey = "test-key", queue = queue)
        vm.load(3L)
        vm.selectType("location")
        vm.batchAiFillMetaForCurrentEntries()
        vm.batchAiFillMetaForCurrentEntries()
        assertTrue(vm.state.value.metaFillSubmitting)
        gate.complete(ids)
        io.mockk.coVerify(exactly = 1) { queue.enqueueEncyclopediaMetaFill(3L, "雾海", ids) }
        assertFalse(vm.state.value.metaFillSubmitting)
    }

    @Test fun relationWriteFailureKeepsEditorOpenAndAllowsRetry() = runTest(dispatcher) {
        val dao = mockk<EncyclopediaDao>(relaxed = true)
        val relations = mockk<EntryRelationDao>(relaxed = true)
        coEvery { dao.getById(1) } returns EncyclopediaEntity(id = 1, name = "世界")
        val vm = createViewModel(dao, relationDao = relations)
        vm.load(1)
        coEvery { relations.upsert(any()) } throws IllegalStateException("disk full")
        var saves = 0
        vm.addRelation(10, 20, "盟友", "备注") { saves++ }
        assertEquals(0, saves)
        assertFalse(vm.state.value.relationSaving)
        assertEquals("关系保存失败，输入已保留，请重试", vm.state.value.relationError)
        coEvery { relations.upsert(any()) } returns 1L
        vm.addRelation(10, 20, "盟友", "备注") { saves++ }
        assertEquals(1, saves)
        assertEquals(null, vm.state.value.relationError)
    }

    @Test fun relationRefreshFailureKeepsSavedResultAndOffersReadRetry() = runTest(dispatcher) {
        val dao = mockk<EncyclopediaDao>(relaxed = true)
        val relations = mockk<EntryRelationDao>(relaxed = true)
        coEvery { dao.getById(1) } returns EncyclopediaEntity(id = 1, name = "世界")
        val vm = createViewModel(dao, relationDao = relations)
        vm.load(1)
        vm.setMainTab(EncyclopediaMainTab.GRAPH)
        coEvery { relations.upsert(any()) } returns 1L
        coEvery { relations.getPage(1, Long.MAX_VALUE, 25) } throws IllegalStateException("read failed")
        var saved = false
        vm.addRelation(10, 20, "盟友", "") { saved = true }
        assertTrue(saved)
        assertEquals(null, vm.state.value.relationError)
        assertEquals("关系读取失败，当前页已保留", vm.state.value.relationsLoadError)
    }

    @Test fun pendingRelationWriteRejectsDuplicatesAndDoesNotCloseAnotherWorldEditor() = runTest(dispatcher) {
        val dao = mockk<EncyclopediaDao>(relaxed = true)
        val relations = mockk<EntryRelationDao>(relaxed = true)
        coEvery { dao.getById(any()) } answers { EncyclopediaEntity(id = firstArg(), name = "世界") }
        val pending = kotlinx.coroutines.CompletableDeferred<Long>()
        coEvery { relations.upsert(any()) } coAnswers { pending.await() }
        val vm = createViewModel(dao, relationDao = relations)
        vm.load(1)
        var saves = 0
        vm.addRelation(10, 20, "盟友", "") { saves++ }
        vm.addRelation(10, 20, "盟友", "") { saves++ }
        assertTrue(vm.state.value.relationSaving)
        vm.load(2)
        assertTrue(vm.state.value.relationSaving)
        pending.complete(1L)
        assertFalse(vm.state.value.relationSaving)
        assertEquals(0, saves)
        assertEquals(null, vm.state.value.relationError)
        io.mockk.coVerify(exactly = 1) { relations.upsert(match { it.encyclopediaId == 1L }) }
        io.mockk.coVerify(exactly = 0) { relations.upsert(match { it.encyclopediaId == 2L }) }
    }

    private fun createViewModel(
        encyclopediaDao: EncyclopediaDao,
        entryDao: EncyclopediaEntryDao = mockk(relaxed = true),
        relationDao: EntryRelationDao = mockk(relaxed = true),
        timelineDao: TimelineEventDao = mockk(relaxed = true),
        savedStateHandle: androidx.lifecycle.SavedStateHandle = androidx.lifecycle.SavedStateHandle(),
        apiKey: String = "",
        queue: GenerationQueueProcessor = mockk(relaxed = true),
        saveEntry: SaveCharacterEntryUseCase = mockk(relaxed = true),
    ): EncyclopediaDetailViewModel {
        val secureStorage = mockk<SecureStorage>(relaxed = true)
        every { secureStorage.publicApiKey } returns apiKey
        every { queue.observeActiveForEncyclopedia(any()) } returns flowOf(emptyList())
        return EncyclopediaDetailViewModel(
            encyclopediaDao = encyclopediaDao,
            entryDao = entryDao,
            saveCharacterEntry = saveEntry,
            deleteEncyclopediaEntry = mockk<DeleteEncyclopediaEntryUseCase>(relaxed = true),
            entryRelationDao = relationDao,
            timelineEventDao = timelineDao,
            secureStorage = secureStorage,
            generationQueueProcessor = queue,
            llmApiService = mockk<LlmApiService>(relaxed = true),
            worldInfoAiConverter = mockk<WorldInfoAiConverter>(relaxed = true),
            savedStateHandle = savedStateHandle,
        )
    }

    @Test
    fun missingEncyclopediaShowsRecoverableLoadError() = runTest(dispatcher) {
        val encyclopediaDao = mockk<EncyclopediaDao> {
            coEvery { getById(3L) } returns null
        }
        val viewModel = createViewModel(encyclopediaDao)

        viewModel.load(3L)

        assertTrue(viewModel.state.value.isLoaded)
        assertNotNull(viewModel.state.value.loadError)
        assertEquals(null, viewModel.state.value.encyclopedia)
    }

    @Test
    fun returningToSameEncyclopediaKeepsTabAndReadsConfirmedNote() = runTest(dispatcher) {
        val dao = mockk<EncyclopediaDao> {
            coEvery { getById(any()) } answers { EncyclopediaEntity(id = firstArg(), name = "雾海") }
        }
        var note = EncyclopediaEntryEntity(
            id = 9L, encyclopediaId = 3L, title = "潮汐钟", confidence = "inferred",
        )
        val entries = mockk<EncyclopediaEntryDao>(relaxed = true) {
            coEvery { getEntryPage(3L, any(), "") } answers { listOf(note) }
            coEvery { getSedimentPage(3L, any(), any()) } answers { listOf(note) }
        }
        val vm = createViewModel(dao, entries)
        vm.load(3L)
        vm.setMainTab(EncyclopediaMainTab.SEDIMENT)
        note = note.copy(confidence = "confirmed", title = "已核对的潮汐钟")

        vm.load(3L)

        assertEquals(EncyclopediaMainTab.SEDIMENT, vm.state.value.mainTab)
        assertEquals(listOf(note), vm.state.value.sedimentEntries)
        vm.load(4L)
        assertEquals(EncyclopediaMainTab.ENTRIES, vm.state.value.mainTab)
        assertTrue(vm.state.value.sedimentEntries.isEmpty())
    }

    @Test
    fun reloadKeepsEntryTypeButDropsDeletedPreview() = runTest(dispatcher) {
        val dao = mockk<EncyclopediaDao> {
            coEvery { getById(3L) } returns EncyclopediaEntity(id = 3L, name = "雾海")
        }
        val place = EncyclopediaEntryEntity(id = 9L, encyclopediaId = 3L, title = "雾港", entryType = "location")
        val person = EncyclopediaEntryEntity(id = 10L, encyclopediaId = 3L, title = "沈照", entryType = "character")
        var records = listOf(place, person)
        val entries = mockk<EncyclopediaEntryDao>(relaxed = true) {
            coEvery { getEntryPage(3L, any(), "") } answers { records }
            coEvery { countEntries(3L, "") } answers { records.size }
            coEvery { getEntryPage(3L, any(), "location") } answers { records.filter { it.entryType == "location" } }
        }
        val vm = createViewModel(dao, entries)
        vm.load(3L)
        vm.selectType("location")
        vm.setPreviewEntry(9L)
        vm.load(3L)
        assertEquals("location", vm.state.value.selectedType)
        assertEquals(listOf(place), vm.state.value.entries)
        assertEquals(9L, vm.state.value.previewEntryId)
        assertEquals(2, vm.state.value.entryCount)

        records = listOf(person)
        vm.load(3L)
        assertTrue(vm.state.value.entries.isEmpty())
        assertEquals(null, vm.state.value.previewEntryId)
        assertEquals(1, vm.state.value.entryCount)
    }

    @Test
    fun readFailureDoesNotAppearAsAnEmptyEncyclopedia() = runTest(dispatcher) {
        val encyclopediaDao = mockk<EncyclopediaDao> {
            coEvery { getById(3L) } throws IllegalStateException("database unavailable")
        }
        val viewModel = createViewModel(encyclopediaDao)

        viewModel.load(3L)

        assertTrue(viewModel.state.value.isLoaded)
        assertNotNull(viewModel.state.value.loadError)
        assertTrue(viewModel.state.value.entries.isEmpty())
    }

    @Test
    fun successfulLoadPublishesOneCompleteSnapshot() = runTest(dispatcher) {
        val encyclopedia = EncyclopediaEntity(id = 3L, name = "雾海")
        val entry = EncyclopediaEntryEntity(id = 9L, encyclopediaId = 3L, title = "潮汐钟")
        val encyclopediaDao = mockk<EncyclopediaDao> {
            coEvery { getById(3L) } returns encyclopedia
        }
        val entryDao = mockk<EncyclopediaEntryDao>(relaxed = true) {
            coEvery { getEntryPage(3L, any(), "") } returns listOf(entry)
            coEvery { countEntries(3L, "") } returns 1
            coEvery { getSedimentPage(3L, any(), any()) } returns emptyList()
        }
        val relationDao = mockk<EntryRelationDao>(relaxed = true) {
            coEvery { getByEncyclopedia(3L) } returns emptyList()
        }
        val timelineDao = mockk<TimelineEventDao>(relaxed = true) {
            coEvery { getByEncyclopedia(3L) } returns emptyList()
        }
        val viewModel = createViewModel(encyclopediaDao, entryDao, relationDao, timelineDao)

        viewModel.load(3L)

        assertTrue(viewModel.state.value.isLoaded)
        assertEquals(null, viewModel.state.value.loadError)
        assertEquals("雾海", viewModel.state.value.encyclopedia?.name)
        assertEquals(listOf(entry), viewModel.state.value.entries)
        assertTrue(viewModel.state.value.entryCount > 0)
    }
    @Test
    fun renameFailureKeepsDraftAndRetryClosesOnlyAfterSave() = runTest(dispatcher) {
        val dao = mockk<EncyclopediaDao> {
            coEvery { getById(3L) } returns EncyclopediaEntity(id = 3L, name = "雾海")
            coEvery { updateName(3L, any(), any()) } throws IllegalStateException("database unavailable")
        }
        val vm = createViewModel(dao)
        vm.load(3L)
        vm.beginRename()
        vm.editRename("  新雾海  ")
        vm.updateEncyclopediaName()
        assertEquals("  新雾海  ", vm.state.value.renameDraft)
        assertEquals("雾海", vm.state.value.encyclopedia?.name)
        assertNotNull(vm.state.value.renameError)
        assertFalse(vm.state.value.renameSaving)
        coEvery { dao.updateName(3L, "新雾海", any()) } returns 1
        vm.updateEncyclopediaName()
        assertEquals("新雾海", vm.state.value.encyclopedia?.name)
        assertEquals(null, vm.state.value.renameDraft)
        assertEquals(null, vm.state.value.renameError)
    }

    @Test
    fun renameInFlightBlocksDismissAndDuplicateAndSurvivesReload() = runTest(dispatcher) {
        val gate = kotlinx.coroutines.CompletableDeferred<Int>()
        val dao = mockk<EncyclopediaDao> {
            coEvery { getById(3L) } returns EncyclopediaEntity(id = 3L, name = "雾海")
            coEvery { updateName(3L, any(), any()) } coAnswers { gate.await() }
        }
        val vm = createViewModel(dao)
        vm.load(3L)
        vm.beginRename()
        vm.editRename("新雾海")
        vm.updateEncyclopediaName()
        vm.dismissRename()
        vm.editRename("其他名字")
        vm.load(3L)
        vm.updateEncyclopediaName()
        assertTrue(vm.state.value.renameSaving)
        assertEquals("新雾海", vm.state.value.renameDraft)
        gate.complete(1)
        io.mockk.coVerify(exactly = 1) { dao.updateName(3L, "新雾海", any()) }
        assertEquals("新雾海", vm.state.value.encyclopedia?.name)
        assertFalse(vm.state.value.renameSaving)
    }

    @Test
    fun deletedTargetRetainsDraftAndChangingTargetIgnoresOldCompletion() = runTest(dispatcher) {
        val gate = kotlinx.coroutines.CompletableDeferred<Int>()
        val dao = mockk<EncyclopediaDao> {
            coEvery { getById(any()) } answers { EncyclopediaEntity(id = firstArg(), name = "原名称") }
            coEvery { updateName(3L, any(), any()) } returns 0
        }
        val vm = createViewModel(dao)
        vm.load(3L)
        vm.beginRename()
        vm.editRename("修改名称")
        vm.updateEncyclopediaName()
        assertNotNull(vm.state.value.renameError)
        assertEquals("修改名称", vm.state.value.renameDraft)
        coEvery { dao.updateName(3L, any(), any()) } coAnswers { gate.await() }
        vm.updateEncyclopediaName()
        vm.load(4L)
        gate.complete(1)
        assertEquals(4L, vm.state.value.encyclopedia?.id)
        assertEquals("原名称", vm.state.value.encyclopedia?.name)
        assertEquals(null, vm.state.value.renameDraft)
    }

    @Test
    fun olderLoadCannotReplaceNewPageEvenWhenReadIgnoresCancellation() = runTest(dispatcher) {
        val gate = kotlinx.coroutines.CompletableDeferred<EncyclopediaEntity?>()
        val dao = mockk<EncyclopediaDao> {
            coEvery { getById(3L) } coAnswers {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() }
            }
            coEvery { getById(4L) } returns EncyclopediaEntity(id = 4L, name = "新百科")
        }
        val vm = createViewModel(dao)
        vm.load(3L)
        vm.load(4L)
        gate.complete(EncyclopediaEntity(id = 3L, name = "旧百科"))
        assertEquals(4L, vm.state.value.encyclopedia?.id)
        assertEquals("新百科", vm.state.value.encyclopedia?.name)
        assertEquals(null, vm.state.value.loadError)
    }

    @Test
    fun reloadSnapshotCannotUndoRenameCompletedDuringRead() = runTest(dispatcher) {
        val dao = mockk<EncyclopediaDao> {
            coEvery { getById(3L) } returns EncyclopediaEntity(id = 3L, name = "旧名称")
            coEvery { updateName(3L, any(), any()) } returns 1
        }
        val entries = mockk<EncyclopediaEntryDao>(relaxed = true)
        val vm = createViewModel(dao, entries)
        vm.load(3L)
        vm.beginRename()
        vm.editRename("新名称")
        val gate = kotlinx.coroutines.CompletableDeferred<List<EncyclopediaEntryEntity>>()
        coEvery { entries.getEntryPage(3L, any(), "") } coAnswers { gate.await() }
        vm.load(3L)
        vm.updateEncyclopediaName()
        gate.complete(emptyList())
        assertEquals("新名称", vm.state.value.encyclopedia?.name)
        assertEquals(null, vm.state.value.renameDraft)
        assertTrue(vm.state.value.isLoaded)
    }

    @Test
    fun rapidTypeRoundTripCannotPublishEarlierResultForSameType() = runTest(dispatcher) {
        val dao = mockk<EncyclopediaDao> {
            coEvery { getById(3L) } returns EncyclopediaEntity(id = 3L, name = "雾海")
        }
        val entries = mockk<EncyclopediaEntryDao>(relaxed = true)
        val vm = createViewModel(dao, entries)
        vm.load(3L)
        val old = EncyclopediaEntryEntity(id = 9L, encyclopediaId = 3L, title = "旧雾港", entryType = "location")
        val latest = old.copy(title = "新雾港")
        val gate = kotlinx.coroutines.CompletableDeferred<List<EncyclopediaEntryEntity>>()
        coEvery { entries.getEntryPage(3L, any(), "location") } coAnswers { gate.await() }
        vm.selectType("location")
        vm.selectType("character")
        coEvery { entries.getEntryPage(3L, any(), "location") } returns listOf(latest)
        vm.selectType("location")
        vm.setPreviewEntry(9L)

        gate.complete(listOf(old))

        assertEquals("location", vm.state.value.selectedType)
        assertEquals(listOf(latest), vm.state.value.entries)
        assertEquals(9L, vm.state.value.previewEntryId)
    }

    @Test
    fun oldTypeRefreshCannotOverwriteReloadedEncyclopedia() = runTest(dispatcher) {
        val dao = mockk<EncyclopediaDao> {
            coEvery { getById(3L) } returns EncyclopediaEntity(id = 3L, name = "雾海")
        }
        val entries = mockk<EncyclopediaEntryDao>(relaxed = true)
        val vm = createViewModel(dao, entries)
        vm.load(3L)
        val gate = kotlinx.coroutines.CompletableDeferred<List<EncyclopediaEntryEntity>>()
        coEvery { entries.getEntryPage(3L, any(), "location") } coAnswers { gate.await() }
        vm.selectType("location")
        val latest = EncyclopediaEntryEntity(id = 10L, encyclopediaId = 3L, title = "新码头", entryType = "location")
        coEvery { entries.getEntryPage(3L, any(), "") } returns listOf(latest)
        coEvery { entries.getEntryPage(3L, any(), "location") } returns listOf(latest)
        vm.load(3L)

        gate.complete(emptyList())

        assertEquals(listOf(latest), vm.state.value.entries)
    }

    @Test
    fun oldSedimentRefreshCannotReplaceNewerConfirmedSnapshot() = runTest(dispatcher) {
        val dao = mockk<EncyclopediaDao> {
            coEvery { getById(3L) } returns EncyclopediaEntity(id = 3L, name = "雾海")
        }
        val entries = mockk<EncyclopediaEntryDao>(relaxed = true)
        val vm = createViewModel(dao, entries)
        vm.load(3L)
        val gate = kotlinx.coroutines.CompletableDeferred<List<EncyclopediaEntryEntity>>()
        coEvery { entries.getSedimentPage(3L, any(), any()) } coAnswers { gate.await() }
        vm.setMainTab(EncyclopediaMainTab.SEDIMENT)
        val confirmed = EncyclopediaEntryEntity(id = 9L, encyclopediaId = 3L, title = "潮汐钟", confidence = "confirmed")
        coEvery { entries.getSedimentPage(3L, any(), any()) } returns listOf(confirmed)
        vm.setMainTab(EncyclopediaMainTab.GRAPH)
        vm.setMainTab(EncyclopediaMainTab.SEDIMENT)

        gate.complete(listOf(confirmed.copy(confidence = "inferred")))

        assertEquals(listOf(confirmed), vm.state.value.sedimentEntries)
    }

    @Test
    fun returningToSameIdDoesNotLetOldSaveCloseNewDraft() = runTest(dispatcher) {
        val gate = kotlinx.coroutines.CompletableDeferred<Int>()
        val dao = mockk<EncyclopediaDao> {
            coEvery { getById(any()) } answers { EncyclopediaEntity(id = firstArg(), name = "原名称") }
            coEvery { updateName(3L, any(), any()) } coAnswers { gate.await() }
        }
        val vm = createViewModel(dao)
        vm.load(3L)
        vm.beginRename()
        vm.editRename("旧提交")
        vm.updateEncyclopediaName()
        vm.load(4L)
        vm.load(3L)
        vm.beginRename()
        vm.editRename("新草稿")
        gate.complete(1)
        assertEquals("新草稿", vm.state.value.renameDraft)
        assertEquals("原名称", vm.state.value.encyclopedia?.name)
        assertFalse(vm.state.value.renameSaving)
    }

}
