package com.mojing.app.domain.encyclopedia

import com.mojing.app.data.local.entity.EncyclopediaEntryEntity
import com.mojing.app.data.local.entity.MessageEntity
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

    @Test fun versionedReferencesReadPerMessageFingerprintsAndFingerprintIsStable() {
        val message = MessageEntity(id = 4, sessionId = 2, speakerType = "character", characterId = 8,
            branchId = "main", content = "原文", structuredContentJson = "{\"chapter\":1}")
        val fingerprint = messageSourceFingerprint(message)
        val result = EncyclopediaEntryEntity(encyclopediaId = 1, sourceMessageId = 4, metaJson =
            """{"source_message_ids":[4],"source_fingerprint_version":1,"source_message_fingerprints":[{"message_id":4,"fingerprint":"$fingerprint"}]}""").sourceReferences()
        assertEquals(fingerprint, result.fingerprints[4L])
        assertEquals(fingerprint, messageSourceFingerprint(message.copy()))
        assertNotEquals(fingerprint, messageSourceFingerprint(message.copy(content = "已编辑")))
    }

    @Test fun malformedFingerprintsAreIgnoredAndDoNotClaimVerification() {
        val result = EncyclopediaEntryEntity(encyclopediaId = 1, sourceMessageId = 4, metaJson =
            """{"source_message_ids":[4],"source_fingerprint_version":99,"source_message_fingerprints":[{"message_id":4,"fingerprint":"short"}]}""").sourceReferences()
        assertTrue(result.fingerprints.isEmpty())
    }

    @Test fun fingerprintArrayIsBoundedBeforeParsing() {
        val valid = "a".repeat(64)
        val items = (1..11).joinToString(",") { "{\"message_id\":99,\"fingerprint\":\"bad\"}" } +
            ", {\"message_id\":1,\"fingerprint\":\"$valid\"}"
        val result = EncyclopediaEntryEntity(encyclopediaId = 1, sourceMessageId = 1,
            metaJson = """{"source_message_ids":[1],"source_fingerprint_version":1,"source_message_fingerprints":[$items]}""").sourceReferences()
        assertTrue(result.fingerprints.isEmpty())
    }
}
