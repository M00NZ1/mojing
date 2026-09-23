package com.mojing.app.engine

import com.mojing.app.domain.engine.CharacterSnapshotCadence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CharacterSnapshotCadenceTest {
    @Test fun dueAfterFifteenNewUserMessagesEvenWhenContextWindowSlides() {
        val userIds = (2L..430L step 2).toList()
        fun recentSince(cursor: Long, newest: Long) = userIds.filter { it > cursor && it <= newest }.takeLast(15).reversed()
        val firstAttempt = CharacterSnapshotCadence.dueUserMessageId(recentSince(370L, 400L))
        assertEquals(400L, firstAttempt)

        assertNull(CharacterSnapshotCadence.dueUserMessageId(recentSince(firstAttempt!!, 402L)))
        assertEquals(430L, CharacterSnapshotCadence.dueUserMessageId(recentSince(firstAttempt, 430L)))
    }

    @Test fun failedAttemptCursorWaitsForAnotherFullInterval() {
        val userIds = (2L..40L step 2).toList()
        assertNull(CharacterSnapshotCadence.dueUserMessageId(userIds.filter { it > 12L }.reversed()))
        assertEquals(40L, CharacterSnapshotCadence.dueUserMessageId(userIds.filter { it > 10L }.reversed()))
        assertNull(CharacterSnapshotCadence.dueUserMessageId(userIds.filter { it > 40L }.reversed()))
    }

    @Test fun noUsableUserMessageDoesNotTriggerExtraction() {
        assertNull(CharacterSnapshotCadence.dueUserMessageId(emptyList()))
        assertNull(CharacterSnapshotCadence.dueUserMessageId(List(15) { 0L }))
    }
}
