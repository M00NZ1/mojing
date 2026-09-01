package com.mojing.app.ui.character

import com.mojing.app.data.local.dao.CharacterDao
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CharacterListViewModelTest {
    @Test
    fun defaultFilterObservesAllCharacters() = runTest {
        val dao = mockk<CharacterDao>()
        every { dao.observeAll() } returns flowOf(emptyList())
        every { dao.observeByEncyclopedia(any()) } returns flowOf(emptyList())

        assertEquals(emptyList<Any>(), dao.observeForCharacterFilter(null).first())
        verify(exactly = 1) { dao.observeAll() }
        verify(exactly = 0) { dao.observeByEncyclopedia(any()) }
    }

    @Test
    fun encyclopediaFilterKeepsExistingScopedQuery() = runTest {
        val dao = mockk<CharacterDao>()
        every { dao.observeByEncyclopedia(7L) } returns flowOf(emptyList())

        assertEquals(emptyList<Any>(), dao.observeForCharacterFilter(7L).first())
        verify(exactly = 1) { dao.observeByEncyclopedia(7L) }
        verify(exactly = 0) { dao.observeAll() }
    }

    @Test
    fun createNewDefaultsToUnboundDraft() {
        assertEquals(0L, newCharacterDraft().boundEncyclopediaId)
        assertEquals(7L, newCharacterDraft(7L).boundEncyclopediaId)
    }
}
