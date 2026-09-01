package com.mojing.app.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.SessionContextMemoryEntity
import com.mojing.app.data.local.entity.SessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionContextMemoryDaoTest {
    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun upsertReplacesMemoryForSameSessionAndBranch() = runBlocking {
        val sessionId = db.sessionDao().insert(SessionEntity(title = "测试会话"))
        val dao = db.sessionContextMemoryDao()

        dao.upsert(
            SessionContextMemoryEntity(
                sessionId = sessionId,
                branchId = "main",
                globalSummary = "旧摘要",
                sourceStartMessageId = 1,
                sourceEndMessageId = 2,
            ),
        )
        dao.upsert(
            SessionContextMemoryEntity(
                sessionId = sessionId,
                branchId = "main",
                globalSummary = "新摘要",
                sourceStartMessageId = 1,
                sourceEndMessageId = 3,
            ),
        )

        val saved = dao.getBySessionAndBranch(sessionId, "main")
        assertEquals("新摘要", saved?.globalSummary)
        assertEquals(3L, saved?.sourceEndMessageId)
    }

    @Test
    fun clearAdvancesOnlyTheSelectedBranchRevisionAndRejectsAnOlderCommit() = runBlocking {
        val sessionId = db.sessionDao().insert(SessionEntity(title = "测试会话"))
        val dao = db.sessionContextMemoryDao()

        dao.upsert(
            SessionContextMemoryEntity(
                sessionId = sessionId,
                branchId = "main",
                globalSummary = "主线",
                revision = 2L,
            ),
        )
        dao.upsert(SessionContextMemoryEntity(sessionId = sessionId, branchId = "alt", globalSummary = "分支"))

        assertEquals(3L, dao.reserveNextRevision(sessionId, "main", now = 10L))
        val reserved = requireNotNull(dao.getBySessionAndBranch(sessionId, "main"))
        assertTrue(reserved.isValid)
        assertEquals("主线", reserved.globalSummary)

        dao.clearAndAdvanceRevision(sessionId, "main", updatedAt = 20L)

        val cleared = requireNotNull(dao.getBySessionAndBranch(sessionId, "main"))
        assertFalse(cleared.isValid)
        assertEquals("", cleared.globalSummary)
        assertEquals(4L, cleared.revision)
        assertEquals(20L, cleared.updatedAt)
        assertTrue(requireNotNull(dao.getBySessionAndBranch(sessionId, "alt")).isValid)

        assertFalse(
            dao.replaceIfRevisionMatches(
                SessionContextMemoryEntity(
                    sessionId = sessionId,
                    branchId = "main",
                    globalSummary = "过期结果",
                    isValid = true,
                    revision = 3L,
                ),
                expectedRevision = 3L,
            ),
        )
        assertTrue(
            dao.replaceIfRevisionMatches(
                SessionContextMemoryEntity(
                    sessionId = sessionId,
                    branchId = "main",
                    globalSummary = "清空后的新结果",
                    isValid = true,
                    revision = 4L,
                ),
                expectedRevision = 4L,
            ),
        )
        val rebuilt = requireNotNull(dao.getBySessionAndBranch(sessionId, "main"))
        assertEquals("清空后的新结果", rebuilt.globalSummary)
        assertTrue(rebuilt.isValid)
        assertEquals(4L, rebuilt.revision)
    }

}
