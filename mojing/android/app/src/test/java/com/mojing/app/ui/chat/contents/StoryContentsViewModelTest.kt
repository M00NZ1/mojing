package com.mojing.app.ui.chat.contents

import com.mojing.app.data.local.dao.MessageDao
import com.mojing.app.data.local.dao.StoryContentsMessageProjection
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StoryContentsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun completionActionUsesUnfilteredActualTailAndSurvivesSearch() = runTest(dispatcher) {
        val dao = mockk<MessageDao>()
        val draft = com.mojing.app.domain.story.NovelChapter.draftMetadata("{}", 1, "北塔")
        val chapter = StoryContentsMessageProjection(10, "narrator", "main", 0, draft, "北塔")
        coEvery { dao.getVisibleStoryContentsBefore(7, any(), Long.MAX_VALUE, 41) } returns listOf(chapter)
        coEvery { dao.getStoryChapterTail(7, "main") } returns com.mojing.app.data.local.dao.StoryChapterTailProjection(10, "main", draft)
        coEvery { dao.searchVisibleStoryContentsBefore(7, "main", Long.MAX_VALUE, 41, "无匹配", null) } returns emptyList()
        val vm = StoryContentsViewModel(dao)
        vm.load(7, "main"); advanceUntilIdle()
        assertTrue(vm.state.value.canResumeChapter)
        vm.updateQuery("无匹配"); advanceUntilIdle()
        assertTrue(vm.state.value.canResumeChapter)
        assertTrue(vm.state.value.entries.isEmpty())
        coEvery { dao.getStoryChapterTail(7, "main") } returns com.mojing.app.data.local.dao.StoryChapterTailProjection(11, "main", "{}")
        vm.updateQuery(""); advanceUntilIdle()
        vm.load(7, "main"); advanceUntilIdle()
        assertFalse(vm.state.value.canResumeChapter)
        assertTrue(vm.state.value.latestEntry!!.incomplete)
        coEvery { dao.getStoryChapterTail(7, "child") } returns com.mojing.app.data.local.dao.StoryChapterTailProjection(10, "main", draft)
        vm.load(7, "child"); advanceUntilIdle()
        assertFalse(vm.state.value.canResumeChapter)
        coEvery { dao.getVisibleStoryContentsBefore(8, "main", Long.MAX_VALUE, 41) } returns emptyList()
        coEvery { dao.getStoryChapterTail(8, "main") } returns null
        vm.load(8, "main"); advanceUntilIdle()
        assertFalse(vm.state.value.canResumeChapter)
        assertEquals(null, vm.state.value.latestEntry)
    }

    @Test fun exactlyOnePageDoesNotOfferAnEmptyNextPage() = runTest(dispatcher) {
        val dao = mockk<MessageDao>().also { coEvery { it.getStoryChapterTail(any(), any()) } returns null }
        coEvery { dao.getVisibleStoryContentsBefore(7L, "main", Long.MAX_VALUE, 41) } returns rows(40 downTo 1)
        val vm = StoryContentsViewModel(dao)

        vm.load(7L, "main")
        advanceUntilIdle()

        assertEquals(40, vm.state.value.entries.size)
        assertFalse(vm.state.value.hasMore)
        vm.loadMore()
        coVerify(exactly = 1) { dao.getVisibleStoryContentsBefore(any(), any(), any(), any()) }
    }

    @Test fun rapidLoadMoreUsesOneCursorAndKeepsUniqueEntries() = runTest(dispatcher) {
        val dao = mockk<MessageDao>().also { coEvery { it.getStoryChapterTail(any(), any()) } returns null }
        coEvery { dao.getVisibleStoryContentsBefore(7L, "main", Long.MAX_VALUE, 41) } returns rows(81 downTo 41)
        coEvery { dao.getVisibleStoryContentsBefore(7L, "main", 42L, 41) } returns rows(41 downTo 1)
        val vm = StoryContentsViewModel(dao)
        vm.load(7L, "main")
        advanceUntilIdle()
        assertTrue(vm.state.value.hasMore)
        assertEquals(42L, vm.state.value.entries.last().messageId)

        vm.loadMore()
        assertTrue(vm.state.value.isLoadingMore)
        vm.loadMore()
        advanceUntilIdle()

        assertEquals(80, vm.state.value.entries.size)
        assertEquals(80, vm.state.value.entries.map { it.messageId }.distinct().size)
        coVerify(exactly = 1) { dao.getVisibleStoryContentsBefore(7L, "main", 42L, 41) }
    }

    @Test fun searchPagesUseQueryCursorAndKeepNovelActionsIndependentOfMatches() = runTest(dispatcher) {
        val dao = mockk<MessageDao>().also { coEvery { it.getStoryChapterTail(any(), any()) } returns null }
        coEvery { dao.getVisibleStoryContentsBefore(7L, "main", Long.MAX_VALUE, 41) } returns rows(150 downTo 110)
        coEvery { dao.searchVisibleStoryContentsBefore(7L, "main", Long.MAX_VALUE, 41, "雾港", null) } returns rows(100 downTo 60)
        coEvery { dao.searchVisibleStoryContentsBefore(7L, "main", 61, 41, "雾港", null) } returns rows(60 downTo 50)
        coEvery { dao.searchVisibleStoryContentsBefore(7L, "main", Long.MAX_VALUE, 41, "不存在", null) } returns emptyList()
        val vm = StoryContentsViewModel(dao)
        vm.load(7, "main")
        advanceUntilIdle()
        vm.updateQuery("雾港")
        advanceUntilIdle()
        assertEquals(40, vm.state.value.entries.size)
        vm.loadMore()
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(51, vm.state.value.entries.size)
        assertEquals(51, vm.state.value.entries.map { it.messageId }.distinct().size)
        coVerify(exactly = 1) { dao.searchVisibleStoryContentsBefore(7L, "main", 61, 41, "雾港", null) }
        vm.updateQuery("不存在")
        advanceUntilIdle()
        assertTrue(vm.state.value.entries.isEmpty())
        assertEquals(150L, vm.state.value.latestEntry?.messageId)
        vm.updateQuery("")
        advanceUntilIdle()
        assertEquals(150L, vm.state.value.entries.first().messageId)
    }

    @Test fun lateSearchCannotReplaceNewQueryOrAnotherStoryLine() = runTest(dispatcher) {
        val dao = mockk<MessageDao>().also { coEvery { it.getStoryChapterTail(any(), any()) } returns null }
        coEvery { dao.getVisibleStoryContentsBefore(7L, "main", Long.MAX_VALUE, 41) } returns rows(80 downTo 40)
        lateinit var old: Continuation<List<StoryContentsMessageProjection>>
        coEvery { dao.searchVisibleStoryContentsBefore(7L, "main", Long.MAX_VALUE, 41, "旧", null) } coAnswers {
            suspendCoroutine { old = it }
        }
        coEvery { dao.searchVisibleStoryContentsBefore(7L, "main", Long.MAX_VALUE, 41, "第十二章", 12) } returns rows(12 downTo 12)
        coEvery { dao.getVisibleStoryContentsBefore(8L, "B", Long.MAX_VALUE, 41) } returns rows(90 downTo 90)
        val vm = StoryContentsViewModel(dao)
        vm.load(7, "main")
        advanceUntilIdle()
        vm.updateQuery("旧")
        advanceTimeBy(250)
        runCurrent()
        vm.updateQuery("第十二章")
        advanceUntilIdle()
        assertEquals(listOf(12L), vm.state.value.entries.map { it.messageId })
        vm.load(8, "B")
        advanceUntilIdle()
        old.resume(rows(1 downTo 1))
        advanceUntilIdle()
        assertEquals("", vm.state.value.query)
        assertEquals(listOf(90L), vm.state.value.entries.map { it.messageId })
    }

    @Test fun failedSearchRetriesSameQueryAndRenameReappliesFilter() = runTest(dispatcher) {
        val dao = mockk<MessageDao>().also { coEvery { it.getStoryChapterTail(any(), any()) } returns null }
        coEvery { dao.getVisibleStoryContentsBefore(7L, "main", Long.MAX_VALUE, 41) } returns rows(80 downTo 40)
        var attempts = 0
        coEvery { dao.searchVisibleStoryContentsBefore(7L, "main", Long.MAX_VALUE, 41, "钟声", null) } coAnswers {
            when (++attempts) {
                1 -> error("temporary read failure")
                2 -> rows(12 downTo 12)
                else -> emptyList()
            }
        }
        val vm = StoryContentsViewModel(dao)
        vm.load(7, "main")
        advanceUntilIdle()
        vm.updateQuery("钟声")
        advanceUntilIdle()
        assertEquals("钟声", vm.state.value.query)
        assertTrue(vm.state.value.error != null)
        assertEquals(80L, vm.state.value.latestEntry?.messageId)
        vm.retry()
        advanceUntilIdle()
        assertEquals(listOf(12L), vm.state.value.entries.map { it.messageId })
        vm.refreshEntry(12)
        advanceUntilIdle()
        assertTrue(vm.state.value.entries.isEmpty())
        assertEquals("钟声", vm.state.value.query)
    }

    @Test fun typingDebouncesReadsAndRetainsWhitespaceInTheField() = runTest(dispatcher) {
        val dao = mockk<MessageDao>().also { coEvery { it.getStoryChapterTail(any(), any()) } returns null }
        coEvery { dao.getVisibleStoryContentsBefore(7L, "main", Long.MAX_VALUE, 41) } returns rows(80 downTo 40)
        coEvery { dao.searchVisibleStoryContentsBefore(7L, "main", Long.MAX_VALUE, 41, "雾港", null) } returns emptyList()
        val vm = StoryContentsViewModel(dao)
        vm.load(7, "main")
        advanceUntilIdle()
        vm.updateQuery("雾")
        advanceTimeBy(100)
        vm.updateQuery(" 雾港 ")
        advanceUntilIdle()
        coVerify(exactly = 1) { dao.searchVisibleStoryContentsBefore(any(), any(), any(), any(), any(), any()) }
        assertEquals(" 雾港 ", vm.state.value.query)
    }

    @Test fun renamedSourceRefreshFailureRetainsEntryAndRetriesInPlace() = runTest(dispatcher) {
        val dao = mockk<MessageDao>()
        val old = rows(12 downTo 12).single().copy(branchId = "parent")
        coEvery { dao.getStoryChapterTail(7, "child") } returns null
        coEvery { dao.getVisibleStoryContentsBefore(7, "child", Long.MAX_VALUE, 41) } returns listOf(old)
        coEvery { dao.getBranchStoryContentsEntry(7, "child", 12) } throws IllegalStateException("read failed")
        val vm = StoryContentsViewModel(dao)
        vm.load(7, "child"); advanceUntilIdle()
        vm.refreshEntry(12); advanceUntilIdle()
        assertEquals(12L, vm.state.value.refreshFailedId)
        assertEquals("parent", vm.state.value.entries.single().sourceBranchId)
        coEvery { dao.getBranchStoryContentsEntry(7, "child", 12) } returns old.copy(structuredContentJson = "{\"chapter_title\":\"新塔\"}")
        vm.refreshEntry(12); advanceUntilIdle()
        assertEquals(null, vm.state.value.refreshFailedId)
        assertEquals("新塔", vm.state.value.entries.single().title)
        assertEquals("parent", vm.state.value.entries.single().sourceBranchId)
        coVerify(exactly = 2) { dao.getBranchStoryContentsEntry(7, "child", 12) }
    }

    @Test fun sameDirectoryReloadRetainsQueryAndDifferentScopeClearsIt() = runTest(dispatcher) {
        val dao = mockk<MessageDao>().also { coEvery { it.getStoryChapterTail(any(), any()) } returns null }
        coEvery { dao.getVisibleStoryContentsBefore(any(), any(), Long.MAX_VALUE, 41) } returns rows(70 downTo 30)
        coEvery { dao.searchVisibleStoryContentsBefore(7, "main", Long.MAX_VALUE, 41, "北塔", null) } returns rows(7 downTo 7)
        val vm = StoryContentsViewModel(dao)
        vm.load(7, "main"); advanceUntilIdle()
        vm.updateQuery("北塔"); advanceUntilIdle()
        vm.load(7, "main"); advanceUntilIdle()
        assertEquals("北塔", vm.state.value.query)
        assertEquals(listOf(7L), vm.state.value.entries.map { it.messageId })
        assertEquals(70L, vm.state.value.latestEntry?.messageId)
        coVerify(exactly = 2) { dao.searchVisibleStoryContentsBefore(7, "main", Long.MAX_VALUE, 41, "北塔", null) }
        vm.load(7, "child"); advanceUntilIdle()
        assertEquals("", vm.state.value.query)
        vm.load(8, "main"); advanceUntilIdle()
        assertEquals("", vm.state.value.query)
    }

    @Test fun restoredEditorSaveReappliesFilterEvenWhenTargetIsAbsentFromWindow() = runTest(dispatcher) {
        val dao = mockk<MessageDao>().also { coEvery { it.getStoryChapterTail(any(), any()) } returns null }
        coEvery { dao.getVisibleStoryContentsBefore(7, "main", Long.MAX_VALUE, 41) } returns rows(70 downTo 30)
        var matches = rows(7 downTo 7)
        coEvery { dao.searchVisibleStoryContentsBefore(7, "main", Long.MAX_VALUE, 41, "北塔", null) } coAnswers { matches }
        val vm = StoryContentsViewModel(dao)
        vm.load(7, "main"); advanceUntilIdle()
        vm.updateQuery("北塔"); advanceUntilIdle()
        vm.load(7, "main")
        assertTrue(vm.state.value.entries.isEmpty())
        matches = emptyList()
        vm.refreshEntry(7); advanceUntilIdle()
        assertEquals("北塔", vm.state.value.query)
        assertTrue(vm.state.value.entries.isEmpty())
        assertFalse(vm.state.value.isLoading)
        coVerify(exactly = 2) { dao.searchVisibleStoryContentsBefore(7, "main", Long.MAX_VALUE, 41, "北塔", null) }
    }

    private fun rows(ids: IntProgression): List<StoryContentsMessageProjection> = ids.map { id ->
        StoryContentsMessageProjection(id = id.toLong(), speakerType = "narrator", branchId = "main",
            createdAt = 0L, structuredContentJson = "{}", contentPreview = "第${id}章")
    }
}
