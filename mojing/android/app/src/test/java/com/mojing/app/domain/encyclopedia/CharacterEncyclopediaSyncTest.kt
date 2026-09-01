package com.mojing.app.domain.encyclopedia

import com.mojing.app.data.local.dao.EncyclopediaEntryDao
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.EncyclopediaEntryEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterEncyclopediaSyncTest {
    private val entryDao = mockk<EncyclopediaEntryDao>(relaxed = true)

    @Test
    fun createsMirrorWithLinkedCharacterId() = runTest {
        val saved = slot<EncyclopediaEntryEntity>()
        coEvery { entryDao.getByType(9, "character") } returns emptyList()
        coEvery { entryDao.upsert(capture(saved)) } returns 12

        val result = CharacterEncyclopediaSync.syncCharacterToEntry(
            entryDao,
            CharacterEntity(
                id = 5,
                name = "  林岚  ",
                personaPrompt = "  冷静的调查员\n善于观察  ",
                boundEncyclopediaId = 9,
            ),
        )

        assertEquals(12L, result?.id)
        assertEquals("林岚", saved.captured.title)
        assertEquals("冷静的调查员", saved.captured.summary)
        assertEquals("冷静的调查员\n善于观察", saved.captured.content)
        assertEquals(5L, CharacterEncyclopediaSync.readLinkedCharacterId(saved.captured.metaJson))
    }

    @Test
    fun updatesFirstMirrorAndDeletesDuplicates() = runTest {
        val first = mirror(id = 12, linkedCharacterId = 5, metaJson = "{\"linkedCharacterId\":5,\"keep\":true}")
        val duplicate = mirror(id = 13, linkedCharacterId = 5)
        val unrelated = mirror(id = 14, linkedCharacterId = 6)
        val saved = slot<EncyclopediaEntryEntity>()
        coEvery { entryDao.getByType(9, "character") } returns listOf(first, duplicate, unrelated)
        coEvery { entryDao.upsert(capture(saved)) } returns 12

        CharacterEncyclopediaSync.syncCharacterToEntry(
            entryDao,
            CharacterEntity(id = 5, name = "新名字", personaPrompt = "新人设", boundEncyclopediaId = 9),
        )

        assertEquals(12L, saved.captured.id)
        assertEquals("新名字", saved.captured.title)
        assertTrue(saved.captured.metaJson.contains("\"keep\":true"))
        coVerify(exactly = 1) { entryDao.delete(13) }
        coVerify(exactly = 0) { entryDao.delete(14) }
    }

    @Test
    fun removeDeletesOnlyMatchingLinkedIds() = runTest {
        coEvery { entryDao.getByType(9, "character") } returns listOf(
            mirror(id = 12, linkedCharacterId = 5),
            mirror(id = 13, linkedCharacterId = 5),
            mirror(id = 14, linkedCharacterId = 6),
            EncyclopediaEntryEntity(id = 15, encyclopediaId = 9, entryType = "character", title = "同名用户条目"),
        )

        CharacterEncyclopediaSync.removeCharacterMirrors(entryDao, 9, 5)

        coVerify(exactly = 1) { entryDao.delete(12) }
        coVerify(exactly = 1) { entryDao.delete(13) }
        coVerify(exactly = 0) { entryDao.delete(14) }
        coVerify(exactly = 0) { entryDao.delete(15) }
    }

    private fun mirror(id: Long, linkedCharacterId: Long, metaJson: String? = null) =
        EncyclopediaEntryEntity(
            id = id,
            encyclopediaId = 9,
            entryType = "character",
            metaJson = metaJson ?: "{\"linkedCharacterId\":$linkedCharacterId}",
        )
}
