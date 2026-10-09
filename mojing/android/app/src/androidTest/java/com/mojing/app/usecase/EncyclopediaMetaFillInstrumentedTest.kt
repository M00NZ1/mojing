package com.mojing.app.usecase

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.EncyclopediaEntryEntity
import com.mojing.app.domain.encyclopedia.CharacterEncyclopediaSync
import com.mojing.app.domain.encyclopedia.EncyclopediaEntryMetaMerge
import com.mojing.app.domain.usecase.SaveCharacterEntryUseCase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncyclopediaMetaFillInstrumentedTest {
    private lateinit var db: AppDatabase
    private lateinit var save: SaveCharacterEntryUseCase
    private var world = 0L

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        save = SaveCharacterEntryUseCase(db)
        world = db.encyclopediaDao().upsert(EncyclopediaEntity(name = "合成世界"))
    }
    @After fun tearDown() { db.close() }

    @Test fun fillsOnlyStillMissingFieldsAndPreservesConcurrentEdits() = runBlocking {
        val requested = save(EncyclopediaEntryEntity(encyclopediaId = world, title = "原名", content = "原文"))
        val edited = save.saveEdited(requested.copy(title = "手动姓名", content = "手动正文",
            tags = "手动标签", confidence = "inferred", isFeatured = true, coverImagePath = "local-cover",
            metaJson = "{\"age\":\"手动年龄\",\"occupation\":\"\"}"))
        assertTrue(save.fillMissingGeneratedFields(requested, mapOf(
            "title" to "模型姓名", "content" to "模型正文", "tags" to "模型标签", "summary" to "补全摘要",
            "age" to "模型年龄", "occupation" to "补全职业", "unknown" to "禁止字段"), setOf("age", "occupation")))
        val filled = db.encyclopediaEntryDao().getById(requested.id)!!
        assertEquals(edited.copy(summary = "补全摘要", metaJson = filled.metaJson, updatedAt = filled.updatedAt), filled)
        val meta = EncyclopediaEntryMetaMerge.parseMetaJson(filled.metaJson)
        assertEquals("手动年龄", meta["age"].asString)
        assertEquals("补全职业", meta["occupation"].asString)
        assertFalse(meta.has("unknown"))
        assertFalse(save.fillMissingGeneratedFields(requested, mapOf("summary" to "再次改写"), emptySet()))
        assertEquals(filled, db.encyclopediaEntryDao().getById(requested.id))
    }

    @Test fun deletedOrChangedTypeTargetIsNeverRecreatedOrRetargeted() = runBlocking {
        val requested = save(EncyclopediaEntryEntity(encyclopediaId = world, title = "删除目标"))
        db.encyclopediaEntryDao().delete(requested.id)
        assertFalse(save.fillMissingGeneratedFields(requested, mapOf("content" to "迟到正文"), emptySet()))
        assertNull(db.encyclopediaEntryDao().getById(requested.id))
        val changed = save(EncyclopediaEntryEntity(encyclopediaId = world, title = "改类型目标"))
        val current = save.saveEdited(changed.copy(entryType = "location", summary = "新的用途"))
        assertFalse(save.fillMissingGeneratedFields(changed, mapOf("content" to "旧类型正文"), emptySet()))
        assertEquals(current, db.encyclopediaEntryDao().getById(changed.id))
    }

    @Test fun characterMirrorKeepsLatestPersonaAndBindingWhileMetadataIsFilled() = runBlocking {
        val requested = save(EncyclopediaEntryEntity(encyclopediaId = world, title = "合成角色",
            entryType = "character", content = "原人设"))
        val characterId = CharacterEncyclopediaSync.readLinkedCharacterId(requested.metaJson)!!
        val edited = save.saveEdited(requested.copy(title = "新角色名", content = "用户新人设"))
        assertTrue(save.fillMissingGeneratedFields(requested, mapOf(
            "title" to "旧模型名", "content" to "旧模型人设", "age" to "三十"), setOf("age")))
        val filled = db.encyclopediaEntryDao().getById(requested.id)!!
        assertEquals(edited.content, filled.content)
        assertEquals(characterId, CharacterEncyclopediaSync.readLinkedCharacterId(filled.metaJson))
        val character = db.characterDao().getById(characterId)!!
        assertEquals("新角色名", character.name)
        assertEquals("用户新人设", character.personaPrompt)
        assertEquals(world, character.boundEncyclopediaId)
    }
}
