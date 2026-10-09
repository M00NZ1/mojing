package com.mojing.app.ui.chat

import com.mojing.app.data.local.entity.MessageBookmarkEntity
import com.mojing.app.data.local.entity.SessionEventNodeEntity
import org.junit.Assert.*
import org.junit.Test

class LibraryPageWindowTest {
    @Test fun eventWindowRetainsThreePagesAndCursorBeforeItsHead() {
        val rows = (100L downTo 1L).map { SessionEventNodeEntity(id = it, sessionId = 1, createdAt = 10) }
        val window = appendEventPage(rows.take(72), rows.drop(72).take(24), null, null)
        assertEquals(72, window.rows.size)
        assertEquals(77L, window.beforeId)
        assertEquals(10L, window.beforeCreatedAt)
        assertEquals(76L, window.rows.first().id)
        assertEquals(5L, window.rows.last().id)
        val refreshed = appendEventPage(window.rows, window.rows.takeLast(2), window.beforeCreatedAt, window.beforeId)
        assertEquals(window, refreshed)
    }

    @Test fun bookmarkWindowTrimsPreviewsByStableMessageIdsAndRetainsCursor() {
        val rows = (200L downTo 1L).map { MessageBookmarkEntity(id = it, sessionId = 1, messageId = it, createdAt = 20) }
        val window = appendBookmarkPage(rows.take(120), rows.drop(120).take(40), null, null)
        assertEquals(120, window.rows.size)
        assertEquals(161L, window.beforeId)
        assertEquals(20L, window.beforeCreatedAt)
        assertEquals(160L, window.rows.first().id)
        assertEquals(41L, window.rows.last().id)
        assertEquals(window, appendBookmarkPage(window.rows, emptyList(), window.beforeCreatedAt, window.beforeId))
    }
}
