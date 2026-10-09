package com.mojing.app.ui.chat.search

import com.mojing.app.data.local.dao.MessageDao
import com.mojing.app.data.local.dao.SessionBranchDao
import com.mojing.app.data.local.entity.MessageEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class StorylineSearchCursor(val branchOrder: Long, val branchId: String, val messageId: Long)
internal data class StorylineSearchRow(val message: MessageEntity, val branchOrder: Long, val branchId: String, val branchLabel: String)
internal data class StorylineSearchPage(val rows: List<StorylineSearchRow>, val hasMore: Boolean)

/** Stable grouping: main, then branch row ID ascending; each group uses message ID descending.
 * No mutable paging state is advanced before the caller publishes a successful page. */
internal class AllStorylineSearchPager(private val messages: MessageDao, private val branches: SessionBranchDao) {
    suspend fun page(sessionId: Long, query: String, exact: Boolean, cursor: StorylineSearchCursor? = null,
        newer: Boolean = false, limit: Int = 40): StorylineSearchPage {
        require(limit in 1..40)
        require(!newer || cursor != null)
        var order = cursor?.branchOrder ?: 0L
        var branch = cursor?.branchId ?: "main"
        var messageCursor = cursor?.messageId ?: Long.MAX_VALUE
        val rows = ArrayList<StorylineSearchRow>(limit + 1)
        while (rows.size <= limit) {
            currentCoroutineContext().ensureActive()
            val metadata = if (order == 0L) null else branches.getByBranch(sessionId, branch)
            if (order == 0L || metadata?.id == order) {
                val amount = limit + 1 - rows.size
                val found = if (newer) {
                    if (branch == "main") messages.searchMainMessagesAfter(sessionId, query, if (exact) 1 else 0, amount, messageCursor)
                    else messages.searchVisibleMessagesAfter(sessionId, branch, query, if (exact) 1 else 0, amount, messageCursor)
                } else {
                    if (branch == "main") messages.searchMainMessages(sessionId, query, if (exact) 1 else 0, amount, messageCursor)
                    else messages.searchVisibleMessages(sessionId, branch, query, if (exact) 1 else 0, amount, messageCursor)
                }
                rows += found.map { StorylineSearchRow(it, order, branch, if (order == 0L) "主线" else metadata!!.label.ifBlank { "故事线 ${metadata.id}" }) }
                if (rows.size > limit) break
            }
            val next = if (newer) branches.getPreviousPage(sessionId, order, 1).firstOrNull()
                else branches.getPage(sessionId, order, 1).firstOrNull()
            if (next == null) {
                if (!newer || order == 0L) break
                order = 0L; branch = "main"
            } else { order = next.id; branch = next.branchId }
            messageCursor = if (newer) 0L else Long.MAX_VALUE
        }
        val result = rows.take(limit)
        return StorylineSearchPage(if (newer) result.asReversed() else result, rows.size > limit)
    }
}
