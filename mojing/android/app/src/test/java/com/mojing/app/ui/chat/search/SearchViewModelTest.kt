package com.mojing.app.ui.chat.search

import android.app.Application
import android.content.SharedPreferences
import com.mojing.app.data.local.dao.MessageDao
import com.mojing.app.data.local.entity.MessageEntity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val application = mockk<Application>(relaxed = true)
    private val preferences = mockk<SharedPreferences>(relaxed = true)
    private val editor = mockk<SharedPreferences.Editor>(relaxed = true)
    private lateinit var dao: MessageDao
    private lateinit var viewModel: SearchViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { application.getSharedPreferences(any(), any()) } returns preferences
        every { preferences.getStringSet(any(), any()) } returns emptySet()
        every { preferences.getLong(any(), any()) } returns 0L
        every { preferences.edit() } returns editor
        dao = mockk(relaxed = true)
        val presentation = io.mockk.mockk<SearchPresentationLoader>()
        io.mockk.coEvery { presentation.load(any(), any()) } returns SearchPresentation()
        viewModel = SearchViewModel(application, dao, presentation, SearchResultFormatter(dispatcher))
        viewModel.initialize(1L, "main")
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun firstPageCanBeReadWhileTotalCountIsPending() = runTest(dispatcher) {
        val gate = CompletableDeferred<Int>()
        val page = (1L..40L).reversed().map { message(it, "hit") }
        coEvery { dao.searchMainMessages(any(), any(), any(), any(), any()) } returns page
        coEvery { dao.countMainMessages(any(), any(), any()) } coAnswers { gate.await() }
        coEvery { dao.getMainMessageById(1L, 40L) } returns page.first()
        viewModel.setQuery("hit"); viewModel.search(1L, "main"); runCurrent()
        assertEquals(40, viewModel.state.value.hits.size)
        assertFalse(viewModel.state.value.searching)
        assertTrue(viewModel.state.value.counting)
        viewModel.openHit(1L, "main", 40L); runCurrent()
        assertEquals(40L, viewModel.state.value.selectedMessageId)
        gate.complete(60); advanceUntilIdle()
        assertEquals(60, viewModel.state.value.totalMatches)
        assertFalse(viewModel.state.value.counting)
    }

    @Test fun countFailureKeepsResultsAndCanBeRetried() = runTest(dispatcher) {
        coEvery { dao.searchMainMessages(any(), any(), any(), any(), any()) } returns
            (1L..40L).reversed().map { message(it, "hit") }
        coEvery { dao.countMainMessages(any(), any(), any()) } throws IllegalStateException("count failed")
        viewModel.setQuery("hit"); viewModel.search(1L, "main"); advanceUntilIdle()
        assertEquals(40, viewModel.state.value.hits.size)
        assertNull(viewModel.state.value.error)
        assertEquals("匹配总数统计失败", viewModel.state.value.countError)
        coEvery { dao.countMainMessages(any(), any(), any()) } returns 40
        viewModel.countMatches(1L, "main"); advanceUntilIdle()
        assertEquals(40, viewModel.state.value.totalMatches)
        assertNull(viewModel.state.value.countError)
        assertFalse(viewModel.state.value.hasOlder)
    }

    @Test fun changedQueryCannotReceivePreviousCount() = runTest(dispatcher) {
        val gate = CompletableDeferred<Int>()
        coEvery { dao.searchMainMessages(any(), any(), any(), any(), any()) } returns
            (1L..40L).reversed().map { message(it, "old") }
        coEvery { dao.countMainMessages(any(), any(), any()) } coAnswers { gate.await() }
        viewModel.setQuery("old"); viewModel.search(1L, "main"); runCurrent()
        viewModel.setQuery("new")
        gate.complete(100); advanceUntilIdle()
        assertNull(viewModel.state.value.totalMatches)
        assertFalse(viewModel.state.value.counting)
        assertTrue(viewModel.state.value.hits.isEmpty())
    }

    @Test fun changingQueryWhileSnippetIsQueuedCannotPublishOldResults() = runTest(dispatcher) {
        val cpu = QueuedSearchDispatcher()
        val presentation = mockk<SearchPresentationLoader>()
        val vm = SearchViewModel(application, dao, presentation, SearchResultFormatter(cpu))
        vm.initialize(1L)
        coEvery { dao.searchMainMessages(any(), any(), any(), any(), any()) } returns listOf(message(8L, "old body"))
        coEvery { dao.countMainMessages(any(), any(), any()) } returns 1
        vm.setQuery("old"); vm.search(1L, "main")
        runCurrent()
        assertTrue(vm.state.value.searching)
        assertTrue(vm.state.value.hits.isEmpty())
        vm.setQuery("new")
        cpu.runCurrent()
        advanceUntilIdle()
        assertEquals("new", vm.state.value.query)
        assertFalse(vm.state.value.searching)
        assertTrue(vm.state.value.hits.isEmpty())
    }

    @Test fun changingQueryCancelsInFlightSearchAndClearsSearchState() = runTest(dispatcher) {
        val gate = CompletableDeferred<List<MessageEntity>>()
        coEvery { dao.searchMainMessages(1L, "old", 0, any(), any()) } coAnswers { gate.await() }
        coEvery { dao.countMainMessages(1L, "old", 0) } returns 1
        viewModel.setQuery("old"); viewModel.search(1L, "main"); advanceUntilIdle()
        assertTrue(viewModel.state.value.searching)
        viewModel.setQuery("new")
        gate.complete(listOf(message(1L, "old result")))
        advanceUntilIdle()
        assertEquals("new", viewModel.state.value.query)
        assertFalse(viewModel.state.value.searching)
        assertTrue(viewModel.state.value.hits.isEmpty())
    }

    @Test fun initializingAnotherBranchClearsPreviousResults() = runTest(dispatcher) {
        coEvery { dao.searchMainMessages(any(), any(), any(), any(), any()) } returns listOf(message(3L, "hit"))
        coEvery { dao.countMainMessages(any(), any(), any()) } returns 1
        viewModel.setQuery("hit"); viewModel.search(1L, "main"); advanceUntilIdle()
        assertEquals(1, viewModel.state.value.hits.size)
        viewModel.initialize(1L, "branch-2")
        assertTrue(viewModel.state.value.hits.isEmpty())
        assertEquals("", viewModel.state.value.completedQuery)
    }

    @Test fun nextNavigationLoadsNextPageAndOpensFirstNewHit() = runTest(dispatcher) {
        val first = (2L..41L).reversed().map { message(it, "hit $it") }
        val next = listOf(message(1L, "hit 1"))
        coEvery { dao.searchMainMessages(1L, "hit", 0, 40, Long.MAX_VALUE) } returns first
        coEvery { dao.searchMainMessages(1L, "hit", 0, 40, 2L) } returns next
        coEvery { dao.countMainMessages(1L, "hit", 0) } returns 41
        coEvery { dao.getMainMessageById(1L, 2L) } returns first.last()
        coEvery { dao.getMainMessageById(1L, 1L) } returns next.single()
        coEvery { dao.getMainMessagesBefore(1L, any(), any()) } returns emptyList()
        coEvery { dao.getMainMessagesAfter(1L, any(), any()) } returns emptyList()
        viewModel.setQuery("hit"); viewModel.search(1L, "main"); advanceUntilIdle()
        viewModel.openHit(1L, "main", 2L); advanceUntilIdle()
        viewModel.navigateHit(1L, "main", 1); advanceUntilIdle()
        assertEquals(1L, viewModel.state.value.selectedMessageId)
        assertEquals(41, viewModel.state.value.hits.size)
    }

    @Test fun resultWindowStaysBoundedAndCanPageBackToNewerHits() = runTest(dispatcher) {
        val pages = (1L..200L).reversed().chunked(40).map { ids -> ids.map { message(it, "hit $it") } }
        coEvery { dao.searchMainMessages(1L, "hit", 0, 40, Long.MAX_VALUE) } returns pages[0]
        for (page in 1..4) {
            coEvery { dao.searchMainMessages(1L, "hit", 0, 40, pages[page - 1].last().id) } returns pages[page]
        }
        coEvery { dao.searchMainMessagesAfter(1L, "hit", 0, 40, 120L) } returns pages[1].asReversed()
        coEvery { dao.searchMainMessagesAfter(1L, "hit", 0, 40, 160L) } returns pages[0].asReversed()
        coEvery { dao.countMainMessages(1L, "hit", 0) } returns 200
        coEvery { dao.getMainMessageById(1L, 120L) } returns pages[2].first()
        coEvery { dao.getMainMessageById(1L, 121L) } returns pages[1].last()
        coEvery { dao.getMainMessagesBefore(1L, any(), any()) } returns emptyList()
        coEvery { dao.getMainMessagesAfter(1L, any(), any()) } returns emptyList()

        viewModel.setQuery("hit"); viewModel.search(1L, "main"); advanceUntilIdle()
        repeat(4) { viewModel.loadOlder(1L, "main"); advanceUntilIdle() }
        val oldestWindow = viewModel.state.value
        assertEquals(120, oldestWindow.hits.size)
        assertEquals(80, oldestWindow.firstHitOffset)
        assertEquals(120L, oldestWindow.hits.first().message.id)
        assertTrue(oldestWindow.hasNewer)
        assertFalse(oldestWindow.hasOlder)
        assertEquals(200, oldestWindow.totalMatches)

        viewModel.openHit(1L, "main", 120L); advanceUntilIdle()
        viewModel.navigateHit(1L, "main", -1); advanceUntilIdle()
        assertEquals(121L, viewModel.state.value.selectedMessageId)
        assertEquals(40, viewModel.state.value.firstHitOffset)
        assertEquals(160L, viewModel.state.value.hits.first().message.id)
        assertTrue(viewModel.state.value.hasOlder)

        viewModel.closeHit()
        viewModel.loadNewer(1L, "main"); advanceUntilIdle()
        assertEquals(0, viewModel.state.value.firstHitOffset)
        assertEquals(200L, viewModel.state.value.hits.first().message.id)
        assertEquals(120, viewModel.state.value.hits.size)
        assertFalse(viewModel.state.value.hasNewer)
    }

    @Test fun failedNewerPageCanBeRetriedWithoutLosingWindow() = runTest(dispatcher) {
        val pages = (1L..160L).reversed().chunked(40).map { ids -> ids.map { message(it, "hit") } }
        coEvery { dao.searchMainMessages(1L, "hit", 0, 40, Long.MAX_VALUE) } returns pages[0]
        for (page in 1..3) {
            coEvery { dao.searchMainMessages(1L, "hit", 0, 40, pages[page - 1].last().id) } returns pages[page]
        }
        coEvery { dao.countMainMessages(1L, "hit", 0) } returns 160
        coEvery { dao.searchMainMessagesAfter(1L, "hit", 0, 40, 120L) } throws IllegalStateException("offline")
        viewModel.setQuery("hit"); viewModel.search(1L, "main"); advanceUntilIdle()
        repeat(3) { viewModel.loadOlder(1L, "main"); advanceUntilIdle() }
        assertEquals(40, viewModel.state.value.firstHitOffset)

        viewModel.loadNewer(1L, "main"); advanceUntilIdle()
        assertEquals(SearchPageDirection.NEWER, viewModel.state.value.failedPage)
        assertEquals(120, viewModel.state.value.hits.size)
        coEvery { dao.searchMainMessagesAfter(1L, "hit", 0, 40, 120L) } returns pages[0].asReversed()
        viewModel.loadNewer(1L, "main"); advanceUntilIdle()
        assertNull(viewModel.state.value.error)
        assertEquals(0, viewModel.state.value.firstHitOffset)
        assertEquals(120, viewModel.state.value.hits.size)
    }

    @Test fun closeHitPreventsLateDetailResponseFromReopeningReader() = runTest(dispatcher) {
        val gate = CompletableDeferred<MessageEntity?>()
        coEvery { dao.getMainMessageById(1L, 7L) } coAnswers { gate.await() }
        viewModel.openHit(1L, "main", 7L); advanceUntilIdle()
        viewModel.closeHit(); gate.complete(message(7L, "late")); advanceUntilIdle()
        assertNull(viewModel.state.value.selectedMessageId)
        assertTrue(viewModel.state.value.contextMessages.isEmpty())
    }

    @Test fun missingTargetProducesRecoverableError() = runTest(dispatcher) {
        coEvery { dao.getMainMessageById(1L, 99L) } returns null
        viewModel.openHit(1L, "main", 99L); advanceUntilIdle()
        assertEquals("该消息已删除或不在当前故事线", viewModel.state.value.error)
        assertFalse(viewModel.state.value.searching)
    }

    @Test fun readerStartsWithSmallContextAndLoadsRemainingWindowOnDemand() = runTest(dispatcher) {
        coEvery { dao.getMainMessageById(1L, 10L) } returns message(10L, "target")
        coEvery { dao.getMainMessagesBefore(1L, 10L, 2) } returns listOf(message(9L, "before 9"), message(8L, "before 8"))
        coEvery { dao.getMainMessagesAfter(1L, 10L, 2) } returns listOf(message(11L, "after 11"), message(12L, "after 12"))
        coEvery { dao.getMainMessagesBefore(1L, 8L, 6) } returns
            (2L..7L).reversed().map { message(it, "before $it") }

        viewModel.openHit(1L, "main", 10L); advanceUntilIdle()
        assertEquals(listOf(8L, 9L, 10L, 11L, 12L), viewModel.state.value.contextMessages.map { it.id })
        assertTrue(viewModel.state.value.contextBeforeHasMore)
        assertTrue(viewModel.state.value.contextAfterHasMore)

        viewModel.loadMoreContext(1L, "main", SearchContextDirection.BEFORE); advanceUntilIdle()
        assertEquals((2L..12L).toList(), viewModel.state.value.contextMessages.map { it.id })
        assertFalse(viewModel.state.value.contextBeforeHasMore)
    }

    @Test fun contextFailureKeepsTargetAndRetryAddsOriginalText() = runTest(dispatcher) {
        coEvery { dao.getMainMessageById(1L, 10L) } returns message(10L, "target body")
        coEvery { dao.getMainMessagesBefore(1L, 10L, 2) } throws IllegalStateException("offline")
        coEvery { dao.getMainMessagesAfter(1L, 10L, 2) } returns emptyList()

        viewModel.openHit(1L, "main", 10L); advanceUntilIdle()
        assertEquals(listOf(10L), viewModel.state.value.contextMessages.map { it.id })
        assertTrue(viewModel.state.value.contextFailedBefore)
        assertEquals("无法加载上文，请重试", viewModel.state.value.error)

        coEvery { dao.getMainMessagesBefore(1L, 10L, 2) } returns listOf(message(9L, "before 9"), message(8L, "before 8"))
        viewModel.retryContext(1L, "main"); advanceUntilIdle()
        assertEquals(listOf(8L, 9L, 10L), viewModel.state.value.contextMessages.map { it.id })
        assertFalse(viewModel.state.value.contextFailedBefore)
        assertNull(viewModel.state.value.error)
    }

    @Test fun interleavedContextPresentationKeepsBothSides() = runTest(dispatcher) {
        val presentation = mockk<SearchPresentationLoader>()
        val beforePresentationEntered = CompletableDeferred<Unit>()
        val releaseBeforePresentation = CompletableDeferred<Unit>()
        coEvery { presentation.load(any(), any()) } coAnswers {
            val rows = secondArg<List<MessageEntity>>()
            if (rows.size == 3 && rows.firstOrNull()?.id == 8L) {
                beforePresentationEntered.complete(Unit)
                releaseBeforePresentation.await()
            }
            SearchPresentation()
        }
        val vm = SearchViewModel(application, dao, presentation, SearchResultFormatter(dispatcher))
        vm.initialize(1L, "main")
        coEvery { dao.getMainMessageById(1L, 10L) } returns message(10L, "target")
        coEvery { dao.getMainMessagesBefore(1L, 10L, 2) } returns listOf(message(9L, "before 9"), message(8L, "before 8"))
        coEvery { dao.getMainMessagesAfter(1L, 10L, 2) } returns listOf(message(11L, "after 11"), message(12L, "after 12"))

        vm.openHit(1L, "main", 10L)
        runCurrent()
        assertTrue(beforePresentationEntered.isCompleted)
        releaseBeforePresentation.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(8L, 9L, 10L, 11L, 12L), vm.state.value.contextMessages.map { it.id })
    }

    @Test fun resultCacheDoesNotRetainFullMessageBody() = runTest(dispatcher) {
        val longText = "正文".repeat(500)
        coEvery { dao.searchMainMessages(1L, "正文", 0, any(), any()) } returns listOf(message(2L, longText))
        coEvery { dao.countMainMessages(1L, "正文", 0) } returns 1
        viewModel.setQuery("正文"); viewModel.search(1L, "main"); advanceUntilIdle()
        val cached = viewModel.state.value.hits.single().message
        assertEquals("", cached.content)
        assertEquals("{}", cached.structuredContentJson)
        assertTrue(viewModel.state.value.hits.single().snippet.length < longText.length)
    }

    @Test fun historyWritesAtMostTwelveEntriesAndSupportsRemoval() = runTest(dispatcher) {
        val stored = mutableMapOf<String, Any>()
        every { preferences.getStringSet(any(), any()) } answers { (stored[firstArg()] as? Set<String>) ?: emptySet() }
        every { preferences.getLong(any(), any()) } answers { (stored[firstArg()] as? Long) ?: secondArg() }
        every { editor.putStringSet(any(), any()) } answers { stored[firstArg()] = secondArg<Set<String>>(); editor }
        every { editor.putLong(any(), any()) } answers { stored[firstArg()] = secondArg<Long>(); editor }
        every { editor.remove(any()) } answers { stored.remove(firstArg<String>()); editor }
        every { editor.apply() } answers { }
        coEvery { dao.searchMainMessages(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { dao.countMainMessages(any(), any(), any()) } returns 0
        repeat(20) {
            viewModel.setQuery("query-$it")
            viewModel.search(1L, "main")
            advanceUntilIdle()
        }
        assertEquals(12, (stored["history_1"] as Set<*>).size)
        assertFalse((stored["history_1"] as Set<*>).contains("query-0"))
        viewModel.removeHistory(1L, "query-19")
        assertEquals(11, (stored["history_1"] as Set<*>).size)
        viewModel.clearHistory(1L)
        assertEquals(0, (stored["history_1"] as Set<*>).size)
    }

    private fun message(id: Long, content: String) = MessageEntity(id = id, sessionId = 1L, content = content)
}
