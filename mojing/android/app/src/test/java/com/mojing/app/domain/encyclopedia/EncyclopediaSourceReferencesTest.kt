package com.mojing.app.domain.encyclopedia

import com.mojing.app.data.local.entity.EncyclopediaEntryEntity
import org.junit.Assert.*
import org.junit.Test

class EncyclopediaSourceReferencesTest {
    @Test fun legacyAndInvalidMetadataKeepAnchor() {
        for (meta in listOf("{}", "broken", "[]", """{"source_message_ids":5}""")) {
            val result = EncyclopediaEntryEntity(encyclopediaId = 1, sourceMessageId = 8, metaJson = meta).sourceReferences()
            assertEquals(listOf(8L), result.messageIds)
        }
    }
    @Test fun referencesArePositiveUniqueBoundedAndKeepAnchor() {
        val ids = (-1..20).joinToString(",")
        val result = EncyclopediaEntryEntity(encyclopediaId = 1, sourceMessageId = 99,
            metaJson = """{"source_message_ids":[null,{},"bad",1.5,2,2,$ids],"source_branch_id":"branch"}""").sourceReferences()
        assertEquals(10, result.messageIds.size)
        assertEquals(10, result.messageIds.distinct().size)
        assertTrue(result.messageIds.all { it > 0 })
        assertEquals(99L, result.messageIds.last())
        assertEquals("branch", result.branchId)
    }
}
