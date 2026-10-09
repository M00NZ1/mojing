package com.mojing.app.ui.encyclopedia

import com.mojing.app.data.SecureStorage
import com.mojing.app.data.local.dao.EncyclopediaDao
import com.mojing.app.data.local.dao.EncyclopediaEntryDao
import com.mojing.app.data.local.dao.EncyclopediaEntryListItem
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
import kotlinx.coroutines.test.advanceUntilIdle
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

    private fun EncyclopediaEntryEntity.toListItem() = EncyclopediaEntryListItem(
        id = id,
        encyclopediaId = encyclopediaId,
        title = title,
        entryType = entryType,
        summary = summary,
        isFeatured = isFeatured,
        coverImagePath = coverImagePath,
        updatedAt = updatedAt,
    )

    private fun List<EncyclopediaEntryEntity>.toListItems() = map { it.toListItem() }

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
    fun standaloneTimelineEditKeepsIdentityAndPublishesSuccess() = runTest(dispatcher) {
        val encyclopediaDao = mockk<EncyclopediaDao>(relaxed = true)
        val timelineDao = mockk<TimelineEventDao>(relaxed = true)
        coEvery { encyclopediaDao.getById(3L) } returns EncyclopediaEntity(id = 3L, name = "世界")
        coEvery { timelineDao.updateStandalone(7L, 3L, "新标题", "新描述", "第二年", -4) } returns 1
        val vm = createViewModel(encyclopediaDao, timelineDao = timelineDao)
        vm.load(3L)
        vm.setMainTab(EncyclopediaMainTab.TIMELINE)
        val result = mutableListOf<Boolean>()

        vm.updateTimelineEvent(7L, "新标题", "新描述", "第二年", -4) { result += it }

        assertEquals(listOf(true), result)
        assertFalse(vm.state.value.timelineSaving)
        coVerify(exactly = 1) { timelineDao.updateStandalone(7L, 3L, "新标题", "新描述", "第二年", -4) }
    }

    @Test
    fun standaloneTimelineEditFailureKeepsRetryAvailable() = runTest(dispatcher) {
        val encyclopediaDao = mockk<EncyclopediaDao>(relaxed = true)
        val timelineDao = mockk<TimelineEventDao>(relaxed = true)
        coEvery { encyclopediaDao.getById(3L) } returns EncyclopediaEntity(id = 3L, name = "世界")
        coEvery { timelineDao.updateStandalone(any(), any(), any(), any(), any(), any()) } throws IllegalStateException("disk unavailable")
        val vm = createViewModel(encyclopediaDao, timelineDao = timelineDao)
        vm.load(3L)
        vm.setMainTab(EncyclopediaMainTab.TIMELINE)
        val result = mutableListOf<Boolean>()

        vm.updateTimelineEvent(7L, "保留标题", "保留描述", "时间", 2) { result += it }

        assertEquals(listOf(false), result)
        assertFalse(vm.state.value.timelineSaving)
        assertEquals("事件保存失败，输入已保留，请重试", vm.state.value.timelineSaveError)
        coEvery { timelineDao.updateStandalone(7L, 3L, "保留标题", "保留描述", "时间", 2) } returns 1
        vm.updateTimelineEvent(7L, "保留标题", "保留描述", "时间", 2) { result += it }
        assertEquals(listOf(false, true), result)
    }

    @Test
    fun standaloneTimelineEditTreatsMissingRowAsRecoverable() = runTest(dispatcher) {
        val encyclopediaDao = mockk<EncyclopediaDao>(relaxed = true)
        val timelineDao = mockk<TimelineEventDao>(relaxed = true)
        coEvery { encyclopediaDao.getById(3L) } returns EncyclopediaEntity(id = 3L, name = "世界")
        coEvery { timelineDao.updateStandalone(any(), any(), any(), any(), any(), any()) } returns 0
        val vm = createViewModel(encyclopediaDao, timelineDao = timelineDao)
        vm.load(3L)
        vm.setMainTab(EncyclopediaMainTab.TIMELINE)
        val result = mutableListOf<Boolean>()

        vm.updateTimelineEvent(7L, "标题", "描述", "时间", 1) { result += it }

        assertEquals(listOf(false), result)
        assertEquals("事件已不存在或不可编辑，请重新读取时间线", vm.state.value.timelineSaveError)
        assertFalse(vm.state.value.timelineSaving)
    }

    @Test
    fun delayedTimelineEditCannotPolluteAReopenedWorld() = runTest(dispatcher) {
        val encyclopediaDao = mockk<EncyclopediaDao>(relaxed = true)
        val timelineDao = mockk<TimelineEventDao>(relaxed = true)
        val gate = kotlinx.coroutines.CompletableDeferred<Int>()
        coEvery { encyclopediaDao.getById(any()) } answers { EncyclopediaEntity(id = firstArg(), name = "世界") }
        coEvery { timelineDao.updateStandalone(any(), any(), any(), any(), any(), any()) } coAnswers { gate.await() }
        val vm = createViewModel(encyclopediaDao, timelineDao = timelineDao)
        vm.load(3L)
        vm.setMainTab(EncyclopediaMainTab.TIMELINE)
        vm.updateTimelineEvent(7L, "旧世界", "旧描述", "旧时间", 1)
        vm.load(4L)

        assertTrue(vm.state.value.timelineSaving)
        gate.complete(1)
        assertFalse(vm.state.value.timelineSaving)
        assertEquals(null, vm.state.value.timelineSaveError)
    }

    @Test fun reloadingWorldKeepsSaveOwnerAndCommitClearsOnlyItsDraft() = runTest(dispatcher) {
        val worlds = mockk<EncyclopediaDao>(relaxed = true)
        val timeline = mockk<TimelineEventDao>(relaxed = true)
        val gate = kotlinx.coroutines.CompletableDeferred<Long>()
        coEvery { worlds.getById(any()) } answers { EncyclopediaEntity(id = firstArg(), name = "世界") }
        coEvery { timeline.upsert(any()) } coAnswers { gate.await() }
        val vm = createViewModel(worlds, timelineDao = timeline)
        vm.load(3L)
        vm.saveTimelineDraft(null, "title", "新事件")
        vm.addTimelineEvent("新事件", "说明", "元年", 2)
        vm.load(3L)
        assertTrue(vm.state.value.timelineSaving)
        vm.addTimelineEvent("重复点击", "说明", "元年", 2)
        coVerify(exactly = 1) { timeline.upsert(any()) }
        vm.load(4L)
        vm.saveTimelineDraft(null, "title", "另一个世界的草稿")
        gate.complete(77L)
        assertFalse(vm.state.value.timelineSaving)
        assertEquals("另一个世界的草稿", vm.timelineDraft(null, "title"))
        vm.load(3L)
        assertEquals("", vm.timelineDraft(null, "title"))
    }

    @Test fun committedTimelineEditKeepsNewerDraft() = runTest(dispatcher) {
        val worlds = mockk<EncyclopediaDao>(relaxed = true)
        val timeline = mockk<TimelineEventDao>(relaxed = true)
        val gate = kotlinx.coroutines.CompletableDeferred<Int>()
        coEvery { worlds.getById(3L) } returns EncyclopediaEntity(id = 3L, name = "世界")
        coEvery { timeline.updateStandalone(any(), any(), any(), any(), any(), any()) } coAnswers { gate.await() }
        val vm = createViewModel(worlds, timelineDao = timeline)
        vm.load(3L)
        vm.saveTimelineDraft(7L, "title", "提交标题")
        vm.updateTimelineEvent(7L, "提交标题", "说明", "元年", 1)
        vm.load(3L)
        vm.saveTimelineDraft(7L, "title", "后来的草稿")
        vm.updateTimelineEvent(7L, "后来的草稿", "说明", "元年", 1)
        coVerify(exactly = 1) { timeline.updateStandalone(any(), any(), any(), any(), any(), any()) }
        gate.complete(1)
        assertEquals("后来的草稿", vm.timelineDraft(7L, "title"))
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
    fun timelineDeleteKeepsOwnerAcrossSameWorldReloadAndRejectsDuplicate() = runTest(dispatcher) {
        val encyclopediaDao = mockk<EncyclopediaDao>(relaxed = true)
        val timelineDao = mockk<TimelineEventDao>(relaxed = true)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { encyclopediaDao.getById(3L) } returns EncyclopediaEntity(id = 3L, name = "世界")
        coEvery { timelineDao.delete(7L) } coAnswers { gate.await() }
        val vm = createViewModel(encyclopediaDao, timelineDao = timelineDao)
        vm.load(3L)
        vm.setMainTab(EncyclopediaMainTab.TIMELINE)
        val results = mutableListOf<Boolean>()

        vm.deleteTimelineEvent(7L) { results += it }
        vm.deleteTimelineEvent(7L) { results += it }
        vm.load(3L)
        assertEquals(7L, vm.state.value.timelineDeletingId)
        coVerify(exactly = 1) { timelineDao.delete(7L) }
        gate.complete(Unit)

        assertEquals(listOf(true), results)
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

    @Test fun relationEndpointsReadOnlyCurrent24EdgesIndependentOfEntryPage() = runTest(dispatcher) {
        val enc = mockk<EncyclopediaDao>(relaxed = true)
        val entries = mockk<EncyclopediaEntryDao>(relaxed = true)
        val relations = mockk<EntryRelationDao>(relaxed = true)
        coEvery { enc.getById(3) } returns EncyclopediaEntity(id = 3, name = "世界")
        val rows = (25L downTo 1L).map { EntryRelationEntity(id = it, encyclopediaId = 3, fromEntryId = it*2, toEntryId = it*2+1) }
        coEvery { relations.getPage(3, Long.MAX_VALUE, 25) } returns rows
        val ids = rows.take(24).flatMap { listOf(it.fromEntryId,it.toEntryId) }
        val projections = ids.map { com.mojing.app.data.local.dao.EncyclopediaRelationEndpoint(it,"页外$it","location","/$it.png") }
        coEvery { entries.getRelationEndpointsByIds(3, ids) } returns projections
        val vm = createViewModel(enc, entryDao = entries, relationDao = relations)
        vm.load(3);vm.setMainTab(EncyclopediaMainTab.GRAPH)
        assertTrue(vm.state.value.entries.isEmpty())
        assertEquals(48, vm.state.value.relationEndpoints.size)
        assertEquals("/50.png",vm.state.value.relationEndpoints[50]?.coverImagePath)
        coVerify(exactly = 1) { entries.getRelationEndpointsByIds(3, ids) }
        coVerify(exactly = 0) { entries.getByEncyclopedia(any()) }
    }

    @Test fun relationEndpointReadFailureKeepsPageAndCoversThenRetriesTogether() = runTest(dispatcher) {
        val enc = mockk<EncyclopediaDao>(relaxed = true)
        val entries = mockk<EncyclopediaEntryDao>(relaxed = true)
        val relations = mockk<EntryRelationDao>(relaxed = true)
        coEvery { enc.getById(3) } returns EncyclopediaEntity(id = 3, name = "世界")
        coEvery { relations.getPage(3, Long.MAX_VALUE, 25) } returns listOf(EntryRelationEntity(id=4,encyclopediaId=3,fromEntryId=10,toEntryId=20))
        val old = com.mojing.app.data.local.dao.EncyclopediaRelationEndpoint(10,"旧名","location","/old.png")
        coEvery { entries.getRelationEndpointsByIds(3, listOf(10,20)) } returns listOf(old)
        val vm = createViewModel(enc, entryDao = entries, relationDao = relations)
        vm.load(3);vm.setMainTab(EncyclopediaMainTab.GRAPH)
        coEvery { entries.getRelationEndpointsByIds(3, listOf(10,20)) } throws IllegalStateException("read")
        vm.retryRelationPage()
        assertEquals(old,vm.state.value.relationEndpoints[10])
        assertEquals(listOf(4L),vm.state.value.relations.map { it.id })
        assertEquals("关系读取失败，当前页已保留",vm.state.value.relationsLoadError)
        coEvery { entries.getRelationEndpointsByIds(3, listOf(10,20)) } returns listOf(old.copy(title="新名",coverImagePath="/new.png"))
        vm.retryRelationPage()
        assertEquals("/new.png",vm.state.value.relationEndpoints[10]?.coverImagePath)
        assertEquals(null,vm.state.value.relationsLoadError)
    }

    @Test fun lateRelationEndpointProjectionCannotReplaceAnotherWorld() = runTest(dispatcher) {
        val enc = mockk<EncyclopediaDao>(relaxed = true)
        val entries = mockk<EncyclopediaEntryDao>(relaxed = true)
        val relations = mockk<EntryRelationDao>(relaxed = true)
        coEvery { enc.getById(any()) } answers { EncyclopediaEntity(id=firstArg(),name="世界") }
        coEvery { relations.getPage(3, Long.MAX_VALUE, 25) } returns listOf(EntryRelationEntity(id=4,encyclopediaId=3,fromEntryId=10,toEntryId=20))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { entries.getRelationEndpointsByIds(3, listOf(10,20)) } coAnswers {
            gate.await();listOf(com.mojing.app.data.local.dao.EncyclopediaRelationEndpoint(10,"旧世界","location","/old.png"))
        }
        val vm = createViewModel(enc, entryDao = entries, relationDao = relations)
        vm.load(3);vm.setMainTab(EncyclopediaMainTab.GRAPH)
        vm.load(9);vm.setMainTab(EncyclopediaMainTab.GRAPH)
        gate.complete(Unit);advanceUntilIdle()
        assertEquals(9L,vm.state.value.encyclopedia?.id)
        assertTrue(vm.state.value.relationEndpoints.isEmpty())
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
    fun relationDeleteKeepsOwnerAcrossSameWorldReloadAndRejectsDuplicate() = runTest(dispatcher) {
        val encyclopediaDao = mockk<EncyclopediaDao>(relaxed = true)
        val relationDao = mockk<EntryRelationDao>(relaxed = true)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { encyclopediaDao.getById(3L) } returns EncyclopediaEntity(id = 3L, name = "世界")
        coEvery { relationDao.delete(7L) } coAnswers { gate.await() }
        val vm = createViewModel(encyclopediaDao, relationDao = relationDao)
        vm.load(3L)
        vm.setMainTab(EncyclopediaMainTab.GRAPH)
        val results = mutableListOf<Boolean>()

        vm.deleteRelation(7L) { results += it }
        vm.deleteRelation(7L) { results += it }
        vm.load(3L)
        assertEquals(7L, vm.state.value.relationDeletingId)
        coVerify(exactly = 1) { relationDao.delete(7L) }
        gate.complete(Unit)

        assertEquals(listOf(true), results)
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
        coEvery { entries.getEntryListPage(1, any(), "") } answers { rows.toListItems() }
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

    @Test fun singleConfirmationIgnoresDuplicateWhileWritingAndKeepsOtherEntryPending() = runTest(dispatcher) {
        val dao = mockk<EncyclopediaDao>(relaxed = true)
        val entries = mockk<EncyclopediaEntryDao>(relaxed = true)
        coEvery { dao.getById(1) } returns EncyclopediaEntity(id = 1, name = "世界")
        var rows = listOf(7L, 9L).map { EncyclopediaEntryEntity(id = it, encyclopediaId = 1, confidence = "inferred") }
        coEvery { entries.getSedimentPage(1, any(), any()) } answers { rows }
        coEvery { entries.getEntryListPage(1, any(), "") } answers { rows.toListItems() }
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { entries.confirmSedimentEntries(1, listOf(7), any()) } coAnswers {
            gate.await()
            rows = rows.map { if (it.id == 7L) it.copy(confidence = "confirmed") else it }
            1
        }
        val vm = createViewModel(dao, entries)
        vm.load(1)
        var callbacks = 0
        vm.confirmSedimentEntries(setOf(7)) { callbacks++ }
        assertTrue(vm.state.value.sedimentConfirming)
        vm.confirmSedimentEntries(setOf(7)) { callbacks++ }
        vm.confirmSedimentEntries(setOf(9)) { callbacks++ }
        assertTrue(vm.state.value.sedimentEntries.all { it.confidence == "inferred" })
        gate.complete(Unit)
        assertEquals(1, callbacks)
        assertFalse(vm.state.value.sedimentConfirming)
        assertEquals("confirmed", vm.state.value.sedimentEntries.first { it.id == 7L }.confidence)
        assertEquals("inferred", vm.state.value.sedimentEntries.first { it.id == 9L }.confidence)
        io.mockk.coVerify(exactly = 1) { entries.confirmSedimentEntries(1, listOf(7), any()) }
        io.mockk.coVerify(exactly = 0) { entries.confirmSedimentEntries(1, listOf(9), any()) }
    }

    @Test fun confirmationRefreshRetainsCurrentRowsAndBlocksStaleWritesUntilReadOrRetry() = runTest(dispatcher) {
        val dao = mockk<EncyclopediaDao>(relaxed = true)
        val entries = mockk<EncyclopediaEntryDao>(relaxed = true)
        coEvery { dao.getById(1) } returns EncyclopediaEntity(id = 1, name = "世界")
        var rows = listOf(7L, 9L).map { EncyclopediaEntryEntity(id = it, encyclopediaId = 1, confidence = "inferred") }
        var readGate: kotlinx.coroutines.CompletableDeferred<List<EncyclopediaEntryEntity>>? = null
        coEvery { entries.getSedimentPage(1, any(), any()) } coAnswers { readGate?.await() ?: rows }
        coEvery { entries.getEntryListPage(1, any(), "") } answers { rows.toListItems() }
        coEvery { entries.confirmSedimentEntries(1, any(), any()) } answers {
            val selected = secondArg<List<Long>>()
            rows = rows.map { if (it.id in selected) it.copy(confidence = "confirmed") else it }
            selected.size
        }
        val vm = createViewModel(dao, entries)
        vm.load(1); vm.setMainTab(EncyclopediaMainTab.SEDIMENT)
        readGate = kotlinx.coroutines.CompletableDeferred()
        vm.confirmSedimentEntries(setOf(7))
        assertTrue(vm.state.value.sedimentLoading)
        assertEquals(listOf(7L, 9L), vm.state.value.sedimentEntries.map { it.id })
        assertTrue(vm.state.value.sedimentEntries.all { it.confidence == "inferred" })
        vm.confirmSedimentEntries(setOf(9))
        io.mockk.coVerify(exactly = 0) { entries.confirmSedimentEntries(1, listOf(9), any()) }
        readGate!!.complete(rows)
        assertFalse(vm.state.value.sedimentLoading)
        assertEquals("confirmed", vm.state.value.sedimentEntries.first { it.id == 7L }.confidence)
        assertEquals("inferred", vm.state.value.sedimentEntries.first { it.id == 9L }.confidence)
        readGate = kotlinx.coroutines.CompletableDeferred()
        vm.confirmSedimentEntries(setOf(9))
        readGate!!.completeExceptionally(IllegalStateException("read failed"))
        assertFalse(vm.state.value.sedimentLoading)
        assertNotNull(vm.state.value.sedimentError)
        assertEquals(listOf(7L, 9L), vm.state.value.sedimentEntries.map { it.id })
        vm.confirmSedimentEntries(setOf(9))
        io.mockk.coVerify(exactly = 1) { entries.confirmSedimentEntries(1, listOf(9), any()) }
        readGate = null
        vm.reloadSediment()
        assertEquals(null, vm.state.value.sedimentError)
        assertTrue(vm.state.value.sedimentEntries.all { it.confidence == "confirmed" })
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
            coEvery { getEntryListPage(3L, any(), any()) } answers {
                records.filter { it.id > secondArg<Long>() }.take(101).toListItems()
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
        val gate = kotlinx.coroutines.CompletableDeferred<List<EncyclopediaEntryListItem>>()
        coEvery { entries.getEntryListPage(3L, 100, "location") } coAnswers { gate.await() }
        vm.nextEntryPage()
        vm.nextEntryPage()
        assertEquals(listOf(0L, 100L), vm.state.value.entryCursors)
        gate.completeExceptionally(IllegalStateException("read failure"))
        assertNotNull(vm.state.value.entriesError)
        coEvery { entries.getEntryListPage(3L, 100, "location") } returns records.drop(100).take(101).toListItems()
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
    fun selectingEntryLoadsFullPreviewByIdWithoutPuttingContentInListState() = runTest(dispatcher) {
        val encyclopediaDao = mockk<EncyclopediaDao> {
            coEvery { getById(3L) } returns EncyclopediaEntity(id = 3L, name = "雾海")
        }
        val full = EncyclopediaEntryEntity(
            id = 7L, encyclopediaId = 3L, title = "守灯人", entryType = "character",
            summary = "摘要", content = "完整正文", metaJson = "{\"large\":\"正文外资料\"}",
        )
        val entries = mockk<EncyclopediaEntryDao> {
            coEvery { getEntryListPage(3L, 0L, "") } returns listOf(full.toListItem())
            coEvery { countEntries(3L, any()) } returns 1
            coEvery { getWorldTypeCounts(3L) } returns emptyList()
            coEvery { getById(7L) } returns full
        }
        val vm = createViewModel(encyclopediaDao, entries)

        vm.load(3L)
        assertEquals(listOf(7L), vm.state.value.entries.map { it.id })
        coVerify(exactly = 0) { entries.getById(any()) }
        vm.setPreviewEntry(7L)

        assertEquals(7L, vm.state.value.previewEntryId)
        assertEquals(full, vm.state.value.previewEntry)
        assertFalse(vm.state.value.previewLoading)
        assertEquals(null, vm.state.value.previewError)
    }

    @Test
    fun previewFailureKeepsTargetAndRetryLoadsFullEntry() = runTest(dispatcher) {
        val encyclopediaDao = mockk<EncyclopediaDao> {
            coEvery { getById(3L) } returns EncyclopediaEntity(id = 3L, name = "雾海")
        }
        val full = EncyclopediaEntryEntity(id = 9L, encyclopediaId = 3L, title = "港湾", content = "正文")
        val entries = mockk<EncyclopediaEntryDao> {
            coEvery { getEntryListPage(3L, 0L, "") } returns listOf(full.toListItem())
            coEvery { countEntries(3L, any()) } returns 1
            coEvery { getWorldTypeCounts(3L) } returns emptyList()
            coEvery { getById(9L) } throws IllegalStateException("read failure") andThen full
        }
        val vm = createViewModel(encyclopediaDao, entries)

        vm.load(3L)
        vm.setPreviewEntry(9L)
        assertEquals(9L, vm.state.value.previewEntryId)
        assertEquals("预览读取失败，请重试", vm.state.value.previewError)
        assertEquals(null, vm.state.value.previewEntry)

        vm.retryEntryPreview()
        assertEquals(full, vm.state.value.previewEntry)
        assertEquals(null, vm.state.value.previewError)
        coVerify(exactly = 2) { entries.getById(9L) }
    }

    @Test
    fun missingOrCrossEncyclopediaPreviewIsUnavailableUntilListRefresh() = runTest(dispatcher) {
        val encyclopediaDao = mockk<EncyclopediaDao> {
            coEvery { getById(3L) } returns EncyclopediaEntity(id = 3L, name = "雾海")
        }
        val missing = EncyclopediaEntryEntity(id = 9L, encyclopediaId = 3L, title = "已删除")
        val moved = EncyclopediaEntryEntity(id = 10L, encyclopediaId = 4L, title = "已移动")
        val entries = mockk<EncyclopediaEntryDao> {
            coEvery { getEntryListPage(3L, 0L, "") } returns listOf(missing.toListItem(), moved.copy(encyclopediaId = 3L).toListItem())
            coEvery { countEntries(3L, any()) } returns 2
            coEvery { getWorldTypeCounts(3L) } returns emptyList()
            coEvery { getById(9L) } returns null
            coEvery { getById(10L) } returns moved
        }
        val vm = createViewModel(encyclopediaDao, entries)

        vm.load(3L)
        vm.setPreviewEntry(9L)
        assertEquals("条目已不可用，请刷新列表", vm.state.value.previewError)
        vm.setPreviewEntry(10L)

        assertEquals(10L, vm.state.value.previewEntryId)
        assertEquals("条目已不可用，请刷新列表", vm.state.value.previewError)
        assertEquals(null, vm.state.value.previewEntry)
    }

    @Test
    fun latePreviewForOldTargetCannotOverwriteNewTarget() = runTest(dispatcher) {
        val encyclopediaDao = mockk<EncyclopediaDao> {
            coEvery { getById(3L) } returns EncyclopediaEntity(id = 3L, name = "雾海")
        }
        val old = EncyclopediaEntryEntity(id = 7L, encyclopediaId = 3L, title = "旧", content = "旧正文")
        val latest = EncyclopediaEntryEntity(id = 8L, encyclopediaId = 3L, title = "新", content = "新正文")
        val gate = kotlinx.coroutines.CompletableDeferred<EncyclopediaEntryEntity>()
        val entries = mockk<EncyclopediaEntryDao> {
            coEvery { getEntryListPage(3L, 0L, "") } returns listOf(old.toListItem(), latest.toListItem())
            coEvery { countEntries(3L, any()) } returns 2
            coEvery { getWorldTypeCounts(3L) } returns emptyList()
            coEvery { getById(7L) } coAnswers {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() }
            }
            coEvery { getById(8L) } returns latest
        }
        val vm = createViewModel(encyclopediaDao, entries)

        vm.load(3L)
        vm.setPreviewEntry(7L)
        vm.setPreviewEntry(8L)
        assertEquals(latest, vm.state.value.previewEntry)
        gate.complete(old)
        advanceUntilIdle()

        assertEquals(8L, vm.state.value.previewEntryId)
        assertEquals(latest, vm.state.value.previewEntry)
    }

    @Test
    fun changingFilterClearsPreviewAndLateFullReadCannotReturnToNewList() = runTest(dispatcher) {
        val encyclopediaDao = mockk<EncyclopediaDao> {
            coEvery { getById(3L) } returns EncyclopediaEntity(id = 3L, name = "雾海")
        }
        val old = EncyclopediaEntryEntity(id = 7L, encyclopediaId = 3L, title = "旧", entryType = "location")
        val latest = EncyclopediaEntryEntity(id = 8L, encyclopediaId = 3L, title = "新", entryType = "character")
        val entries = mockk<EncyclopediaEntryDao> {
            coEvery { getEntryListPage(3L, any(), "") } returns listOf(old.toListItem())
            coEvery { getEntryListPage(3L, any(), "character") } returns listOf(latest.toListItem())
            coEvery { countEntries(3L, any()) } returns 1
            coEvery { getWorldTypeCounts(3L) } returns emptyList()
            coEvery { getById(7L) } returns old
        }
        val vm = createViewModel(encyclopediaDao, entries)

        vm.load(3L)
        vm.setPreviewEntry(7L)
        assertEquals(old, vm.state.value.previewEntry)
        vm.selectType("character")
        advanceUntilIdle()

        assertEquals("character", vm.state.value.selectedType)
        assertEquals(listOf(8L), vm.state.value.entries.map { it.id })
        assertEquals(null, vm.state.value.previewEntryId)
        assertEquals(null, vm.state.value.previewEntry)
    }

    @Test
    fun changingEncyclopediaInvalidatesLatePreviewRead() = runTest(dispatcher) {
        val encyclopediaDao = mockk<EncyclopediaDao> {
            coEvery { getById(3L) } returns EncyclopediaEntity(id = 3L, name = "旧世界")
            coEvery { getById(4L) } returns EncyclopediaEntity(id = 4L, name = "新世界")
        }
        val old = EncyclopediaEntryEntity(id = 7L, encyclopediaId = 3L, title = "旧", content = "旧正文")
        val latest = EncyclopediaEntryEntity(id = 8L, encyclopediaId = 4L, title = "新", content = "新正文")
        val gate = kotlinx.coroutines.CompletableDeferred<EncyclopediaEntryEntity>()
        val entries = mockk<EncyclopediaEntryDao> {
            coEvery { getEntryListPage(3L, any(), any()) } returns listOf(old.toListItem())
            coEvery { getEntryListPage(4L, any(), any()) } returns listOf(latest.toListItem())
            coEvery { countEntries(any(), any()) } returns 1
            coEvery { getWorldTypeCounts(any()) } returns emptyList()
            coEvery { getById(7L) } coAnswers {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() }
            }
        }
        val vm = createViewModel(encyclopediaDao, entries)

        vm.load(3L)
        vm.setPreviewEntry(7L)
        vm.load(4L)
        gate.complete(old)
        advanceUntilIdle()

        assertEquals(4L, vm.state.value.encyclopedia?.id)
        assertEquals(listOf(8L), vm.state.value.entries.map { it.id })
        assertEquals(null, vm.state.value.previewEntryId)
        assertEquals(null, vm.state.value.previewEntry)
    }

    @Test
    fun movingToNextEntryPageClearsPreviewBeforeLateOldReadCompletes() = runTest(dispatcher) {
        val encyclopediaDao = mockk<EncyclopediaDao> {
            coEvery { getById(3L) } returns EncyclopediaEntity(id = 3L, name = "雾海")
        }
        val old = EncyclopediaEntryEntity(id = 7L, encyclopediaId = 3L, title = "旧", content = "旧正文")
        val next = EncyclopediaEntryEntity(id = 101L, encyclopediaId = 3L, title = "新", content = "新正文")
        val gate = kotlinx.coroutines.CompletableDeferred<EncyclopediaEntryEntity>()
        val firstPage = (1L..101L).map { id ->
            EncyclopediaEntryEntity(id = id, encyclopediaId = 3L, title = if (id == 7L) old.title else "条目$id").toListItem()
        }
        val entries = mockk<EncyclopediaEntryDao> {
            coEvery { getEntryListPage(3L, 0L, "") } returns firstPage
            coEvery { getEntryListPage(3L, 100L, "") } returns listOf(next.toListItem())
            coEvery { countEntries(3L, any()) } returns 102
            coEvery { getWorldTypeCounts(3L) } returns emptyList()
            coEvery { getById(7L) } coAnswers {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() }
            }
        }
        val vm = createViewModel(encyclopediaDao, entries)

        vm.load(3L)
        vm.setPreviewEntry(7L)
        vm.nextEntryPage()
        gate.complete(old)
        advanceUntilIdle()

        assertEquals(listOf(101L), vm.state.value.entries.map { it.id })
        assertEquals(null, vm.state.value.previewEntryId)
        assertEquals(null, vm.state.value.previewEntry)
    }

    @Test
    fun deletingLastEntryOnLaterPageReturnsToPreviousPage() = runTest(dispatcher) {
        val dao = mockk<EncyclopediaDao> {
            coEvery { getById(3L) } returns EncyclopediaEntity(id = 3L, name = "雾海")
        }
        var records = (1L..101L).map { EncyclopediaEntryEntity(id = it, encyclopediaId = 3L) }
        val entries = mockk<EncyclopediaEntryDao>(relaxed = true) {
            coEvery { getEntryListPage(3L, any(), any()) } answers { records.filter { it.id > secondArg<Long>() }.take(101).toListItems() }
            coEvery { countEntries(3L, any()) } answers { records.size }
        }
        val deleter = mockk<DeleteEncyclopediaEntryUseCase>()
        coEvery { deleter(101L, 3L) } coAnswers { records = records.dropLast(1); true }
        val vm = createViewModel(dao, entries, deleteUseCase = deleter)

        vm.load(3L)
        vm.selectType("location")
        vm.nextEntryPage()
        assertEquals("location", vm.state.value.selectedType)
        assertEquals(listOf(101L), vm.state.value.entries.map { it.id })
        vm.deleteEntry(101L)

        assertEquals(listOf(0L), vm.state.value.entryCursors)
        assertEquals(100, vm.state.value.entries.size)
        assertEquals(null, vm.state.value.entryDeleteError)
    }

    @Test
    fun failedEntryDeleteKeepsDialogRetryable() = runTest(dispatcher) {
        val dao = mockk<EncyclopediaDao> {
            coEvery { getById(3L) } returns EncyclopediaEntity(id = 3L, name = "雾海")
        }
        val deleter = mockk<DeleteEncyclopediaEntryUseCase>()
        var shouldFail = true
        coEvery { deleter(9L, 3L) } coAnswers {
            if (shouldFail) {
                shouldFail = false
                throw IllegalStateException("disk unavailable")
            }
            true
        }
        val vm = createViewModel(dao, deleteUseCase = deleter)
        vm.load(3L)
        val results = mutableListOf<Boolean>()

        vm.deleteEntry(9L) { results += it }

        assertEquals(listOf(false), results)
        assertEquals("删除失败，请重试", vm.state.value.entryDeleteError)
        assertEquals(null, vm.state.value.entryDeletingId)

        vm.deleteEntry(9L) { results += it }
        assertEquals(listOf(false, true), results)
        assertEquals(null, vm.state.value.entryDeleteError)
        assertEquals(null, vm.state.value.entryDeletingId)
    }

    @Test
    fun entryDeleteIgnoresSecondRequestWhileWriteIsInFlight() = runTest(dispatcher) {
        val dao = mockk<EncyclopediaDao> {
            coEvery { getById(3L) } returns EncyclopediaEntity(id = 3L, name = "雾海")
        }
        val gate = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val deleter = mockk<DeleteEncyclopediaEntryUseCase>()
        coEvery { deleter(9L, 3L) } coAnswers { gate.await() }
        val vm = createViewModel(dao, deleteUseCase = deleter)
        vm.load(3L)

        vm.deleteEntry(9L)
        vm.deleteEntry(9L)
        coVerify(exactly = 1) { deleter(9L, 3L) }

        gate.complete(true)
        assertEquals(null, vm.state.value.entryDeletingId)
    }

    @Test
    fun delayedEntryDeleteDoesNotUpdateReopenedEncyclopedia() = runTest(dispatcher) {
        val dao = mockk<EncyclopediaDao>(relaxed = true) {
            coEvery { getById(any()) } answers { EncyclopediaEntity(id = firstArg(), name = "世界") }
        }
        val gate = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val deleter = mockk<DeleteEncyclopediaEntryUseCase>()
        coEvery { deleter(9L, 3L) } coAnswers { gate.await() }
        val vm = createViewModel(dao, deleteUseCase = deleter)
        vm.load(3L)
        val results = mutableListOf<Boolean>()

        vm.deleteEntry(9L) { results += it }
        vm.load(4L)
        gate.complete(true)

        assertEquals(emptyList<Boolean>(), results)
        assertEquals(4L, vm.state.value.encyclopedia?.id)
        assertEquals(null, vm.state.value.entryDeleteError)
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
        coEvery { relations.upsertIfEndpointsBelongToEncyclopedia(any()) } throws IllegalStateException("disk full")
        var saves = 0
        vm.addRelation(10, 20, "盟友", "备注") { saves++ }
        assertEquals(0, saves)
        assertFalse(vm.state.value.relationSaving)
        assertEquals("关系保存失败，输入已保留，请重试", vm.state.value.relationError)
        coEvery { relations.upsertIfEndpointsBelongToEncyclopedia(any()) } returns true
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
        coEvery { relations.upsertIfEndpointsBelongToEncyclopedia(any()) } returns true
        coEvery { relations.getPage(1, Long.MAX_VALUE, 25) } throws IllegalStateException("read failed")
        var saved = false
        vm.addRelation(10, 20, "盟友", "") { saved = true }
        assertTrue(saved)
        assertEquals(null, vm.state.value.relationError)
        assertEquals("关系读取失败，当前页已保留", vm.state.value.relationsLoadError)
    }

    @Test fun relationRejectsEndpointsOutsideCurrentEncyclopediaAndKeepsDialogOpen() = runTest(dispatcher) {
        val dao = mockk<EncyclopediaDao>(relaxed = true)
        val relations = mockk<EntryRelationDao>(relaxed = true)
        coEvery { dao.getById(1) } returns EncyclopediaEntity(id = 1, name = "当前世界")
        coEvery { relations.upsertIfEndpointsBelongToEncyclopedia(any()) } returns false
        val vm = createViewModel(dao, relationDao = relations)
        vm.load(1)
        var saved = false
        vm.addRelation(10, 20, "盟友", "备注") { saved = true }
        assertFalse(saved)
        assertFalse(vm.state.value.relationSaving)
        assertEquals("两个端点必须属于当前百科，输入已保留，请重新选择", vm.state.value.relationError)
    }

    @Test fun pendingRelationWriteRejectsDuplicatesAndDoesNotCloseAnotherWorldEditor() = runTest(dispatcher) {
        val dao = mockk<EncyclopediaDao>(relaxed = true)
        val relations = mockk<EntryRelationDao>(relaxed = true)
        coEvery { dao.getById(any()) } answers { EncyclopediaEntity(id = firstArg(), name = "世界") }
        val pending = kotlinx.coroutines.CompletableDeferred<Long>()
        coEvery { relations.upsertIfEndpointsBelongToEncyclopedia(any()) } coAnswers { pending.await(); true }
        val vm = createViewModel(dao, relationDao = relations)
        vm.load(1)
        var saves = 0
        vm.addRelation(10, 20, "盟友", "") { saves++ }
        vm.addRelation(10, 20, "盟友", "") { saves++ }
        assertTrue(vm.state.value.relationSaving)
        vm.load(2)
        assertFalse(vm.state.value.relationSaving)
        pending.complete(1L)
        assertFalse(vm.state.value.relationSaving)
        assertEquals(0, saves)
        assertEquals(null, vm.state.value.relationError)
        io.mockk.coVerify(exactly = 1) { relations.upsertIfEndpointsBelongToEncyclopedia(match { it.encyclopediaId == 1L }) }
        io.mockk.coVerify(exactly = 0) { relations.upsertIfEndpointsBelongToEncyclopedia(match { it.encyclopediaId == 2L }) }
    }

    @Test fun sameEncyclopediaReloadKeepsRelationWriteOwner() = runTest(dispatcher) {
        val dao = mockk<EncyclopediaDao>(relaxed = true)
        val relations = mockk<EntryRelationDao>(relaxed = true)
        coEvery { dao.getById(1) } returns EncyclopediaEntity(id = 1, name = "世界")
        val pending = kotlinx.coroutines.CompletableDeferred<Boolean>()
        coEvery { relations.upsertIfEndpointsBelongToEncyclopedia(any()) } coAnswers { pending.await() }
        val vm = createViewModel(dao, relationDao = relations)
        vm.load(1)
        var saved = false
        vm.addRelation(10, 20, "盟友", "") { saved = true }
        vm.load(1)
        assertTrue(vm.state.value.relationSaving)
        pending.complete(true)
        advanceUntilIdle()
        assertTrue(saved)
        assertFalse(vm.state.value.relationSaving)
    }

    @Test fun relationTypeBoundariesUse24PlusOneAndIndependentEntryType() = runTest(dispatcher) {
        for (count in listOf(0, 1, 23, 24, 25)) {
            val enc = mockk<EncyclopediaDao>(relaxed = true)
            val dao = mockk<EntryRelationDao>(relaxed = true)
            coEvery { enc.getById(3) } returns EncyclopediaEntity(id = 3, name = "世界")
            coEvery { dao.getTypePage(3, Long.MAX_VALUE, "location", 25) } returns
                (count.toLong() downTo 1L).map { EntryRelationEntity(id = it, encyclopediaId = 3, fromEntryId = it * 2, toEntryId = it * 2 + 1) }
            val vm = createViewModel(enc, relationDao = dao)
            vm.load(3); vm.selectType("character"); vm.setMainTab(EncyclopediaMainTab.GRAPH); vm.selectRelationType("location")
            assertEquals(count.coerceAtMost(24), vm.state.value.relations.size)
            assertEquals(count > 24, vm.state.value.relationsHasNext)
            assertEquals("character", vm.state.value.selectedType)
            coVerify { dao.getTypePage(3, Long.MAX_VALUE, "location", 25) }
            coVerify(exactly = 0) { dao.getByEncyclopedia(any()) }
        }
    }

    @Test fun relationFilterFailureRetriesSameTypeCursorAndRetainsBothEndpoints() = runTest(dispatcher) {
        val enc = mockk<EncyclopediaDao>(relaxed = true)
        val dao = mockk<EntryRelationDao>(relaxed = true)
        val entries = mockk<EncyclopediaEntryDao>(relaxed = true)
        coEvery { enc.getById(3) } returns EncyclopediaEntity(id = 3, name = "世界")
        val rows = (25L downTo 1L).map { EntryRelationEntity(id=it,encyclopediaId=3,fromEntryId=10,toEntryId=20) }
        coEvery { dao.getTypePage(3, Long.MAX_VALUE, "location", 25) } returns rows
        coEvery { entries.getRelationEndpointsByIds(3,listOf(10,20)) } returns listOf(
            com.mojing.app.data.local.dao.EncyclopediaRelationEndpoint(10,"人物端","character",""),
            com.mojing.app.data.local.dao.EncyclopediaRelationEndpoint(20,"地点端","location",""))
        coEvery { dao.getTypePage(3, 2, "location", 25) } throws IllegalStateException("read")
        val vm=createViewModel(enc,entryDao=entries,relationDao=dao)
        vm.load(3);vm.setMainTab(EncyclopediaMainTab.GRAPH);vm.selectRelationType("location");vm.nextRelationPage()
        assertEquals(24,vm.state.value.relations.size);assertEquals(0,vm.state.value.relationPageIndex)
        assertEquals(setOf(10L,20L),vm.state.value.relationEndpoints.keys)
        assertNotNull(vm.state.value.relationsLoadError)
        coEvery { dao.getTypePage(3, 2, "location", 25) } returns rows.takeLast(1)
        vm.retryRelationPage()
        assertEquals(listOf(1L),vm.state.value.relations.map { it.id });assertEquals(1,vm.state.value.relationPageIndex)
        assertEquals("location",vm.state.value.relationTypeFilter)
    }

    @Test fun oldRelationFilterProjectionCannotReplaceNewFilterOrPublishError() = runTest(dispatcher) {
        val enc=mockk<EncyclopediaDao>(relaxed=true);val dao=mockk<EntryRelationDao>(relaxed=true)
        val entries=mockk<EncyclopediaEntryDao>(relaxed=true)
        coEvery { enc.getById(3) } returns EncyclopediaEntity(id=3,name="世界")
        coEvery { dao.getTypePage(3,Long.MAX_VALUE,"location",25) } returns listOf(EntryRelationEntity(id=3,encyclopediaId=3,fromEntryId=10,toEntryId=20))
        val gate=kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { entries.getRelationEndpointsByIds(3,listOf(10,20)) } coAnswers { gate.await();throw IllegalStateException("late") }
        coEvery { dao.getTypePage(3,Long.MAX_VALUE,"faction",25) } returns listOf(EntryRelationEntity(id=4,encyclopediaId=3,fromEntryId=30,toEntryId=40))
        val vm=createViewModel(enc,entryDao=entries,relationDao=dao)
        vm.load(3);vm.setMainTab(EncyclopediaMainTab.GRAPH);vm.selectRelationType("location");vm.selectRelationType("faction")
        gate.complete(Unit);advanceUntilIdle()
        assertEquals("faction",vm.state.value.relationTypeFilter);assertEquals(listOf(4L),vm.state.value.relations.map { it.id })
        assertEquals(null,vm.state.value.relationsLoadError);assertFalse(vm.state.value.relationsLoading)
    }

    @Test fun relationFilterSwitchClearsFailedCursorAndTabSwitchIsolatesLateRead() = runTest(dispatcher) {
        val enc=mockk<EncyclopediaDao>(relaxed=true);val dao=mockk<EntryRelationDao>(relaxed=true)
        coEvery { enc.getById(3) } returns EncyclopediaEntity(id=3,name="世界")
        val gate=kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { dao.getTypePage(3,Long.MAX_VALUE,"location",25) } coAnswers { gate.await();emptyList() }
        val vm=createViewModel(enc,relationDao=dao)
        vm.load(3);vm.setMainTab(EncyclopediaMainTab.GRAPH);vm.selectRelationType("location");vm.setMainTab(EncyclopediaMainTab.ENTRIES)
        gate.complete(Unit);advanceUntilIdle()
        assertFalse(vm.state.value.relationsLoading);assertEquals(EncyclopediaMainTab.ENTRIES,vm.state.value.mainTab)
        vm.setMainTab(EncyclopediaMainTab.GRAPH)
        assertTrue(vm.state.value.relationsLoaded);assertEquals("location",vm.state.value.relationTypeFilter)
        vm.selectRelationType("faction");assertEquals(listOf(Long.MAX_VALUE),vm.state.value.relationCursors)
        assertEquals(null,vm.state.value.relationRetryIndex)
    }

    @Test fun relationFilterPageRestoresFromFreshSavedStateAndSameWorldReload() = runTest(dispatcher) {
        val enc=mockk<EncyclopediaDao>(relaxed=true);val dao=mockk<EntryRelationDao>(relaxed=true)
        coEvery { enc.getById(3) } returns EncyclopediaEntity(id=3,name="世界")
        val rows=(25L downTo 1L).map { EntryRelationEntity(id=it,encyclopediaId=3,fromEntryId=10,toEntryId=20) }
        coEvery { dao.getTypePage(3,Long.MAX_VALUE,"location",25) } returns rows
        coEvery { dao.getTypePage(3,2,"location",25) } returns rows.takeLast(1)
        val saved=androidx.lifecycle.SavedStateHandle()
        val vm=createViewModel(enc,relationDao=dao,savedStateHandle=saved)
        vm.load(3);vm.setMainTab(EncyclopediaMainTab.GRAPH);vm.selectRelationType("location");vm.nextRelationPage();vm.load(3)
        assertEquals(1,vm.state.value.relationPageIndex);assertEquals(listOf(1L),vm.state.value.relations.map { it.id })
        val fresh=androidx.lifecycle.SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })
        val restored=createViewModel(enc,relationDao=dao,savedStateHandle=fresh);restored.load(3)
        assertEquals(EncyclopediaMainTab.GRAPH,restored.state.value.mainTab);assertEquals("location",restored.state.value.relationTypeFilter)
        assertEquals(1,restored.state.value.relationPageIndex);assertEquals(listOf(1L),restored.state.value.relations.map { it.id })
        restored.load(4);assertEquals("",restored.state.value.relationTypeFilter)
    }

    @Test fun relationFilterInvalidSavedCriteriaFallsBackToAllFirstPage() = runTest(dispatcher) {
        val enc=mockk<EncyclopediaDao>(relaxed=true)
        coEvery { enc.getById(3) } returns EncyclopediaEntity(id=3,name="世界")
        for (cursors in listOf(longArrayOf(5,6), longArrayOf(Long.MAX_VALUE,0),LongArray(129) { Long.MAX_VALUE-it })) {
            val vm=createViewModel(enc,savedStateHandle=androidx.lifecycle.SavedStateHandle(mapOf("world_tab_3" to "GRAPH", "relation_type_3" to "守护", "relation_cursors_3" to cursors)))
            vm.load(3);assertEquals("",vm.state.value.relationTypeFilter);assertEquals(listOf(Long.MAX_VALUE),vm.state.value.relationCursors)
        }
    }

    @Test fun relationWriteDeleteAndEmptyPageRetreatKeepType() = runTest(dispatcher) {
        val enc=mockk<EncyclopediaDao>(relaxed=true);val dao=mockk<EntryRelationDao>(relaxed=true)
        coEvery { enc.getById(3) } returns EncyclopediaEntity(id=3,name="世界")
        val rows=(25L downTo 1L).map { EntryRelationEntity(id=it,encyclopediaId=3,fromEntryId=10,toEntryId=20) }
        coEvery { dao.getTypePage(3,Long.MAX_VALUE,"location",25) } returns rows
        coEvery { dao.getTypePage(3,2,"location",25) } returns rows.takeLast(1)
        coEvery { dao.upsertIfEndpointsBelongToEncyclopedia(any()) } returns true
        val vm=createViewModel(enc,relationDao=dao);vm.load(3);vm.setMainTab(EncyclopediaMainTab.GRAPH);vm.selectRelationType("location");vm.nextRelationPage()
        vm.addRelation(10,20,"守护","")
        assertEquals(1,vm.state.value.relationPageIndex);assertEquals("location",vm.state.value.relationTypeFilter)
        coEvery { dao.getTypePage(3,2,"location",25) } returns emptyList()
        vm.deleteRelation(1);assertEquals(0,vm.state.value.relationPageIndex);assertEquals("location",vm.state.value.relationTypeFilter)
        vm.addRelation(10,20,"守护","");assertEquals("location",vm.state.value.relationTypeFilter)
        coVerify { dao.getTypePage(3,Long.MAX_VALUE,"location",25) }
    }

    @Test fun timelinePairPageRestoresAcrossNewOwnerAndSameWorldReload() = runTest(dispatcher) {
        val enc=mockk<EncyclopediaDao>(relaxed=true)
        val dao=mockk<TimelineEventDao>(relaxed=true)
        coEvery { enc.getById(3) } returns EncyclopediaEntity(id=3,name="世界")
        val rows=(1L..30L).map { TimelineEventEntity(id=it,encyclopediaId=3,sortOrder=if(it<=24) -4 else 7) }
        coEvery { dao.getPage(3,null,null,25) } returns rows.take(25)
        coEvery { dao.getPage(3,-4,24,25) } returns rows.drop(24)
        val saved=androidx.lifecycle.SavedStateHandle()
        val vm=createViewModel(enc,timelineDao=dao,savedStateHandle=saved)
        vm.load(3);vm.setMainTab(EncyclopediaMainTab.TIMELINE);vm.nextTimelinePage();vm.load(3)
        assertEquals(1,vm.state.value.timelinePageIndex)
        assertEquals(listOf(25L,26L,27L,28L,29L,30L),vm.state.value.timelineEvents.map { it.id })
        val fresh=androidx.lifecycle.SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })
        val restored=createViewModel(enc,timelineDao=dao,savedStateHandle=fresh);restored.load(3)
        assertEquals(1,restored.state.value.timelinePageIndex)
        assertEquals(25L,restored.state.value.timelineEvents.first().id)
        coVerify(exactly=0) { dao.getByEncyclopedia(any()) }
        restored.previousTimelinePage();assertEquals(0,restored.state.value.timelinePageIndex)
        assertTrue(fresh.get<LongArray>("timeline_cursors_3")!!.isEmpty())
    }

    @Test fun invalidTimelinePairCursorsRestoreFirstPage() = runTest(dispatcher) {
        val enc=mockk<EncyclopediaDao>(relaxed=true)
        coEvery { enc.getById(3) } returns EncyclopediaEntity(id=3,name="世界")
        listOf(longArrayOf(0),longArrayOf(0,0),longArrayOf(Int.MAX_VALUE.toLong()+1,1),
            longArrayOf(3,2,2,3),longArrayOf(3,2,3,2),LongArray(256)).forEach { pairs ->
            val vm=createViewModel(enc,savedStateHandle=androidx.lifecycle.SavedStateHandle(mapOf("world_tab_3" to "TIMELINE","timeline_cursors_3" to pairs)))
            vm.load(3);assertEquals(listOf<TimelinePageCursor?>(null),vm.state.value.timelineCursors)
            assertEquals(0,vm.state.value.timelinePageIndex)
        }
    }

    @Test fun restoredTimelineEmptyDeepPageFallsBackAndPersistsRealPair() = runTest(dispatcher) {
        val enc=mockk<EncyclopediaDao>(relaxed=true);val dao=mockk<TimelineEventDao>(relaxed=true)
        coEvery { enc.getById(3) } returns EncyclopediaEntity(id=3,name="世界")
        coEvery { dao.getPage(3,4,24,25) } returns emptyList()
        coEvery { dao.getPage(3,null,null,25) } returns listOf(TimelineEventEntity(id=1,encyclopediaId=3))
        val saved=androidx.lifecycle.SavedStateHandle(mapOf("world_tab_3" to "TIMELINE","timeline_cursors_3" to longArrayOf(4,24)))
        val vm=createViewModel(enc,timelineDao=dao,savedStateHandle=saved);vm.load(3)
        assertEquals(0,vm.state.value.timelinePageIndex);assertEquals(1L,vm.state.value.timelineEvents.single().id)
        assertTrue(saved.get<LongArray>("timeline_cursors_3")!!.isEmpty())
    }

    @Test fun failedTimelinePairDoesNotAdvanceAndRetriesOriginalPair() = runTest(dispatcher) {
        val enc=mockk<EncyclopediaDao>(relaxed=true);val dao=mockk<TimelineEventDao>(relaxed=true)
        coEvery { enc.getById(3) } returns EncyclopediaEntity(id=3,name="世界")
        val rows=(1L..25L).map { TimelineEventEntity(id=it,encyclopediaId=3,sortOrder=-7) }
        coEvery { dao.getPage(3,null,null,25) } returns rows
        coEvery { dao.getPage(3,-7,24,25) } throws IllegalStateException("read failed")
        val saved=androidx.lifecycle.SavedStateHandle();val vm=createViewModel(enc,timelineDao=dao,savedStateHandle=saved)
        vm.load(3);vm.setMainTab(EncyclopediaMainTab.TIMELINE);vm.nextTimelinePage();vm.nextTimelinePage()
        assertEquals(0,vm.state.value.timelinePageIndex);assertEquals(24,vm.state.value.timelineEvents.size)
        coVerify(exactly=1) { dao.getPage(3,-7,24,25) }
        assertTrue(saved.get<LongArray>("timeline_cursors_3")!!.isEmpty())
        coEvery { dao.getPage(3,-7,24,25) } returns rows.drop(24)
        vm.retryTimelinePage();assertEquals(1,vm.state.value.timelinePageIndex)
        assertEquals(25L,vm.state.value.timelineEvents.single().id)
    }

    @Test fun timelineDraftRestoresWithCorrectWorldAndEventIdentity() = runTest(dispatcher) {
        val enc=mockk<EncyclopediaDao>(relaxed=true)
        coEvery { enc.getById(any()) } answers { EncyclopediaEntity(id=firstArg(),name="世界") }
        val saved=androidx.lifecycle.SavedStateHandle();val vm=createViewModel(enc,savedStateHandle=saved)
        vm.load(3);vm.saveTimelineDraft(24,"description","未提交事件24");vm.saveTimelineDraft(null,"title","新增草稿")
        val restored=createViewModel(enc,savedStateHandle=androidx.lifecycle.SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }))
        restored.load(4);assertEquals("",restored.timelineDraft(24,"description"))
        restored.load(3);assertEquals("未提交事件24",restored.timelineDraft(24,"description"));assertEquals("新增草稿",restored.timelineDraft(null,"title"))
        restored.discardTimelineDraft(24);assertEquals("",restored.timelineDraft(24,"description"));assertEquals("新增草稿",restored.timelineDraft(null,"title"))
    }


    @Test fun timelineSourceResetsPairAndRestoresAcrossOwnerTabAndWorld() = runTest(dispatcher) {
        val enc=mockk<EncyclopediaDao>(relaxed=true);val dao=mockk<TimelineEventDao>(relaxed=true)
        coEvery { enc.getById(any()) } answers { EncyclopediaEntity(id=firstArg(),name="世界") }
        val rows=(1L..30L).map { TimelineEventEntity(id=it,encyclopediaId=3,entryId=8,sortOrder=-4) }
        coEvery { dao.getPage(3,null,null,25,"linked") } returns rows.take(25)
        coEvery { dao.getPage(3,-4,24,25,"linked") } returns rows.drop(24)
        val saved=androidx.lifecycle.SavedStateHandle();val vm=createViewModel(enc,timelineDao=dao,savedStateHandle=saved)
        vm.load(3);vm.setMainTab(EncyclopediaMainTab.TIMELINE);vm.selectTimelineSource("linked");vm.nextTimelinePage()
        vm.setMainTab(EncyclopediaMainTab.ENTRIES);vm.setMainTab(EncyclopediaMainTab.TIMELINE)
        assertEquals(1,vm.state.value.timelinePageIndex);assertEquals(25L,vm.state.value.timelineEvents.first().id)
        val restored=createViewModel(enc,timelineDao=dao,savedStateHandle=androidx.lifecycle.SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }))
        restored.load(3);assertEquals("linked",restored.state.value.timelineSource);assertEquals(1,restored.state.value.timelinePageIndex)
        restored.load(4);assertEquals("",restored.state.value.timelineSource);assertEquals(0,restored.state.value.timelinePageIndex)
        restored.load(3);assertEquals("linked",restored.state.value.timelineSource);assertEquals(1,restored.state.value.timelinePageIndex)
        restored.selectTimelineSource("standalone");assertEquals(0,restored.state.value.timelinePageIndex)
        assertTrue(restored.state.value.timelineEvents.isEmpty());assertTrue(saved.get<LongArray>("timeline_cursors_3")!!.isNotEmpty())
    }

    @Test fun timelineSourceCancelsOldReadWithoutLatePublication() = runTest(dispatcher) {
        val enc=mockk<EncyclopediaDao>(relaxed=true);val dao=mockk<TimelineEventDao>(relaxed=true)
        coEvery { enc.getById(3) } returns EncyclopediaEntity(id=3,name="世界")
        val gate=kotlinx.coroutines.CompletableDeferred<Unit>();var cancelled=false
        coEvery { dao.getPage(3,null,null,25,"linked") } coAnswers {
            try { gate.await();listOf(TimelineEventEntity(id=1,encyclopediaId=3,entryId=8)) }
            finally { cancelled=true }
        }
        coEvery { dao.getPage(3,null,null,25,"standalone") } returns listOf(TimelineEventEntity(id=2,encyclopediaId=3))
        val vm=createViewModel(enc,timelineDao=dao);vm.load(3);vm.setMainTab(EncyclopediaMainTab.TIMELINE)
        vm.selectTimelineSource("linked");assertTrue(vm.state.value.timelineLoading)
        vm.selectTimelineSource("standalone");assertTrue(cancelled);gate.complete(Unit);advanceUntilIdle()
        assertEquals("standalone",vm.state.value.timelineSource);assertEquals(2L,vm.state.value.timelineEvents.single().id)
        assertFalse(vm.state.value.timelineLoading)
    }

    @Test fun timelineSourceFailedPairRetriesAndEmptyDeepPageFallsBack() = runTest(dispatcher) {
        val enc=mockk<EncyclopediaDao>(relaxed=true);val dao=mockk<TimelineEventDao>(relaxed=true)
        coEvery { enc.getById(3) } returns EncyclopediaEntity(id=3,name="世界")
        val rows=(1L..25L).map { TimelineEventEntity(id=it,encyclopediaId=3,sortOrder=7) }
        coEvery { dao.getPage(3,null,null,25,"standalone") } returns rows
        coEvery { dao.getPage(3,7,24,25,"standalone") } throws IllegalStateException("read failed")
        val vm=createViewModel(enc,timelineDao=dao);vm.load(3);vm.setMainTab(EncyclopediaMainTab.TIMELINE);vm.selectTimelineSource("standalone");vm.nextTimelinePage()
        assertEquals(0,vm.state.value.timelinePageIndex);assertEquals(24,vm.state.value.timelineEvents.size)
        coEvery { dao.getPage(3,7,24,25,"standalone") } returns rows.drop(24)
        vm.retryTimelinePage();assertEquals(1,vm.state.value.timelinePageIndex);assertEquals(25L,vm.state.value.timelineEvents.single().id)
        coEvery { dao.getPage(3,7,24,25,"standalone") } returns emptyList()
        vm.load(3);assertEquals(0,vm.state.value.timelinePageIndex);assertEquals(24,vm.state.value.timelineEvents.size)
    }

    @Test fun invalidTimelineSourceDropsUnrelatedSavedPair() = runTest(dispatcher) {
        val enc=mockk<EncyclopediaDao>(relaxed=true)
        coEvery { enc.getById(3) } returns EncyclopediaEntity(id=3,name="世界")
        val vm=createViewModel(enc,savedStateHandle=androidx.lifecycle.SavedStateHandle(mapOf("timeline_source_3" to "completed","timeline_cursors_3" to longArrayOf(2,24))))
        vm.load(3);assertEquals("",vm.state.value.timelineSource);assertEquals(0,vm.state.value.timelinePageIndex)
        vm.selectTimelineSource("completed");assertEquals("",vm.state.value.timelineSource)
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
        deleteUseCase: DeleteEncyclopediaEntryUseCase = mockk(relaxed = true),
    ): EncyclopediaDetailViewModel {
        val secureStorage = mockk<SecureStorage>(relaxed = true)
        every { secureStorage.publicApiKey } returns apiKey
        every { queue.observeActiveForEncyclopedia(any()) } returns flowOf(emptyList())
        return EncyclopediaDetailViewModel(
            encyclopediaDao = encyclopediaDao,
            entryDao = entryDao,
            saveCharacterEntry = saveEntry,
            deleteEncyclopediaEntry = deleteUseCase,
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
            coEvery { getEntryListPage(3L, any(), "") } answers { listOf(note.toListItem()) }
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
            coEvery { getEntryListPage(3L, any(), "") } answers { records.toListItems() }
            coEvery { countEntries(3L, "") } answers { records.size }
            coEvery { getEntryListPage(3L, any(), "location") } answers { records.filter { it.entryType == "location" }.toListItems() }
        }
        val vm = createViewModel(dao, entries)
        vm.load(3L)
        vm.selectType("location")
        vm.setPreviewEntry(9L)
        vm.load(3L)
        assertEquals("location", vm.state.value.selectedType)
        assertEquals(listOf(place.toListItem()), vm.state.value.entries)
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
            coEvery { getEntryListPage(3L, any(), "") } returns listOf(entry.toListItem())
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
        assertEquals(listOf(entry.toListItem()), viewModel.state.value.entries)
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
        val gate = kotlinx.coroutines.CompletableDeferred<List<EncyclopediaEntryListItem>>()
        coEvery { entries.getEntryListPage(3L, any(), "") } coAnswers { gate.await() }
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
        val gate = kotlinx.coroutines.CompletableDeferred<List<EncyclopediaEntryListItem>>()
        coEvery { entries.getEntryListPage(3L, any(), "location") } coAnswers { gate.await() }
        vm.selectType("location")
        vm.selectType("character")
        coEvery { entries.getEntryListPage(3L, any(), "location") } returns listOf(latest.toListItem())
        coEvery { entries.getById(9L) } returns latest
        vm.selectType("location")
        vm.setPreviewEntry(9L)

        gate.complete(listOf(old).toListItems())
        advanceUntilIdle()

        assertEquals("location", vm.state.value.selectedType)
        assertEquals(listOf(latest.toListItem()), vm.state.value.entries)
        assertEquals(9L, vm.state.value.previewEntryId)
        assertEquals(latest, vm.state.value.previewEntry)
    }

    @Test
    fun oldTypeRefreshCannotOverwriteReloadedEncyclopedia() = runTest(dispatcher) {
        val dao = mockk<EncyclopediaDao> {
            coEvery { getById(3L) } returns EncyclopediaEntity(id = 3L, name = "雾海")
        }
        val entries = mockk<EncyclopediaEntryDao>(relaxed = true)
        val vm = createViewModel(dao, entries)
        vm.load(3L)
        val gate = kotlinx.coroutines.CompletableDeferred<List<EncyclopediaEntryListItem>>()
        coEvery { entries.getEntryListPage(3L, any(), "location") } coAnswers { gate.await() }
        vm.selectType("location")
        val latest = EncyclopediaEntryEntity(id = 10L, encyclopediaId = 3L, title = "新码头", entryType = "location")
        coEvery { entries.getEntryListPage(3L, any(), "") } returns listOf(latest.toListItem())
        coEvery { entries.getEntryListPage(3L, any(), "location") } returns listOf(latest.toListItem())
        vm.load(3L)

        gate.complete(emptyList())

        assertEquals(listOf(latest.toListItem()), vm.state.value.entries)
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
