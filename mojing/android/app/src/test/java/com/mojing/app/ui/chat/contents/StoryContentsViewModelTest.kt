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

    @Test fun exactlyOnePageDoesNotOfferAnEmptyNextPage() = runTest(dispatcher) {
        val dao = mockk<MessageDao>()
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
        val dao = mockk<MessageDao>()
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

    private fun rows(ids: IntProgression): List<StoryContentsMessageProjection> = ids.map { id ->
        StoryContentsMessageProjection(id = id.toLong(), speakerType = "narrator", branchId = "main",
            createdAt = 0L, structuredContentJson = "{}", contentPreview = "第${id}章")
    }
}
