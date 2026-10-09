package com.mojing.app.ui.chat

import com.mojing.app.data.local.entity.SessionEventNodeEntity

internal data class EventPageWindow(
    val rows: List<SessionEventNodeEntity>,
    val beforeCreatedAt: Long?,
    val beforeId: Long?,
)

/** Keep the cursor before the retained window so an edit refreshes the same range. */
internal fun appendEventPage(
    existing: List<SessionEventNodeEntity>,
    next: List<SessionEventNodeEntity>,
    beforeCreatedAt: Long?,
    beforeId: Long?,
): EventPageWindow {
    val combined = (existing + next).distinctBy { it.id }
    val discarded = combined.dropLast(EVENT_NODE_WINDOW_SIZE).lastOrNull()
    return EventPageWindow(combined.takeLast(EVENT_NODE_WINDOW_SIZE),
        discarded?.createdAt ?: beforeCreatedAt, discarded?.id ?: beforeId)
}
