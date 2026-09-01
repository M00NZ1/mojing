package com.mojing.app.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.dao.GenerationTaskDao
import com.mojing.app.data.local.entity.GenerationTaskEntity
import com.mojing.app.data.local.entity.GenerationTaskKinds
import com.mojing.app.data.local.entity.GenerationTaskStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GenerationTaskDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var taskDao: GenerationTaskDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        taskDao = db.generationTaskDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun observeQueueVisibleIncludesCompletedAndFailed() = runBlocking {
        val t = System.currentTimeMillis()
        taskDao.insert(
            GenerationTaskEntity(
                taskKind = GenerationTaskKinds.ENCYCLOPEDIA_ENTRIES,
                title = "已完成",
                status = GenerationTaskStatus.COMPLETED,
                progressDone = 5,
                progressTotal = 5,
                payloadJson = "{}",
                createdAt = t,
                updatedAt = t,
            ),
        )
        taskDao.insert(
            GenerationTaskEntity(
                taskKind = GenerationTaskKinds.ENCYCLOPEDIA_ENTRIES,
                title = "失败",
                status = GenerationTaskStatus.FAILED,
                progressDone = 0,
                progressTotal = 5,
                payloadJson = "{}",
                errorMessage = "模型返回空",
                createdAt = t + 1,
                updatedAt = t + 1,
            ),
        )
        taskDao.insert(
            GenerationTaskEntity(
                taskKind = GenerationTaskKinds.ENCYCLOPEDIA_ENTRIES,
                title = "排队",
                status = GenerationTaskStatus.QUEUED,
                progressDone = 0,
                progressTotal = 3,
                payloadJson = "{}",
                createdAt = t + 2,
                updatedAt = t + 2,
            ),
        )
        val visible = taskDao.observeQueueVisible().first()
        assertEquals(3, visible.size)
        assertTrue(visible.any { it.status == GenerationTaskStatus.COMPLETED })
        assertTrue(visible.any { it.status == GenerationTaskStatus.FAILED })
        assertTrue(visible.any { it.status == GenerationTaskStatus.QUEUED })
    }

    @Test
    fun claimIfQueuedOnlyClaimsQueuedRow() = runBlocking {
        val t = System.currentTimeMillis()
        val id = taskDao.insert(
            GenerationTaskEntity(
                taskKind = GenerationTaskKinds.CHARACTER_PERSONA_AI,
                title = "人设",
                status = GenerationTaskStatus.QUEUED,
                progressDone = 0,
                progressTotal = 1,
                payloadJson = "{}",
                createdAt = t,
                updatedAt = t,
            ),
        )
        assertEquals(1, taskDao.claimIfQueued(id, t + 100))
        val running = taskDao.getById(id)
        assertEquals(GenerationTaskStatus.RUNNING, running?.status)
        assertEquals(0, taskDao.claimIfQueued(id, t + 200))
    }
}
