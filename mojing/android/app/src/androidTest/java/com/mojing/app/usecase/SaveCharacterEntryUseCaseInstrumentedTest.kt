package com.mojing.app.usecase

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.EncyclopediaEntryEntity
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.EntryRelationEntity
import com.mojing.app.data.local.entity.EntryVersionEntity
import com.mojing.app.data.local.entity.TimelineEventEntity
import com.mojing.app.domain.encyclopedia.CharacterEncyclopediaSync
import com.mojing.app.domain.usecase.SaveCharacterEntryUseCase
import com.mojing.app.domain.usecase.DeleteEncyclopediaEntryUseCase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SaveCharacterEntryUseCaseInstrumentedTest {
    private lateinit var database: AppDatabase
    private lateinit var saveCharacterEntry: SaveCharacterEntryUseCase
    private var firstEncyclopediaId: Long = 0
    private var secondEncyclopediaId: Long = 0

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        saveCharacterEntry = SaveCharacterEntryUseCase(database)
        firstEncyclopediaId = database.encyclopediaDao().upsert(EncyclopediaEntity(name = "第一百科"))
        secondEncyclopediaId = database.encyclopediaDao().upsert(EncyclopediaEntity(name = "第二百科"))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun characterEntryReusesBoundNameBeyondFirstMetadataPage() = runBlocking {
        val characterDao = database.characterDao()
        val targetId = characterDao.upsert(CharacterEntity(
            name = "星河", boundEncyclopediaId = firstEncyclopediaId,
            personaPrompt = "旧人设".repeat(1000), createdAt = 100L,
        ))
        repeat(129) { n ->
            characterDao.upsert(CharacterEntity(name = "其他角色$n",
                boundEncyclopediaId = firstEncyclopediaId, createdAt = 100L))
        }
        val otherWorldId = characterDao.upsert(CharacterEntity(name = "星河",
            boundEncyclopediaId = secondEncyclopediaId, createdAt = 100L))

        val saved = saveCharacterEntry(EncyclopediaEntryEntity(
            encyclopediaId = firstEncyclopediaId, title = "星河", entryType = "character", content = "新人设",
        ))

        assertEquals(targetId, CharacterEncyclopediaSync.readLinkedCharacterId(saved.metaJson))
        assertEquals("新人设", characterDao.getById(targetId)?.personaPrompt)
        assertEquals(secondEncyclopediaId, characterDao.getById(otherWorldId)?.boundEncyclopediaId)
    }

    @Test
    fun updatingEntryPreservesVersionRelationAndTimelineChildren() = runBlocking {
        val created = saveCharacterEntry(
            EncyclopediaEntryEntity(
                encyclopediaId = firstEncyclopediaId,
                title = "林岚",
                entryType = "character",
                content = "冷静的调查员",
            ),
        )
        val characterId = CharacterEncyclopediaSync.readLinkedCharacterId(created.metaJson)!!
        val other = saveCharacterEntry(
            EncyclopediaEntryEntity(
                encyclopediaId = firstEncyclopediaId,
                title = "关联条目",
                entryType = "world",
            ),
        )
        database.entryVersionDao().insert(EntryVersionEntity(entryId = created.id, version = 1))
        database.entryRelationDao().upsert(
            EntryRelationEntity(
                encyclopediaId = firstEncyclopediaId,
                fromEntryId = created.id,
                toEntryId = other.id,
            ),
        )
        val timelineId = database.timelineEventDao().upsert(
            TimelineEventEntity(
                encyclopediaId = firstEncyclopediaId,
                entryId = created.id,
                title = "相遇",
            ),
        )

        val updated = saveCharacterEntry(
            created.copy(
                title = "林岚·改",
                metaJson = "{\"linkedCharacterId\":999999}",
            ),
        )

        assertEquals(characterId, CharacterEncyclopediaSync.readLinkedCharacterId(updated.metaJson))
        assertEquals(1, database.entryVersionDao().getByEntry(created.id).size)
        assertEquals(1, database.entryRelationDao().getByEntry(created.id).size)
        assertNotNull(database.timelineEventDao().getById(timelineId))
    }

    @Test
    fun editAndHistoryCommitTogetherAndFailedEditLeavesNoVersion() = runBlocking {
        val original = saveCharacterEntry(EncyclopediaEntryEntity(
            encyclopediaId = firstEncyclopediaId, title = "旧标题", content = "旧正文",
        ))
        val updated = saveCharacterEntry.saveEdited(original.copy(content = "新正文"))
        assertEquals("新正文", database.encyclopediaEntryDao().getById(original.id)?.content)
        assertEquals("旧正文", database.entryVersionDao().getByEntry(original.id).single().content)
        saveCharacterEntry.saveEdited(updated)
        assertEquals(1, database.entryVersionDao().getByEntry(original.id).size)
        try {
            saveCharacterEntry.saveEdited(updated.copy(encyclopediaId = secondEncyclopediaId, content = "失败正文"))
            fail("跨百科编辑应失败")
        } catch (_: IllegalArgumentException) { }
        assertEquals(1, database.entryVersionDao().getByEntry(original.id).size)
        assertEquals("新正文", database.encyclopediaEntryDao().getById(original.id)?.content)
        database.encyclopediaEntryDao().delete(original.id)
        try {
            saveCharacterEntry.saveEdited(updated.copy(content = "迟到的编辑"))
            fail("已删除条目不可恢复写入")
        } catch (_: IllegalArgumentException) { }
        assertNull(database.encyclopediaEntryDao().getById(original.id))
    }

    @Test
    fun editedOld8000PrefixRestoresLinkedCharacterAndMirrorWhileVersionKeepsPrefix() = runBlocking {
        val fullPersona = longPersona()
        assertTrue(fullPersona.length > 8000)
        val oldPrefix = fullPersona.take(8000)
        val characterId = database.characterDao().upsert(CharacterEntity(
            name = "长人设角色",
            personaPrompt = fullPersona,
            boundEncyclopediaId = firstEncyclopediaId,
        ))
        val mirror = database.encyclopediaEntryDao().upsert(EncyclopediaEntryEntity(
            encyclopediaId = firstEncyclopediaId,
            title = "长人设角色",
            summary = "旧摘要",
            entryType = "character",
            content = oldPrefix,
            metaJson = "{\"linkedCharacterId\":$characterId}",
        )).let { database.encyclopediaEntryDao().getById(it)!! }

        val saved = saveCharacterEntry.saveEdited(mirror.copy(title = "长人设角色·改", summary = "新摘要"))

        assertEquals(fullPersona, database.characterDao().getById(characterId)?.personaPrompt)
        assertEquals(fullPersona, database.encyclopediaEntryDao().getById(mirror.id)?.content)
        assertEquals(fullPersona, saved.content)
        assertEquals("长人设角色·改", database.encyclopediaEntryDao().getById(mirror.id)?.title)
        assertEquals(1, database.entryVersionDao().getByEntry(mirror.id).size)
        assertEquals(oldPrefix, database.entryVersionDao().getByEntry(mirror.id).single().content)
        assertEquals(8000, database.entryVersionDao().getByEntry(mirror.id).single().content.length)
    }

    @Test
    fun editedExplicitShortContentIsSavedWithoutRestoringLinkedCharacterSuffix() = runBlocking {
        val fullPersona = longPersona()
        val shortContent = "用户明确改成的短稿"
        val characterId = database.characterDao().upsert(CharacterEntity(
            name = "短稿角色", personaPrompt = fullPersona, boundEncyclopediaId = firstEncyclopediaId,
        ))
        val mirror = database.encyclopediaEntryDao().upsert(EncyclopediaEntryEntity(
            encyclopediaId = firstEncyclopediaId, title = "短稿角色", entryType = "character",
            content = fullPersona.take(8000), metaJson = "{\"linkedCharacterId\":$characterId}",
        )).let { database.encyclopediaEntryDao().getById(it)!! }

        saveCharacterEntry.saveEdited(mirror.copy(content = shortContent))

        assertEquals(shortContent, database.characterDao().getById(characterId)?.personaPrompt)
        assertEquals(shortContent, database.encyclopediaEntryDao().getById(mirror.id)?.content)
    }

    @Test
    fun editedDifferent8000BodyIsSavedWithoutRestoringLinkedCharacterSuffix() = runBlocking {
        val fullPersona = longPersona()
        val differentContent = "不同正文".repeat(8000 / 4)
        assertEquals(8000, differentContent.length)
        val characterId = database.characterDao().upsert(CharacterEntity(
            name = "不同正文角色", personaPrompt = fullPersona, boundEncyclopediaId = firstEncyclopediaId,
        ))
        val mirror = database.encyclopediaEntryDao().upsert(EncyclopediaEntryEntity(
            encyclopediaId = firstEncyclopediaId, title = "不同正文角色", entryType = "character",
            content = fullPersona.take(8000), metaJson = "{\"linkedCharacterId\":$characterId}",
        )).let { database.encyclopediaEntryDao().getById(it)!! }

        saveCharacterEntry.saveEdited(mirror.copy(content = differentContent))

        assertEquals(differentContent, database.characterDao().getById(characterId)?.personaPrompt)
    }

    @Test
    fun editedNon8000BoundaryContentIsSavedWithoutRestoringLinkedCharacterSuffix() = runBlocking {
        val fullPersona = longPersona()
        val boundaryContent = fullPersona.take(8001)
        assertEquals(8001, boundaryContent.length)
        val characterId = database.characterDao().upsert(CharacterEntity(
            name = "边界角色", personaPrompt = fullPersona, boundEncyclopediaId = firstEncyclopediaId,
        ))
        val mirror = database.encyclopediaEntryDao().upsert(EncyclopediaEntryEntity(
            encyclopediaId = firstEncyclopediaId, title = "边界角色", entryType = "character",
            content = fullPersona.take(8000), metaJson = "{\"linkedCharacterId\":$characterId}",
        )).let { database.encyclopediaEntryDao().getById(it)!! }

        saveCharacterEntry.saveEdited(mirror.copy(content = boundaryContent))

        assertEquals(boundaryContent, database.characterDao().getById(characterId)?.personaPrompt)
    }

    @Test
    fun edited7999BoundaryContentIsSavedWithoutRestoringLinkedCharacterSuffix() = runBlocking {
        val fullPersona = longPersona()
        val boundaryContent = fullPersona.take(7999)
        assertEquals(7999, boundaryContent.length)
        val characterId = database.characterDao().upsert(CharacterEntity(
            name = "短边界角色", personaPrompt = fullPersona, boundEncyclopediaId = firstEncyclopediaId,
        ))
        val mirror = database.encyclopediaEntryDao().upsert(EncyclopediaEntryEntity(
            encyclopediaId = firstEncyclopediaId, title = "短边界角色", entryType = "character",
            content = fullPersona.take(8000), metaJson = "{\"linkedCharacterId\":$characterId}",
        )).let { database.encyclopediaEntryDao().getById(it)!! }

        saveCharacterEntry.saveEdited(mirror.copy(content = boundaryContent))

        assertEquals(boundaryContent, database.characterDao().getById(characterId)?.personaPrompt)
    }

    @Test
    fun edited8000PrefixDoesNotRestoreWhenLinkedCharacterDoesNotMatchPrefix() = runBlocking {
        val fullPersona = longPersona()
        val oldPrefix = "不同旧正文".repeat(8000 / 5)
        assertEquals(8000, oldPrefix.length)
        val characterId = database.characterDao().upsert(CharacterEntity(
            name = "不匹配角色", personaPrompt = fullPersona, boundEncyclopediaId = firstEncyclopediaId,
        ))
        val mirror = database.encyclopediaEntryDao().upsert(EncyclopediaEntryEntity(
            encyclopediaId = firstEncyclopediaId, title = "不匹配角色", entryType = "character",
            content = oldPrefix, metaJson = "{\"linkedCharacterId\":$characterId}",
        )).let { database.encyclopediaEntryDao().getById(it)!! }

        saveCharacterEntry.saveEdited(mirror.copy(summary = "只改摘要"))

        assertEquals(oldPrefix, database.characterDao().getById(characterId)?.personaPrompt)
    }

    @Test
    fun edited8000PrefixWithoutLinkedIdDoesNotRestoreByName() = runBlocking {
        val fullPersona = longPersona()
        val oldPrefix = fullPersona.take(8000)
        val characterId = database.characterDao().upsert(CharacterEntity(
            name = "无绑定角色", personaPrompt = fullPersona, boundEncyclopediaId = firstEncyclopediaId,
        ))
        val mirror = database.encyclopediaEntryDao().upsert(EncyclopediaEntryEntity(
            encyclopediaId = firstEncyclopediaId, title = "无绑定角色", entryType = "character",
            content = oldPrefix, metaJson = "{}",
        )).let { database.encyclopediaEntryDao().getById(it)!! }

        saveCharacterEntry.saveEdited(mirror.copy(summary = "只改摘要"))

        assertEquals(oldPrefix, database.characterDao().getById(characterId)?.personaPrompt)
    }

    @Test
    fun confirmingConversationNotePreservesSameNameCharacterAndMirror() = runBlocking {
        val mirror = saveCharacterEntry(EncyclopediaEntryEntity(
            encyclopediaId = firstEncyclopediaId, title = "林岚",
            entryType = "character", content = "完整角色设定",
        ))
        val characterId = CharacterEncyclopediaSync.readLinkedCharacterId(mirror.metaJson)!!
        val note = EncyclopediaEntryEntity(encyclopediaId = firstEncyclopediaId,
            title = "林岚", entryType = "character", content = "对话中新线索",
            confidence = "inferred", sourceSessionId = 7, sourceMessageId = 8)
        val noteId = database.encyclopediaEntryDao().upsert(note)
        val saved = saveCharacterEntry(note.copy(id = noteId, confidence = "confirmed",
            sourceSessionId = null, metaJson = "{\"linkedCharacterId\":$characterId}"))
        assertNull(CharacterEncyclopediaSync.readLinkedCharacterId(saved.metaJson))
        assertEquals(7L, saved.sourceSessionId)
        assertEquals("完整角色设定", database.characterDao().getById(characterId)?.personaPrompt)
        assertNotNull(database.encyclopediaEntryDao().getById(mirror.id))
        assertEquals(1, mirrors(firstEncyclopediaId, characterId).size)
        val edited = saveCharacterEntry(saved.copy(content = "已整理的线索"))
        assertNull(CharacterEncyclopediaSync.readLinkedCharacterId(edited.metaJson))
        assertEquals("完整角色设定", database.characterDao().getById(characterId)?.personaPrompt)
    }

    @Test
    fun changingCharacterEntryTypeUnbindsWithoutDeletingCharacter() = runBlocking {
        val created = saveCharacterEntry(
            EncyclopediaEntryEntity(
                encyclopediaId = firstEncyclopediaId,
                title = "林岚",
                entryType = "character",
                content = "冷静的调查员",
            ),
        )
        val characterId = CharacterEncyclopediaSync.readLinkedCharacterId(created.metaJson)!!

        val changed = saveCharacterEntry(created.copy(entryType = "world"))

        assertNull(CharacterEncyclopediaSync.readLinkedCharacterId(changed.metaJson))
        assertEquals(0L, database.characterDao().getById(characterId)?.boundEncyclopediaId)
        assertNotNull(database.characterDao().getById(characterId))
    }

    @Test
    fun crossEncyclopediaMoveIsRejectedWithoutChangingEntryOrCharacter() = runBlocking {
        val created = saveCharacterEntry(
            EncyclopediaEntryEntity(
                encyclopediaId = firstEncyclopediaId,
                title = "林岚",
                entryType = "character",
            ),
        )
        val characterId = CharacterEncyclopediaSync.readLinkedCharacterId(created.metaJson)!!

        try {
            saveCharacterEntry(created.copy(encyclopediaId = secondEncyclopediaId))
            fail("跨百科移动应被明确拒绝")
        } catch (_: Exception) {
            // 预期失败；下方验证整个事务已回滚。
        }

        assertEquals(firstEncyclopediaId, database.encyclopediaEntryDao().getById(created.id)?.encyclopediaId)
        assertEquals(firstEncyclopediaId, database.characterDao().getById(characterId)?.boundEncyclopediaId)
        assertEquals(1, mirrors(firstEncyclopediaId, characterId).size)
    }

    @Test
    fun relationWriteRequiresBothEndpointsInSameEncyclopedia() = runBlocking {
        val firstEntry = database.encyclopediaEntryDao().upsert(
            EncyclopediaEntryEntity(encyclopediaId = firstEncyclopediaId, title = "第一端点"),
        )
        val secondEntry = database.encyclopediaEntryDao().upsert(
            EncyclopediaEntryEntity(encyclopediaId = firstEncyclopediaId, title = "第二端点"),
        )
        val otherWorldEntry = database.encyclopediaEntryDao().upsert(
            EncyclopediaEntryEntity(encyclopediaId = secondEncyclopediaId, title = "异世界端点"),
        )
        val dao = database.entryRelationDao()

        assertEquals(
            true,
            dao.upsertIfEndpointsBelongToEncyclopedia(
                EntryRelationEntity(
                    encyclopediaId = firstEncyclopediaId,
                    fromEntryId = firstEntry,
                    toEntryId = secondEntry,
                ),
            ),
        )
        assertEquals(
            false,
            dao.upsertIfEndpointsBelongToEncyclopedia(
                EntryRelationEntity(
                    encyclopediaId = firstEncyclopediaId,
                    fromEntryId = firstEntry,
                    toEntryId = otherWorldEntry,
                ),
            ),
        )
        assertEquals(
            false,
            dao.upsertIfEndpointsBelongToEncyclopedia(
                EntryRelationEntity(
                    encyclopediaId = firstEncyclopediaId,
                    fromEntryId = firstEntry,
                    toEntryId = 999999L,
                ),
            ),
        )
        assertEquals(1, dao.getByEncyclopedia(firstEncyclopediaId).size)
    }

    @Test
    fun deletingCharacterEntryRemovesLinkedCharacterAndMirror() = runBlocking {
        val created = saveCharacterEntry(
            EncyclopediaEntryEntity(
                encyclopediaId = firstEncyclopediaId,
                title = "待删除",
                entryType = "character",
            ),
        )
        val characterId = CharacterEncyclopediaSync.readLinkedCharacterId(created.metaJson)!!

        DeleteEncyclopediaEntryUseCase(database)(created.id, firstEncyclopediaId)

        assertNull(database.encyclopediaEntryDao().getById(created.id))
        assertNull(database.characterDao().getById(characterId))
        assertEquals(0, mirrors(firstEncyclopediaId, characterId).size)
    }

    private suspend fun mirrors(encyclopediaId: Long, characterId: Long) =
        database.encyclopediaEntryDao().getByType(encyclopediaId, "character")
            .filter { CharacterEncyclopediaSync.readLinkedCharacterId(it.metaJson) == characterId }

    private fun longPersona(): String =
        ("角色长人设片段：冷静、敏锐、持续保留原文。\n").repeat(800) + "尾部仍然属于角色正文。"
}
