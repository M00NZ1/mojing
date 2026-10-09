package com.mojing.app.usecase

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.EncyclopediaEntryEntity
import com.mojing.app.domain.usecase.DeleteEncyclopediaEntryUseCase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeleteEncyclopediaEntryOwnerInstrumentedTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun deleteRejectsEntryFromAnotherEncyclopediaAndDeletesOwnedEntry() = runBlocking {
        val first = database.encyclopediaDao().upsert(EncyclopediaEntity(name = "第一世界"))
        val second = database.encyclopediaDao().upsert(EncyclopediaEntity(name = "第二世界"))
        val entry = database.encyclopediaEntryDao().upsert(
            EncyclopediaEntryEntity(encyclopediaId = first, title = "条目"),
        )
        val useCase = DeleteEncyclopediaEntryUseCase(database)

        assertFalse(useCase(entry, second))
        assertEquals(first, database.encyclopediaEntryDao().getById(entry)?.encyclopediaId)
        assertTrue(useCase(entry, first))
        assertEquals(null, database.encyclopediaEntryDao().getById(entry))
    }
}
