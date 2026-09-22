package com.mojing.app.usecase

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.LegacyWorldMappingEntity
import com.mojing.app.data.local.entity.WorldLoreEntryEntity
import com.mojing.app.data.local.entity.WorldTemplateEntity
import com.mojing.app.domain.usecase.DeleteWorldTemplateResult
import com.mojing.app.domain.usecase.DeleteWorldTemplateUseCase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeleteWorldTemplateUseCaseInstrumentedTest {
    private lateinit var database: AppDatabase
    private lateinit var delete: DeleteWorldTemplateUseCase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        delete = DeleteWorldTemplateUseCase(database)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun unlinkedTemplateDeletesLoreAndTemplateAtomically() = runBlocking {
        val id = insertTemplateWithLore("plain")

        assertEquals(DeleteWorldTemplateResult.Deleted, delete(id))
        assertNull(database.worldTemplateDao().getById(id))
        assertEquals(emptyList<WorldLoreEntryEntity>(), database.worldLoreEntryDao().getByTemplate(id))
    }

    @Test
    fun mappedTemplateIsProtectedWithoutDeletingAnything() = runBlocking {
        val id = insertTemplateWithLore("mapped")
        val encyclopediaId = database.encyclopediaDao().upsert(EncyclopediaEntity(name = "保留世界"))
        database.legacyWorldMappingDao().insert(LegacyWorldMappingEntity(id, encyclopediaId, "hash"))

        assertEquals(DeleteWorldTemplateResult.Protected, delete(id))
        assertNotNull(database.worldTemplateDao().getById(id))
        assertEquals(1, database.worldLoreEntryDao().getByTemplate(id).size)
    }

    @Test
    fun missingTemplateReturnsNotFound() = runBlocking {
        assertEquals(DeleteWorldTemplateResult.NotFound, delete(999_999L))
    }

    @Test
    fun templateDeleteFailureRollsBackLoreDelete() = runBlocking {
        val id = insertTemplateWithLore("fails")
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_template_delete BEFORE DELETE ON world_templates " +
                "BEGIN SELECT RAISE(ABORT, 'test failure'); END",
        )

        val result = delete(id)

        assertEquals(DeleteWorldTemplateResult.Failed::class, result::class)
        assertNotNull(database.worldTemplateDao().getById(id))
        assertEquals(1, database.worldLoreEntryDao().getByTemplate(id).size)
    }

    @Test
    fun repeatedDeleteReturnsNotFoundAfterFirstDelete() = runBlocking {
        val id = insertTemplateWithLore("repeat")

        assertEquals(DeleteWorldTemplateResult.Deleted, delete(id))
        assertEquals(DeleteWorldTemplateResult.NotFound, delete(id))
    }

    private suspend fun insertTemplateWithLore(templateId: String): Long {
        val id = database.worldTemplateDao().upsert(WorldTemplateEntity(templateId = templateId, label = templateId))
        database.worldLoreEntryDao().upsert(WorldLoreEntryEntity(worldTemplateId = id, title = "Lore"))
        return id
    }
}
