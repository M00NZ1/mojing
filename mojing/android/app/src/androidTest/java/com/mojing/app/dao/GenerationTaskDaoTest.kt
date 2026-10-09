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

    @Test fun historyCursorTraversesOldRecordsAndFiltersBeforeLimiting() = runBlocking {
        for (id in 1L..205L) taskDao.insert(GenerationTaskEntity(id = id,
            taskKind = GenerationTaskKinds.ENCYCLOPEDIA_ENTRIES, title = "$id", payloadJson = "{}",
            status = if (id % 3L == 0L) GenerationTaskStatus.FAILED else GenerationTaskStatus.COMPLETED))
        for (filter in listOf(0, 2)) {
            var cursor = Long.MAX_VALUE
            val seen = mutableListOf<Long>()
            do {
                val page = taskDao.observeHistoryPage(cursor, filter).first()
                assertTrue(page.size <= 51)
                seen.addAll(page.take(50).map { it.id })
                if (page.size <= 50) break
                cursor = page[49].id
            } while (true)
            assertEquals((205L downTo 1L).filter { filter == 0 || it % 3L == 0L }, seen)
        }
        assertTrue(taskDao.observeHistoryPage(Long.MAX_VALUE, 1).first().isEmpty())
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
    fun filteredHistoryMatchesLiteralSymbolsCaseInsensitivelyAndAcrossOldRows() = runBlocking {
        for (id in 1L..205L) taskDao.insert(GenerationTaskEntity(
            id = id,
            taskKind = if (id == 1L) GenerationTaskKinds.CHARACTER_PERSONA_AI else GenerationTaskKinds.ENCYCLOPEDIA_ENTRIES,
            title = if (id == 1L) "Alpha[符号]" else "普通记录 $id",
            payloadJson = "{}",
            status = if (id == 1L) GenerationTaskStatus.FAILED else GenerationTaskStatus.COMPLETED,
        ))

        val rows = taskDao.observeHistoryPageFiltered(
            beforeId = Long.MAX_VALUE,
            filter = 2,
            keyword = "alpha[符号]",
            taskKind = GenerationTaskKinds.CHARACTER_PERSONA_AI,
        ).first()
        assertEquals(listOf(1L), rows.map { it.id })
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

    @Test
    fun resultSnapshotIsSavedOnceAndAppliedMarkIsIdempotent() = runBlocking {
        val id = taskDao.insert(GenerationTaskEntity(
            taskKind = GenerationTaskKinds.CHARACTER_PERSONA_AI,
            title = "快照",
            status = GenerationTaskStatus.RUNNING,
            payloadJson = "{}",
            targetCharacterId = 7L,
        ))
        assertEquals(1, taskDao.saveResultAndTerminal(id, "{\"schemaVersion\":1}", GenerationTaskStatus.COMPLETED, "", 10L))
        assertEquals(0, taskDao.saveResultAndTerminal(id, "replacement", GenerationTaskStatus.COMPLETED, "", 10L))
        assertEquals("{\"schemaVersion\":1}", taskDao.getById(id)?.resultJson)
        assertEquals(1, taskDao.markResultApplied(id, 11L, 11L))
        assertEquals(0, taskDao.markResultApplied(id, 12L, 12L))
        assertEquals(11L, taskDao.getById(id)?.resultAppliedAt)
    }

    @Test
    fun lateResultCannotTurnCancelledTaskCompleted() = runBlocking {
        val id = taskDao.insert(GenerationTaskEntity(
            taskKind = GenerationTaskKinds.CHARACTER_PERSONA_AI,
            title = "取消后迟到结果",
            status = GenerationTaskStatus.CANCELLED,
            payloadJson = "{}",
        ))
        assertEquals(0, taskDao.saveResultAndTerminal(id, "snapshot", GenerationTaskStatus.COMPLETED, "", 20L))
        assertEquals(GenerationTaskStatus.CANCELLED, taskDao.getById(id)?.status)
        assertEquals("", taskDao.getById(id)?.resultJson)
    }
}
