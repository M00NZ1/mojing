package com.mojing.app.ui.chat.search

import com.mojing.app.data.SecureStorage
import com.mojing.app.data.local.dao.AttachmentDao
import com.mojing.app.data.local.dao.CharacterDao
import com.mojing.app.data.local.dao.CharacterNameRow
import com.mojing.app.data.local.dao.SessionWorldDao
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.prefs.UiPreferencesRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchPresentationLoaderTest {
    @Test fun speakerLabelsUseOnlyBoundedNameProjectionsAndKeepMissingCharacterReadable() = runTest {
        val characters = mockk<CharacterDao>()
        val worlds = mockk<SessionWorldDao>()
        val storage = mockk<SecureStorage>()
        every { storage.userName } returns "旅人"
        coEvery { worlds.getNarratorNameBySession(1L) } returns "叙述者"
        coEvery { characters.getNamesByIds(listOf(7L, 8L, 9L)) } returns
            listOf(CharacterNameRow(7L, "阿沅"), CharacterNameRow(8L, ""))
        val loader = SearchPresentationLoader(characters, mockk<AttachmentDao>(), worlds,
            storage, mockk<UiPreferencesRepository>())
        fun row(id: Long, type: String, characterId: Long? = null) =
            MessageEntity(id = id, sessionId = 1L, speakerType = type, characterId = characterId)
        val rows = listOf(row(1L, "user"), row(2L, "narrator"), row(3L, "character", 7L),
            row(4L, "character", 8L), row(5L, "character", 9L), row(6L, "character", 7L))

        val labels = loader.loadSpeakerLabels(1L, rows)

        assertEquals(listOf("旅人", "叙述者", "阿沅", "角色", "角色", "阿沅"),
            rows.map(labels::forMessage))
        coVerify(exactly = 1) { characters.getNamesByIds(listOf(7L, 8L, 9L)) }
        coVerify(exactly = 0) { characters.getById(any()) }
        coVerify(exactly = 0) { worlds.getBySession(any()) }
    }
}
