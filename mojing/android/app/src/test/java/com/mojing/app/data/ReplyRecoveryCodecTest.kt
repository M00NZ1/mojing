package com.mojing.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyRecoveryCodecTest {
    private val snapshot = ReplyRecoverySnapshot(
        token = "123e4567-e89b-12d3-a456-426614174000",
        sessionId = 42L,
        branchId = "main",
        speakerType = "character",
        characterId = 7L,
        anchorMessageId = 81L,
        swipeGroupId = "swipe-1",
        swipeSourceMessageId = 80L,
        rawText = "尚未保存的回复",
        startedAt = 100L,
        updatedAt = 120L,
    )

    @Test
    fun roundTripPreservesRecoveryFields() {
        assertEquals(snapshot, ReplyRecoveryCodec.decode(ReplyRecoveryCodec.encode(snapshot)))
    }

    @Test
    fun narratorDoesNotAcceptCharacterBinding() {
        assertThrows(IllegalArgumentException::class.java) {
            ReplyRecoveryCodec.encode(snapshot.copy(speakerType = "narrator", characterId = 7L))
        }
    }

    @Test
    fun blankTextAndUnknownVersionAreUnreadableInputs() {
        assertThrows(IllegalArgumentException::class.java) {
            ReplyRecoveryCodec.encode(snapshot.copy(rawText = "   "))
        }
        val unknownVersion = ReplyRecoveryCodec.encode(snapshot).replace("\"version\":1", "\"version\":99")
        assertThrows(IllegalArgumentException::class.java) { ReplyRecoveryCodec.decode(unknownVersion) }
    }

    @Test
    fun encodedRecoveryRecordContainsOnlyRecoveryFields() {
        val raw = ReplyRecoveryCodec.encode(snapshot)
        assertTrue(raw.contains("\"rawText\":\"尚未保存的回复\""))
        assertTrue(!raw.contains("apiKey", ignoreCase = true))
    }

    @Test
    fun recoveryKeysAreIndependentPerSession() {
        assertTrue(ReplyRecoveryKeys.forSession(1L) != ReplyRecoveryKeys.forSession(2L))
        assertTrue(ReplyRecoveryKeys.forSession(1L).endsWith("_1"))
    }
}
