package com.mojing.app.ui.character

import org.junit.Assert.assertEquals
import org.junit.Test

class CharacterListViewModelTest {
    @Test
    fun createNewDefaultsToUnboundDraft() {
        assertEquals(0L, newCharacterDraft().boundEncyclopediaId)
        assertEquals(7L, newCharacterDraft(7L).boundEncyclopediaId)
    }
}
