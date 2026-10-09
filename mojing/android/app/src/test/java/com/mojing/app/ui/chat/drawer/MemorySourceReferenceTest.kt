package com.mojing.app.ui.chat.drawer

import com.mojing.app.data.local.entity.SessionEventNodeEntity
import com.mojing.app.data.local.entity.SessionMemorySegmentEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemorySourceReferenceTest {
    @Test
    fun segmentUsesRangeStartAsHistoryTarget() {
        val reference = SessionMemorySegmentEntity(
            sessionId = 1L,
            startMessageId = 12L,
            endMessageId = 28L,
        ).sourceReference()

        assertEquals("原文消息 #12–#28", reference?.label)
        assertEquals(12L, reference?.startMessageId)
        assertEquals(28L, reference?.endMessageId)
    }

    @Test
    fun invalidRangesFallBackToTheirLegalAnchor() {
        assertNull(SessionMemorySegmentEntity(sessionId = 1L).sourceReference())
        val reversed = SessionMemorySegmentEntity(
            sessionId = 1L,
            startMessageId = 30L,
            endMessageId = 20L,
        ).sourceReference()
        assertEquals("原文消息 #30", reversed?.label)
        assertEquals(30L, reversed?.startMessageId)
        assertNull(reversed?.endMessageId)
        assertNull(
            SessionMemorySegmentEntity(
                sessionId = 1L,
                startMessageId = 0L,
                endMessageId = 0L,
            ).sourceReference(),
        )
        assertNull(SessionEventNodeEntity(sessionId = 1L, messageId = null).sourceReference())
    }

    @Test
    fun eventLinksItsExactSourceMessage() {
        val reference = SessionEventNodeEntity(sessionId = 1L, messageId = 42L).sourceReference()

        assertEquals("原文消息 #42", reference?.label)
        assertEquals(42L, reference?.startMessageId)
        assertNull(reference?.endMessageId)
    }
}
