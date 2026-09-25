package com.mojing.app.data.local.dao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchMessageRowTest {
    @Test
    fun mapsOnlySearchFieldsAndLeavesHeavyMetadataAtDefaults() {
        val message = SearchMessageRow(
            id = 17L,
            sessionId = 42L,
            speakerType = "character",
            characterId = 9L,
            branchId = "main",
            content = "命中正文",
            createdAt = 123L,
        ).toMessageEntity()

        assertEquals(17L, message.id)
        assertEquals(42L, message.sessionId)
        assertEquals("character", message.speakerType)
        assertEquals(9L, message.characterId)
        assertEquals("main", message.branchId)
        assertEquals("命中正文", message.content)
        assertEquals(123L, message.createdAt)
        assertEquals("{}", message.structuredContentJson)
        assertTrue(message.includeInContext)
        assertEquals("", message.searchNormalized)
        assertEquals("", message.searchTerms)
    }
}
