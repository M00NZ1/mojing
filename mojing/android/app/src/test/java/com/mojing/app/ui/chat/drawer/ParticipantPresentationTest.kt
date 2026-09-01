package com.mojing.app.ui.chat.drawer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ParticipantPresentationTest {

    @Test
    fun missingCharacterNameDoesNotExposeItsInternalId() {
        val label = participantDisplayName(42L, emptyMap())

        assertEquals("角色资料不可用", label)
        assertFalse(label.contains("42"))
    }

    @Test
    fun characterNameIsTrimmedForTheCard() {
        assertEquals("林墨", participantDisplayName(7L, mapOf(7L to "  林墨  ")))
    }

    @Test
    fun knownSpeakerStrategiesUseProductLabels() {
        assertEquals("自然发言", participantSpeakerStrategyLabel("natural"))
        assertEquals("按顺序发言", participantSpeakerStrategyLabel("LIST"))
        assertEquals("均衡发言", participantSpeakerStrategyLabel(" pooled "))
    }

    @Test
    fun unknownSpeakerStrategyDoesNotExposeItsStoredValue() {
        val label = participantSpeakerStrategyLabel("experimental-owner")

        assertEquals("自定义发言", label)
        assertFalse(label.contains("experimental-owner"))
    }
}
