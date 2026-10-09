package com.mojing.app.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.SessionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionPinDaoTest {
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
    fun pinDoesNotOverwriteNewerStorySettingsAndPinnedFilterTracksUnpin() = runBlocking {
        val dao = database.sessionDao()
        val id = dao.insert(SessionEntity(title = "旧名称", updatedAt = 1L))
        dao.updateTitle(id, "生成后名称", 20L)
        dao.updateSummary(id, "最新剧情摘要", 30L)
        dao.updateThinkMax(id, true, 40L)
        val beforePin = dao.getById(id)!!
        assertEquals(1, dao.updatePinnedAt(id, 100L))
        assertEquals(beforePin.copy(pinnedAt = 100L), dao.getById(id))
        assertEquals(listOf(id), dao.observeListPageWithMeta("", null, null, null, 41, pinnedOnly = true)
            .first().map { it.session.id })
        assertEquals(1, dao.updatePinnedAt(id, 0L))
        assertEquals(beforePin, dao.getById(id))
        assertEquals(emptyList<Long>(), dao.observeListPageWithMeta("", null, null, null, 41, pinnedOnly = true)
            .first().map { it.session.id })
        assertEquals(0, dao.updatePinnedAt(id + 1000L, 100L))
    }
}
