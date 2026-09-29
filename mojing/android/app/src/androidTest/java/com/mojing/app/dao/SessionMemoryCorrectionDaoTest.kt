package com.mojing.app.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionEntity
import com.mojing.app.data.local.entity.SessionMemoryCorrectionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionMemoryCorrectionDaoTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun scopeCrudAndSourceDeletionAreIndependent() = runBlocking {
        db.sessionDao().insert(SessionEntity(id = 1L, title = "测试"))
        val globalId = db.sessionMemoryCorrectionDao().insert(
            SessionMemoryCorrectionEntity(sessionId = 1L, content = "全会话", sourceMessageId = 10L, createdAt = 1L, updatedAt = 1L),
        )
        val branchId = db.sessionMemoryCorrectionDao().insert(
            SessionMemoryCorrectionEntity(sessionId = 1L, branchId = "branch-a", content = "精确分支", createdAt = 2L, updatedAt = 2L),
        )
        db.sessionMemoryCorrectionDao().insert(
            SessionMemoryCorrectionEntity(sessionId = 1L, branchId = "branch-b", content = "兄弟分支", createdAt = 3L, updatedAt = 3L),
        )

        assertEquals(listOf("全会话"), db.sessionMemoryCorrectionDao().getVisible(1L, "main").map { it.content })
        assertEquals(listOf("全会话", "精确分支"), db.sessionMemoryCorrectionDao().getVisible(1L, "branch-a").map { it.content })
        assertEquals(listOf("全会话", "兄弟分支"), db.sessionMemoryCorrectionDao().getVisible(1L, "branch-b").map { it.content })

        val loaded = db.sessionMemoryCorrectionDao().getById(1L, globalId)!!
        db.sessionMemoryCorrectionDao().update(loaded.copy(content = "已更新", updatedAt = 4L))
        assertEquals("已更新", db.sessionMemoryCorrectionDao().getById(1L, globalId)!!.content)
        assertEquals(1, db.sessionMemoryCorrectionDao().deleteById(1L, branchId))

        db.messageDao().insert(MessageEntity(id = 10L, sessionId = 1L, content = "来源"))
        assertTrue(db.messageDao().deleteRaw(10L) == 1)
        assertEquals("已更新", db.sessionMemoryCorrectionDao().getById(1L, globalId)!!.content)
    }

    @Test
    fun visiblePagesUseCreatedTimeAndIdCursorWithinTheSelectedBranch() = runBlocking {
        db.sessionDao().insert(SessionEntity(id = 1L, title = "当前会话"))
        db.sessionDao().insert(SessionEntity(id = 2L, title = "其他会话"))
        val dao = db.sessionMemoryCorrectionDao()
        repeat(18) { index ->
            dao.insert(SessionMemoryCorrectionEntity(
                sessionId = 1L,
                branchId = if (index % 2 == 0) null else "branch-a",
                content = "纠正 $index",
                createdAt = 100L + index / 2,
            ))
        }
        dao.insert(SessionMemoryCorrectionEntity(sessionId = 1L, branchId = "branch-b", content = "其他分支", createdAt = 999L))
        dao.insert(SessionMemoryCorrectionEntity(sessionId = 2L, content = "其他会话", createdAt = 999L))

        val expected = dao.getVisible(1L, "branch-a")
            .sortedWith(compareByDescending<SessionMemoryCorrectionEntity> { it.createdAt }.thenByDescending { it.id })
        val first = dao.getVisibleFirstPage(1L, "branch-a", 17)
        val tail = first[15]
        val second = dao.getVisibleBefore(1L, "branch-a", tail.createdAt, tail.id, 17)

        assertEquals(18, expected.size)
        assertEquals(expected.map { it.id }, (first.take(16) + second).map { it.id })
        assertEquals(listOf("纠正 0", "纠正 2", "纠正 4", "纠正 6", "纠正 8", "纠正 10", "纠正 12", "纠正 14", "纠正 16"),
            dao.getVisibleFirstPage(1L, "main", 17).asReversed().map { it.content })
    }
}
