package com.mojing.app.ui.session

import org.junit.Assert.assertEquals
import org.junit.Test

class OpeningCharacterSelectionTest {
    @Test fun firstLoadSelectsOnlyASoleCharacter() {
        assertEquals(emptySet<Long>(), openingCharacterSelection(emptySet(), null))
        assertEquals(setOf(7L), openingCharacterSelection(setOf(7L), null))
        assertEquals(emptySet<Long>(), openingCharacterSelection(setOf(7L, 9L), null))
    }

    @Test fun encyclopediaChangesPreserveChoicesWithoutAddingParticipants() {
        var selected = setOf(7L, 9L)
        selected = openingCharacterSelection(setOf(7L, 11L), selected)
        assertEquals(setOf(7L), selected)
        selected = openingCharacterSelection(setOf(7L, 9L, 11L, 13L), selected)
        assertEquals(setOf(7L), selected)
        assertEquals(emptySet<Long>(), openingCharacterSelection(setOf(11L), selected))
    }

    @Test fun refreshKeepsAnExplicitEmptySelectionEvenWithASoleCharacter() {
        assertEquals(emptySet<Long>(), openingCharacterSelection(setOf(7L), emptySet()))
    }
}
