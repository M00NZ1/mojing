package com.mojing.app.ui.encyclopedia

import com.mojing.app.data.SecureStorage
import com.mojing.app.data.local.dao.EncyclopediaDao
import com.mojing.app.data.local.dao.EncyclopediaEntryDao
import com.mojing.app.data.local.dao.EntryRelationDao
import com.mojing.app.data.local.dao.TimelineEventDao
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.EncyclopediaEntryEntity
import com.mojing.app.data.remote.LlmApiService
import com.mojing.app.domain.encyclopedia.WorldInfoAiConverter
import com.mojing.app.domain.generation.GenerationQueueProcessor
import com.mojing.app.domain.usecase.DeleteEncyclopediaEntryUseCase
import com.mojing.app.domain.usecase.SaveCharacterEntryUseCase
import io.mockk.coEvery
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

    @Test fun batchConfirmationPreservesFailureAndRefreshesOnlyEligibleEntries() = runTest(dispatcher) {
        val dao = mockk<EncyclopediaDao>(relaxed = true)
        val entries = mockk<EncyclopediaEntryDao>(relaxed = true)
        coEvery { dao.getById(1) } returns EncyclopediaEntity(id = 1, name = "世界")
        var rows = listOf(EncyclopediaEntryEntity(id = 7, encyclopediaId = 1, confidence = "inferred", sourceSessionId = 9),
            EncyclopediaEntryEntity(id = 8, encyclopediaId = 1, confidence = "confirmed", sourceSessionId = 9))
        coEvery { entries.getSedimentEntries(1) } answers { rows }
        coEvery { entries.getByEncyclopedia(1) } answers { rows }
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
        coEvery { entries.getSedimentEntries(1) } returns listOf(EncyclopediaEntryEntity(id = 7, encyclopediaId = 1, confidence = "inferred"))
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

    private fun createViewModel(
        encyclopediaDao: EncyclopediaDao,
        entryDao: EncyclopediaEntryDao = mockk(relaxed = true),
        relationDao: EntryRelationDao = mockk(relaxed = true),
        timelineDao: TimelineEventDao = mockk(relaxed = true),
    ): EncyclopediaDetailViewModel {
        val secureStorage = mockk<SecureStorage>(relaxed = true)
        every { secureStorage.publicApiKey } returns ""
        val queue = mockk<GenerationQueueProcessor>(relaxed = true)
        every { queue.observeActiveForEncyclopedia(any()) } returns flowOf(emptyList())
        return EncyclopediaDetailViewModel(
            encyclopediaDao = encyclopediaDao,
            entryDao = entryDao,
            saveCharacterEntry = mockk<SaveCharacterEntryUseCase>(relaxed = true),
            deleteEncyclopediaEntry = mockk<DeleteEncyclopediaEntryUseCase>(relaxed = true),
            entryRelationDao = relationDao,
            timelineEventDao = timelineDao,
            secureStorage = secureStorage,
            generationQueueProcessor = queue,
            llmApiService = mockk<LlmApiService>(relaxed = true),
            worldInfoAiConverter = mockk<WorldInfoAiConverter>(relaxed = true),
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
            coEvery { getByEncyclopedia(3L) } answers { listOf(note) }
            coEvery { getSedimentEntries(3L) } answers { listOf(note) }
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
            coEvery { getByEncyclopedia(3L) } answers { records }
            coEvery { getByType(3L, "location") } answers { records.filter { it.entryType == "location" } }
        }
        val vm = createViewModel(dao, entries)
        vm.load(3L)
        vm.selectType("location")
        vm.setPreviewEntry(9L)
        vm.load(3L)
        assertEquals("location", vm.state.value.selectedType)
        assertEquals(listOf(place), vm.state.value.entries)
        assertEquals(9L, vm.state.value.previewEntryId)
        assertEquals(2, vm.state.value.pickerEntries.size)

        records = listOf(person)
        vm.load(3L)
        assertTrue(vm.state.value.entries.isEmpty())
        assertEquals(null, vm.state.value.previewEntryId)
        assertEquals(listOf(person), vm.state.value.pickerEntries)
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
            coEvery { getByEncyclopedia(3L) } returns listOf(entry)
            coEvery { getSedimentEntries(3L) } returns emptyList()
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
        assertFalse(viewModel.state.value.pickerEntries.isEmpty())
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
        coEvery { entries.getByEncyclopedia(3L) } coAnswers { gate.await() }
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
        coEvery { entries.getByType(3L, "location") } coAnswers { gate.await() }
        vm.selectType("location")
        vm.selectType("character")
        coEvery { entries.getByType(3L, "location") } returns listOf(latest)
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
        coEvery { entries.getByType(3L, "location") } coAnswers { gate.await() }
        vm.selectType("location")
        val latest = EncyclopediaEntryEntity(id = 10L, encyclopediaId = 3L, title = "新码头", entryType = "location")
        coEvery { entries.getByEncyclopedia(3L) } returns listOf(latest)
        coEvery { entries.getByType(3L, "location") } returns listOf(latest)
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
        coEvery { entries.getSedimentEntries(3L) } coAnswers { gate.await() }
        vm.setMainTab(EncyclopediaMainTab.SEDIMENT)
        val confirmed = EncyclopediaEntryEntity(id = 9L, encyclopediaId = 3L, title = "潮汐钟", confidence = "confirmed")
        coEvery { entries.getSedimentEntries(3L) } returns listOf(confirmed)
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
