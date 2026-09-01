package com.mojing.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.domain.usecase.SaveCharacterBindingUseCase
import com.mojing.app.domain.usecase.SaveCharacterEntryUseCase
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 验证内置种子：旧库未绑定角色在 [SeedDataManager.mergeBuiltinPresetsFromAsset] 后补绑到官方示例百科，
 * 并在百科内生成 linkedCharacterId 镜像条目。
 */
@RunWith(AndroidJUnit4::class)
class SeedDataManagerInstrumentedTest {

    private lateinit var db: AppDatabase
    private lateinit var seedDataManager: SeedDataManager
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        seedDataManager = SeedDataManager(
            characterDao = db.characterDao(),
            worldTemplateDao = db.worldTemplateDao(),
            encyclopediaDao = db.encyclopediaDao(),
            encyclopediaEntryDao = db.encyclopediaEntryDao(),
            entryRelationDao = db.entryRelationDao(),
            timelineEventDao = db.timelineEventDao(),
            configDao = db.configDao(),
            saveCharacterBinding = SaveCharacterBindingUseCase(db),
            saveCharacterEntry = SaveCharacterEntryUseCase(db),
            context = context,
        )
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun mergeRebindsLegacySeedCharacterAndCreatesMirrorEntry() = runBlocking {
        val legacyId = db.characterDao().upsert(
            CharacterEntity(
                name = "星野澄",
                personaPrompt = "旧人设",
                boundEncyclopediaId = 0L,
            ),
        )
        seedDataManager.mergeBuiltinPresetsFromAsset()

        val updated = db.characterDao().getById(legacyId)
        assertNotNull(updated)
        assertTrue("应补绑百科", updated!!.boundEncyclopediaId > 0L)

        val enc = db.encyclopediaDao().getById(updated.boundEncyclopediaId)
        assertNotNull(enc)
        assertTrue(enc!!.name.contains("内置"))

        val entries = db.encyclopediaEntryDao().getByEncyclopedia(updated.boundEncyclopediaId)
        val mirror = entries.firstOrNull { entry ->
            runCatching {
                JsonParser.parseString(entry.metaJson.ifBlank { "{}" }).asJsonObject
                    .get("linkedCharacterId")?.asLong
            }.getOrNull() == legacyId
        }
        assertNotNull("应有 linkedCharacterId 镜像条目", mirror)
        assertEquals("星野澄", mirror!!.title)
        assertEquals("character", mirror.entryType)
    }

    @Test
    fun mergeSeedsAllBuiltinEntryTypesAndExtras() = runBlocking {
        seedDataManager.mergeBuiltinPresetsFromAsset()
        val enc = db.encyclopediaDao().getAll().first { it.name.contains("内置") }
        val entries = db.encyclopediaEntryDao().getByEncyclopedia(enc.id)
        val types = entries.map { it.entryType }.toSet()
        listOf(
            "world", "faction", "location", "item", "event",
            "skill", "creature", "profession", "concept", "timeline", "character",
        ).forEach { t ->
            assertTrue("缺少示例类型 $t", t in types)
        }
        assertTrue(
            "应有沉淀示例（confidence=inferred）",
            entries.any { it.confidence == "inferred" },
        )
        assertTrue(
            "时间线 Tab 应有事件",
            db.timelineEventDao().getByEncyclopedia(enc.id).size >= 3,
        )
        assertTrue(
            "关系图 Tab 应有连线",
            db.entryRelationDao().getByEncyclopedia(enc.id).size >= 5,
        )
    }
}
