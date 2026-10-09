package com.mojing.app.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.EncyclopediaEntryEntity
import com.mojing.app.data.local.entity.TimelineEventEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimelineEventEditDaoTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun updateStandaloneRequiresWorldAndKeepsCreatedAt() = runBlocking {
        db.encyclopediaDao().upsert(EncyclopediaEntity(id = 3L, name = "世界三"))
        db.encyclopediaDao().upsert(EncyclopediaEntity(id = 4L, name = "世界四"))
        val createdAt = 1234L
        db.timelineEventDao().upsert(
            TimelineEventEntity(
                id = 7L,
                encyclopediaId = 3L,
                title = "旧标题",
                description = "旧描述",
                eventTime = "旧时间",
                sortOrder = 1,
                createdAt = createdAt,
            ),
        )

        assertEquals(0, db.timelineEventDao().updateStandalone(7L, 4L, "越界", "越界", "越界", 9))
        assertEquals(1, db.timelineEventDao().updateStandalone(7L, 3L, "新标题", "新描述", "新时间", -2))

        val saved = db.timelineEventDao().getById(7L)
        assertNotNull(saved)
        assertEquals(3L, saved?.encyclopediaId)
        assertEquals("新标题", saved?.title)
        assertEquals("新描述", saved?.description)
        assertEquals(-2, saved?.sortOrder)
        assertEquals(createdAt, saved?.createdAt)
    }

    @Test
    fun updateStandaloneRejectsLinkedAndDeletedEvents() = runBlocking {
        db.encyclopediaDao().upsert(EncyclopediaEntity(id = 3L, name = "世界"))
        db.encyclopediaEntryDao().upsert(EncyclopediaEntryEntity(id = 8L, encyclopediaId = 3L, title = "条目"))
        db.timelineEventDao().upsert(
            TimelineEventEntity(id = 9L, encyclopediaId = 3L, entryId = 8L, title = "关联事件"),
        )
        assertEquals(0, db.timelineEventDao().updateStandalone(9L, 3L, "不应改", "", "", 0))
        assertEquals("关联事件", db.timelineEventDao().getById(9L)?.title)

        db.timelineEventDao().upsert(TimelineEventEntity(id = 10L, encyclopediaId = 3L, title = "待删"))
        db.timelineEventDao().delete(10L)
        assertEquals(0, db.timelineEventDao().updateStandalone(10L, 3L, "不应复活", "", "", 0))
        assertNull(db.timelineEventDao().getById(10L))
    }
}
