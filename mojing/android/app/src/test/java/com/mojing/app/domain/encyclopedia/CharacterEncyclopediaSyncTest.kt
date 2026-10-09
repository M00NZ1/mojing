package com.mojing.app.domain.encyclopedia

import com.mojing.app.data.local.dao.EncyclopediaEntryDao
import com.mojing.app.data.local.dao.CharacterMirrorMetadata
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
    @Test
    fun conversationIdentitySurvivesConfirmationAndTypeChanges() {
        val note = EncyclopediaEntryEntity(encyclopediaId = 9, sourceSessionId = 7,
            entryType = "character", confidence = "inferred")
        assertTrue(CharacterEncyclopediaSync.isConversationNote(note))
        assertTrue(CharacterEncyclopediaSync.isConversationNote(note.copy(confidence = "confirmed", entryType = "event")))
        assertEquals(false, CharacterEncyclopediaSync.isConversationNote(note.copy(sourceSessionId = null)))
        assertEquals(false, CharacterEncyclopediaSync.isConversationNote(note.copy(metaJson = "{\"linkedCharacterId\":5}")))
    }

    private val entryDao = mockk<EncyclopediaEntryDao>(relaxed = true)

    @Test
    fun createsMirrorWithLinkedCharacterId() = runTest {
        val saved = slot<EncyclopediaEntryEntity>()
        coEvery { entryDao.getCharacterMirrorMetadataPage(9, 0, 128) } returns emptyList()
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
    fun createsAndUpdatesMirrorWithCompletePersonaBeyond8000Characters() = runTest {
        val persona = longPersona()
        assertTrue(persona.length > 8000)
        val saved = slot<EncyclopediaEntryEntity>()
        val existing = mirror(id = 12, linkedCharacterId = 5, content = "旧镜像")
        coEvery { entryDao.getCharacterMirrorMetadataPage(9, 0, 128) } returns
            listOf(CharacterMirrorMetadata(existing.id, existing.metaJson))
        coEvery { entryDao.getById(existing.id) } returns existing
        coEvery { entryDao.upsert(capture(saved)) } returns existing.id

        CharacterEncyclopediaSync.syncCharacterToEntry(
            entryDao,
            CharacterEntity(id = 5, name = "长人设", personaPrompt = persona, boundEncyclopediaId = 9),
        )

        assertEquals(persona, saved.captured.content)
        assertTrue(saved.captured.content.length > 8000)
        assertEquals(persona.length, saved.captured.content.length)

        val newEntryDao = mockk<EncyclopediaEntryDao>(relaxed = true)
        val newSaved = slot<EncyclopediaEntryEntity>()
        coEvery { newEntryDao.getCharacterMirrorMetadataPage(9, 0, 128) } returns emptyList()
        coEvery { newEntryDao.upsert(capture(newSaved)) } returns 13
        CharacterEncyclopediaSync.syncCharacterToEntry(
            newEntryDao,
            CharacterEntity(id = 6, name = "新长人设", personaPrompt = persona, boundEncyclopediaId = 9),
        )

        assertEquals(persona, newSaved.captured.content)
        assertTrue(newSaved.captured.content.length > 8000)
        assertEquals(6L, CharacterEncyclopediaSync.readLinkedCharacterId(newSaved.captured.metaJson))
    }

    @Test
    fun updatesFirstMirrorAndDeletesDuplicates() = runTest {
        val first = mirror(id = 12, linkedCharacterId = 5, metaJson = "{\"linkedCharacterId\":5,\"keep\":true}")
        val duplicate = mirror(id = 13, linkedCharacterId = 5)
        val unrelated = mirror(id = 14, linkedCharacterId = 6)
        val saved = slot<EncyclopediaEntryEntity>()
        coEvery { entryDao.getCharacterMirrorMetadataPage(9, 0, 128) } returns
            listOf(first, duplicate, unrelated).map { CharacterMirrorMetadata(it.id, it.metaJson) }
        coEvery { entryDao.getById(12) } returns first
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
        coEvery { entryDao.getCharacterMirrorMetadataPage(9, 0, 128) } returns listOf(
            mirror(id = 12, linkedCharacterId = 5),
            mirror(id = 13, linkedCharacterId = 5),
            mirror(id = 14, linkedCharacterId = 6),
            EncyclopediaEntryEntity(id = 15, encyclopediaId = 9, entryType = "character", title = "同名用户条目"),
        ).map { CharacterMirrorMetadata(it.id, it.metaJson) }

        CharacterEncyclopediaSync.removeCharacterMirrors(entryDao, 9, 5)

        coVerify(exactly = 1) { entryDao.delete(12) }
        coVerify(exactly = 1) { entryDao.delete(13) }
        coVerify(exactly = 0) { entryDao.delete(14) }
        coVerify(exactly = 0) { entryDao.delete(15) }
    }

    @Test
    fun mirrorScanCrossesPageBoundaryWithoutLoadingEntryBodies() = runTest {
        val metadata = (1L..130L).map { id ->
            CharacterMirrorMetadata(id, if (id == 129L) "{\"linkedCharacterId\":5}" else "{}")
        }
        coEvery { entryDao.getCharacterMirrorMetadataPage(9, 0, 128) } returns metadata.take(128)
        coEvery { entryDao.getCharacterMirrorMetadataPage(9, 128, 128) } returns metadata.drop(128)

        assertEquals(listOf(129L), CharacterEncyclopediaSync.findCharacterMirrorIds(entryDao, 9, 5))
        coVerify(exactly = 0) { entryDao.getByType(any(), any()) }
    }

    private fun mirror(
        id: Long,
        linkedCharacterId: Long,
        metaJson: String? = null,
        content: String = "",
    ) =
        EncyclopediaEntryEntity(
            id = id,
            encyclopediaId = 9,
            entryType = "character",
            metaJson = metaJson ?: "{\"linkedCharacterId\":$linkedCharacterId}",
            content = content,
        )

    private fun longPersona(): String =
        ("角色长人设片段：冷静、敏锐、持续保留原文。\n").repeat(800) + "尾部仍然属于角色正文。"
}
