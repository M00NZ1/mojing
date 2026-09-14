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
        viewModel = SearchViewModel(application, dao, presentation)
        viewModel.initialize(1L, "main")
    }

    @After fun tearDown() { Dispatchers.resetMain() }

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
