package com.mojing.app.ui.chat

import com.mojing.app.data.local.entity.MessageBookmarkEntity

internal const val BOOKMARK_WINDOW_SIZE = 120

internal data class BookmarkPageWindow(
    val rows: List<MessageBookmarkEntity>,
    val beforeCreatedAt: Long?,
    val beforeId: Long?,
)

internal fun appendBookmarkPage(
    existing: List<MessageBookmarkEntity>, next: List<MessageBookmarkEntity>,
    beforeCreatedAt: Long?, beforeId: Long?,
): BookmarkPageWindow {
    val combined = (existing + next).distinctBy { it.id }
    val discarded = combined.dropLast(BOOKMARK_WINDOW_SIZE).lastOrNull()
    return BookmarkPageWindow(combined.takeLast(BOOKMARK_WINDOW_SIZE),
        discarded?.createdAt ?: beforeCreatedAt, discarded?.id ?: beforeId)
}
