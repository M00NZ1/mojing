package com.mojing.app.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.dao.CharacterListItem
import com.mojing.app.data.local.entity.CharacterEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CharacterLibraryPaginationTest {
    private lateinit var database: AppDatabase

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() = database.close()

    @Test fun pageBoundariesAndProjection() = runBlocking {
        val dao = database.characterDao()
        for (count in listOf(0, 1, 39, 40, 41)) {
            database.clearAllTables()
            repeat(count) { index ->
                dao.upsert(CharacterEntity(name = "角色$index", personaPrompt = "人".repeat(1000), apiKey = "private"))
            }
            val rows = dao.getLibraryRecommendedPage(null, null, null, null, null, null, 41)
            assertEquals(count, rows.size)
            assertTrue(rows.all { it.personaPreview.length == 72 })
            assertFalse(CharacterListItem::class.java.declaredFields.any { it.name == "apiKey" || it.name == "personaPrompt" })
        }
    }

    @Test fun allSortsTraverseWithoutDuplicateOrMissingRowsAndRespectFilter() = runBlocking {
        val dao = database.characterDao()
        val expected = (0 until 85).map { index ->
            dao.upsert(CharacterEntity(
                name = "Name ${index % 11}",
                createdAt = (index % 7).toLong(),
                pinnedAt = if (index % 13 == 0) 5L else 0L,
                favorite = index % 3 == 0,
                boundEncyclopediaId = if (index % 2 == 0) 7L else 8L,
            ))
        }.toSet()
        val recommended = traverse { cursor -> dao.getLibraryRecommendedPage(
            null, cursor?.pinnedAt?.let { if (it > 0) 1 else 0 }, cursor?.pinnedAt,
            cursor?.favorite, cursor?.createdAt, cursor?.id, 41,
        ) }
        val recent = traverse { cursor -> dao.getLibraryRecentPage(
            null, cursor?.pinnedAt?.let { if (it > 0) 1 else 0 }, cursor?.createdAt, cursor?.id, 41,
        ) }
        val name = traverse { cursor -> dao.getLibraryNamePage(
            null, cursor?.pinnedAt?.let { if (it > 0) 1 else 0 }, cursor?.name, cursor?.id, 41,
        ) }
        for (ids in listOf(recommended, recent, name)) {
            assertEquals(85, ids.size)
            assertEquals(expected, ids.toSet())
        }
        val scoped = traverse { cursor -> dao.getLibraryRecommendedPage(
            7L, cursor?.pinnedAt?.let { if (it > 0) 1 else 0 }, cursor?.pinnedAt,
            cursor?.favorite, cursor?.createdAt, cursor?.id, 41,
        ) }
        assertEquals(43, scoped.size)
        assertTrue(scoped.all { it in expected })
    }

    private suspend fun traverse(fetch: suspend (CharacterListItem?) -> List<CharacterListItem>): List<Long> {
        val ids = mutableListOf<Long>()
        var cursor: CharacterListItem? = null
        do {
            val rows = fetch(cursor)
            val page = rows.take(40)
            ids += page.map { it.id }
            cursor = page.lastOrNull()
        } while (rows.size > 40)
        return ids
    }
}
