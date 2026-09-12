package com.mojing.app.usecase

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonParser
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.WorldLoreEntryEntity
import com.mojing.app.data.local.entity.WorldTemplateEntity
import com.mojing.app.domain.usecase.PromoteWorldTemplateUseCase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PromoteWorldTemplateUseCaseInstrumentedTest {
    private lateinit var database: AppDatabase
    private lateinit var promote: PromoteWorldTemplateUseCase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        promote = PromoteWorldTemplateUseCase(database)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun promotionCopiesLoreAndRepeatedCallsKeepCanonicalEdits() = runBlocking {
        val templateId = database.worldTemplateDao().upsert(
            WorldTemplateEntity(templateId = "harbor", label = "雾港", summary = "潮汐世界", worldPrompt = "规则")
        )
        val loreId = database.worldLoreEntryDao().upsert(
            WorldLoreEntryEntity(
                worldTemplateId = templateId, title = "潮汐钟", entryType = "地点",
                keywordsJson = "[\"潮汐\",\"钟\"]", content = "每晚鸣响", sortOrder = 3, isCore = true,
            ),
        )

        val first = promote(templateId)
        val copied = database.encyclopediaEntryDao().getByEncyclopedia(first.id).single()
        assertEquals("location", copied.entryType)
        assertEquals("[\"潮汐\",\"钟\"]", copied.tags)
        assertEquals("每晚鸣响", copied.content)
        assertEquals(templateId, database.legacyWorldMappingDao().getByTemplateId(templateId)?.worldTemplateId)
        assertEquals(loreId, database.legacyWorldMappingDao().getLoreById(loreId)?.loreEntryId)
        assertEquals("legacy_world_lore", JsonParser.parseString(copied.metaJson).asJsonObject["source"].asString)

        database.encyclopediaDao().updateName(first.id, "用户编辑的雾港", 99L)
        val second = promote(templateId)
        assertEquals(first.id, second.id)
        assertEquals("用户编辑的雾港", requireNotNull(database.encyclopediaDao().getById(first.id)).name)
        assertEquals(1, database.encyclopediaEntryDao().getByEncyclopedia(first.id).size)
        assertNotNull(database.worldTemplateDao().getById(templateId))
        assertNotNull(database.worldLoreEntryDao().getByTemplate(templateId).single())
    }

    @Test
    fun sameNameDifferentWorldGetsDeterministicSuffix() = runBlocking {
        database.encyclopediaDao().upsert(EncyclopediaEntity(name = "雾港"))
        val templateId = database.worldTemplateDao().upsert(
            WorldTemplateEntity(templateId = "other", label = "雾港", worldPrompt = "另一套规则")
        )
        val result = promote(templateId)
        assertNotEquals("雾港", result.name)
        assertEquals("雾港", result.name.substringBefore(" · "))
    }
}
