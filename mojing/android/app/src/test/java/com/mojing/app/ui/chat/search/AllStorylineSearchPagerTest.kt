package com.mojing.app.ui.chat.search

import com.mojing.app.data.local.dao.MessageDao
import com.mojing.app.data.local.dao.SessionBranchDao
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionBranchEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AllStorylineSearchPagerTest {
    private val messages = mockk<MessageDao>()
    private val branches = mockk<SessionBranchDao>()
    private val metadata = (1L..4L).map { SessionBranchEntity(id = it, sessionId = 1, branchId = "b$it", sourceMessageId = 1) }
    private val rows = mapOf("main" to (101L..158L).reversed(), "b1" to emptyList(), "b2" to (1L..101L).reversed(), "b3" to (1L..42L).reversed(), "b4" to emptyList())
    private val pager = AllStorylineSearchPager(messages, branches)
    private fun setup() {
        coEvery { branches.getPage(1, any(), any()) } answers { metadata.filter { it.id > secondArg<Long>() }.take(thirdArg()) }
        coEvery { branches.getPreviousPage(1, any(), any()) } answers { metadata.filter { it.id < secondArg<Long>() }.reversed().take(thirdArg()) }
        coEvery { branches.getByBranch(1, any()) } answers { metadata.firstOrNull { it.branchId == secondArg<String>() } }
        coEvery { messages.searchMainMessages(1, any(), any(), any(), any()) } answers {
            rows.getValue("main").filter { it < arg<Long>(4) }.take(arg(3)).map(::message)
        }
        coEvery { messages.searchMainMessagesAfter(1, any(), any(), any(), any()) } answers {
            rows.getValue("main").filter { it > arg<Long>(4) }.sorted().take(arg(3)).map(::message)
        }
        coEvery { messages.searchVisibleMessages(1, any(), any(), any(), any(), any()) } answers {
            rows.getValue(arg(1)).filter { it < arg<Long>(5) }.take(arg(4)).map(::message)
        }
        coEvery { messages.searchVisibleMessagesAfter(1, any(), any(), any(), any(), any()) } answers {
            rows.getValue(arg(1)).filter { it > arg<Long>(5) }.sorted().take(arg(4)).map(::message)
        }
    }
    private fun message(id: Long) = MessageEntity(id = id, sessionId = 1, speakerType = "narrator", content = "海港")
    private fun StorylineSearchRow.cursor() = StorylineSearchCursor(branchOrder, branchId, message.id)
    private fun List<StorylineSearchRow>.keys() = map { it.branchId to it.message.id }

    @Test fun forwardAndReversePagesHaveNoGapsAcrossBranchesOrDuplicateMessageIds() = runTest {
        setup()
        val expected = rows.flatMap { (branch, ids) -> ids.map { branch to it } }
        val forward = mutableListOf<StorylineSearchRow>()
        var cursor: StorylineSearchCursor? = null
        do {
            val page = pager.page(1, "海港", false, cursor)
            assertTrue(page.rows.size <= 40)
            forward += page.rows
            cursor = page.rows.lastOrNull()?.cursor()
        } while (page.hasMore)
        assertEquals(expected, forward.keys())
        val backward = mutableListOf(forward.last())
        do {
            val page = pager.page(1, "海港", false, backward.first().cursor(), newer = true)
            backward.addAll(0, page.rows)
        } while (page.hasMore)
        assertEquals(expected, backward.keys())
    }

    @Test fun failedPageDoesNotConsumeCursorAndRetryReturnsSameRows() = runTest {
        setup()
        val first = pager.page(1, "海港", false)
        val cursor = first.rows.last().cursor()
        val expected = pager.page(1, "海港", false, cursor)
        coEvery { messages.searchVisibleMessages(1, "b1", any(), any(), any(), any()) } throws IllegalStateException("disk")
        assertTrue(runCatching { pager.page(1, "海港", false, cursor) }.isFailure)
        setup()
        assertEquals(expected.rows.keys(), pager.page(1, "海港", false, cursor).rows.keys())
    }

    @Test fun deletedCursorBranchSkipsToNextSurvivingGroup() = runTest {
        setup()
        coEvery { branches.getByBranch(1, "b2") } returns null
        val page = pager.page(1, "海港", false, StorylineSearchCursor(2, "b2", 50))
        assertEquals("b3", page.rows.first().branchId)
        assertEquals(42L, page.rows.first().message.id)
    }
}
