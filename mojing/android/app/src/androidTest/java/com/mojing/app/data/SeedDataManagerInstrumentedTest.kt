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

/** Versioned sample installation, conservative retirement, and recovery. */
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
    fun installsOneEncyclopediaOneWorldAndTwoCharactersOnlyOnce() = runBlocking {
        seedDataManager.seedIfNeeded()
        val enc = db.encyclopediaDao().getAll().single()
        assertEquals("雾港来信·设定集", enc.name)
        assertEquals(1, db.worldTemplateDao().getAll().size)
        assertEquals(setOf("沈照", "林汐"), db.characterDao().getAll().map { it.name }.toSet())
        val entries = db.encyclopediaEntryDao().getByEncyclopedia(enc.id)
        assertEquals(9, entries.size)
        assertEquals(2, entries.count { it.entryType == "character" })
        assertEquals(2, db.timelineEventDao().getByEncyclopedia(enc.id).size)
        assertEquals(3, db.entryRelationDao().getByEncyclopedia(enc.id).size)
        val removed = db.characterDao().getAll().first()
        com.mojing.app.domain.usecase.DeleteCharacterUseCase(db)(removed.id)
        seedDataManager.mergeBuiltinPresetsFromAsset()
        assertEquals(1, db.characterDao().getAll().size)
    }

    private suspend fun installLegacy() {
        val data = context.resources.openRawResource(com.mojing.app.R.raw.legacy_seed_v1).bufferedReader().use {
            com.google.gson.Gson().fromJson(it, SeedDataManager.SeedData::class.java)
        }
        seedDataManager.installCatalog(data)
    }

    @Test
    fun retiresPristineCatalogAndCanRestoreWithoutOverwritingNewCatalog() = runBlocking {
        installLegacy()
        val oldIds = db.characterDao().getAll().map { it.id }.toSet()
        val upgrade = BuiltinCatalogUpgrade(db, context, SecureStorage())
        upgrade.installCurrent(seedDataManager)
        assertEquals(2, db.characterDao().getAll().size)
        assertEquals(1, db.encyclopediaDao().getAll().size)
        assertEquals(1, db.worldTemplateDao().getAll().size)
        upgrade.installCurrent(seedDataManager)
        assertEquals(2, db.characterDao().getAll().size)
        upgrade.restoreRetiredCatalog()
        assertEquals(14, db.characterDao().getAll().size)
        assertTrue(db.characterDao().getAll().map { it.id }.containsAll(oldIds))
        assertTrue(runCatching { upgrade.restoreRetiredCatalog() }.isFailure)
        assertEquals(14, db.characterDao().getAll().size)
    }

    @Test
    fun editedCatalogIsPreservedDuringUpgrade() = runBlocking {
        installLegacy()
        val character = db.characterDao().getAll().first()
        db.characterDao().upsert(character.copy(personaPrompt = "用户修改的人设"))
        BuiltinCatalogUpgrade(db, context, SecureStorage()).installCurrent(seedDataManager)
        assertEquals("用户修改的人设", db.characterDao().getById(character.id)?.personaPrompt)
        assertEquals(14, db.characterDao().getAll().size)
        assertEquals(2, db.encyclopediaDao().getAll().size)
    }

    @Test
    fun userCharacterWithSameNameIsNotRebound() = runBlocking {
        val id = db.characterDao().upsert(CharacterEntity(name = "沈照", personaPrompt = "用户角色"))
        seedDataManager.seedIfNeeded()
        assertEquals(0L, db.characterDao().getById(id)?.boundEncyclopediaId)
        assertEquals("用户角色", db.characterDao().getById(id)?.personaPrompt)
    }
}
