package com.mojing.app.engine

import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.domain.engine.MemoryCompactionPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryCompactionPlannerTest {
    @Test
    fun selectsOnlyNextCompleteStoryBatch() {
        val messages = listOf(
            message(1, "user"),
            message(2, "system"),
            message(3, "character"),
            message(4, "narrator"),
            message(5, "user"),
        )

        assertEquals(
            listOf(3L, 4L),
            MemoryCompactionPlanner.nextBatch(messages, lastCoveredMessageId = 1L, threshold = 2)
                .map { it.id },
        )
        assertTrue(
            MemoryCompactionPlanner.nextBatch(messages, lastCoveredMessageId = 4L, threshold = 2)
                .isEmpty(),
        )
    }

    @Test
    fun sortsVisibleMessagesByStableIdBeforeSelecting() {
        val messages = listOf(message(9, "character"), message(7, "user"), message(8, "narrator"))

        assertEquals(
            listOf(7L, 8L),
            MemoryCompactionPlanner.nextBatch(messages, lastCoveredMessageId = 0L, threshold = 2)
                .map { it.id },
        )
    }

    private fun message(id: Long, speakerType: String) = MessageEntity(
        id = id,
        sessionId = 1L,
        speakerType = speakerType,
        content = "message-$id",
    )
}
