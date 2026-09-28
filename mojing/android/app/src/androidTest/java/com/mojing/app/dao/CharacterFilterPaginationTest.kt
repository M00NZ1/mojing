package com.mojing.app.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.EncyclopediaEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CharacterFilterPaginationTest {
    private lateinit var database: AppDatabase

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() = database.close()

    @Test fun filterPageHasStableCursorAndSearchesBeyondFirstPage() = runBlocking {
        val dao = database.encyclopediaDao()
        for (count in listOf(0, 1, 39, 40, 41)) {
            database.clearAllTables()
            repeat(count) { index -> dao.upsert(EncyclopediaEntity(name = "百科 $index")) }
            assertEquals(count, dao.getCharacterFilterPage("", null, null, null, null, 41).size)
        }
        database.clearAllTables()
        val expected = (0 until 85).map { index ->
            dao.upsert(EncyclopediaEntity(name = if (index == 84) "远处的星海" else "百科 $index",
                pinnedAt = if (index % 12 == 0) 9L else 0L, updatedAt = (index % 5).toLong()))
        }.toSet()
        val seen = mutableListOf<Long>()
        var cursor: com.mojing.app.data.local.dao.EncyclopediaFilterOption? = null
        do {
            val rows = dao.getCharacterFilterPage("", cursor?.pinnedAt?.let { if (it > 0) 1 else 0 },
                cursor?.pinnedAt, cursor?.updatedAt, cursor?.id, 41)
            val page = rows.take(40)
            seen += page.map { it.id }
            cursor = page.lastOrNull()
        } while (rows.size > 40)
        assertEquals(85, seen.size)
        assertEquals(expected, seen.toSet())
        assertEquals(listOf("远处的星海"), dao.getCharacterFilterPage("星海", null, null, null, null, 41).map { it.name })
    }

    @Test fun characterPageProjectsBoundWorldLabelWithoutLoadingWorldBody() = runBlocking {
        val encyclopediaId = database.encyclopediaDao().upsert(EncyclopediaEntity(
            name = "墨色世界", worldPrompt = "设定".repeat(1000),
        ))
        database.characterDao().upsert(CharacterEntity(name = "甲", boundEncyclopediaId = encyclopediaId))
        val page = database.characterDao().getLibraryRecommendedPage(null, null, null, null, null, null, 41)
        assertEquals("墨色世界", page.single().encyclopediaName)
        assertTrue(page.single().id > 0)
    }
}
