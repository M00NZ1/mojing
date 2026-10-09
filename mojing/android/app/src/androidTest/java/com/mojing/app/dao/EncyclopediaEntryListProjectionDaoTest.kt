package com.mojing.app.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.dao.EncyclopediaEntryListItem
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.EncyclopediaEntryEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class EncyclopediaEntryListProjectionDaoTest {
    @Test fun longTextStaysInFullEntryWhileListPagesFilterAndPreservePresentation() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            db.encyclopediaDao().upsert(EncyclopediaEntity(id = 1, name = "投影世界"))
            db.encyclopediaDao().upsert(EncyclopediaEntity(id = 2, name = "另一世界"))
            val dao = db.encyclopediaEntryDao()
            val original = EncyclopediaEntryEntity(id = 1, encyclopediaId = 1, title = "长文条目", entryType = "location",
                summary = "列表摘要", content = "完整正文".repeat(10000), metaJson = "{\"note\":\"${"扩展".repeat(10000)}\"}",
                isFeatured = true, coverImagePath = "fixture-cover.png", updatedAt = 42)
            dao.upsert(original)
            (2L..105L).forEach { dao.upsert(original.copy(id = it, title = "条目$it", entryType = if (it % 2L == 0L) "character" else "location", content = "原文$it", metaJson = "{}")) }
            dao.upsert(original.copy(id = 200, encyclopediaId = 2))
            assertEquals(EncyclopediaEntryListItem(1, 1, original.title, "location", original.summary, true, original.coverImagePath, 42), dao.getEntryListPage(1, 0, "").first())
            assertEquals(101, dao.getEntryListPage(1, 0, "").size)
            for (type in listOf("", "全部", "character", "location")) {
                val seen = mutableListOf<Long>(); var cursor = 0L
                do {
                    val rows = dao.getEntryListPage(1, cursor, type)
                    seen += rows.take(100).map { it.id }
                    if (rows.size <= 100) break
                    cursor = seen.last()
                } while (true)
                assertEquals((1L..105L).filter { type.isBlank() || type == "全部" || (type == "character") == (it % 2L == 0L) }, seen)
            }
            assertEquals(original, dao.getById(1))
            assertEquals(emptyList<EncyclopediaEntryListItem>(), dao.getEntryListPage(1, 105, ""))
        } finally { db.close() }
    }
}
