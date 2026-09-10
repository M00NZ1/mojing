package com.mojing.app.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.EncyclopediaEntryEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SedimentConfirmationDaoTest {
    @Test fun confirmationIsScopedIdempotentAndPreservesSourceAndText() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            db.encyclopediaDao().upsert(EncyclopediaEntity(id = 1, name = "当前世界"))
            db.encyclopediaDao().upsert(EncyclopediaEntity(id = 2, name = "另一世界"))
            val dao = db.encyclopediaEntryDao()
            val original = EncyclopediaEntryEntity(id = 1, encyclopediaId = 1, title = "约定", content = "保留原文", confidence = "inferred",
                sourceSessionId = 8, sourceMessageId = 9, metaJson = "{\"source_message_ids\":[9]}", updatedAt = 1)
            dao.upsert(original)
            val other = original.copy(id = 2, encyclopediaId = 2)
            val confirmed = original.copy(id = 3, confidence = "confirmed")
            val manual = original.copy(id = 4, confidence = "draft", sourceSessionId = null)
            listOf(other, confirmed, manual).forEach { dao.upsert(it) }
            assertEquals(1, dao.confirmSedimentEntries(1, listOf(1, 2, 3, 4, 999), 20))
            assertEquals(original.copy(confidence = "confirmed", updatedAt = 20), dao.getById(1))
            assertEquals(other, dao.getById(2))
            assertEquals(confirmed, dao.getById(3))
            assertEquals(manual, dao.getById(4))
            assertEquals(0, dao.confirmSedimentEntries(1, listOf(1), 30))
            assertEquals(20L, dao.getById(1)?.updatedAt)
        } finally { db.close() }
    }
}
