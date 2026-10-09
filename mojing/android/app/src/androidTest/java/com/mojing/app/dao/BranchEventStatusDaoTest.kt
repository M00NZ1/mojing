package com.mojing.app.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.BranchEventStatusEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionBranchEntity
import com.mojing.app.data.local.entity.SessionEntity
import com.mojing.app.data.local.entity.SessionEventNodeEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BranchEventStatusDaoTest {
    @Test
    fun inheritedEventStatusIsLocalAndVisibleOverridesFollowNewChildBranch() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val sessionId = db.sessionDao().insert(SessionEntity(title = "分支事件"))
            val messages = db.messageDao()
            val events = db.sessionEventNodeDao()
            val branches = db.sessionBranchDao()
            val first = messages.insert(MessageEntity(sessionId = sessionId, content = "起点"))
            val second = messages.insert(MessageEntity(sessionId = sessionId, content = "分叉点"))
            val visibleId = events.insert(SessionEventNodeEntity(sessionId = sessionId, messageId = first, title = "继承事件"))
            val hiddenId = events.insert(SessionEventNodeEntity(sessionId = sessionId, messageId = second, title = "分叉后事件"))

            branches.insert(SessionBranchEntity(sessionId = sessionId, branchId = "B", sourceMessageId = first))
            events.upsertStatusOverride(BranchEventStatusEntity(sessionId, "B", visibleId, true))
            events.upsertStatusOverride(BranchEventStatusEntity(sessionId, "B", hiddenId, true))
            val branchMessage = messages.insert(MessageEntity(sessionId = sessionId, branchId = "B", content = "分支正文"))
            branches.insert(SessionBranchEntity(sessionId = sessionId, branchId = "C", parentBranchId = "B", sourceMessageId = branchMessage))

            assertEquals(false, events.getPageForBranch(sessionId, "main", limit = 10).first { it.id == visibleId }.resolved)
            assertEquals(true, events.getPageForBranch(sessionId, "B", limit = 10).single().resolved)
            assertEquals(true, events.getPageForBranch(sessionId, "C", limit = 10).single().resolved)
            assertEquals(listOf(visibleId), events.getFilteredPageForBranch(
                sessionId, "B", resolved = true, limit = 10,
            ).map { it.id })
            assertEquals(listOf(visibleId), events.getAllStatusOverrides(sessionId, "C").map { it.eventId })

            events.upsertStatusOverride(BranchEventStatusEntity(sessionId, "B", visibleId, false))
            assertEquals(false, events.getPageForBranch(sessionId, "B", limit = 10).single().resolved)
            assertEquals(true, events.getPageForBranch(sessionId, "C", limit = 10).single().resolved)
            assertEquals(emptyList<Long>(), events.getFilteredPageForBranch(
                sessionId, "B", resolved = true, limit = 10,
            ).map { it.id })
        } finally {
            db.close()
        }
    }

    @Test
    fun filteredMainEventsUseLiteralSearchAndStableKeysetPages() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val sessionId = db.sessionDao().insert(SessionEntity(title = "筛选会话"))
            val otherSessionId = db.sessionDao().insert(SessionEntity(title = "其他会话"))
            val events = db.sessionEventNodeDao()
            repeat(25) { index ->
                events.insert(SessionEventNodeEntity(
                    sessionId = sessionId,
                    title = if (index == 24) "literal_%_target" else "事件 $index",
                    description = "描述 $index",
                    createdAt = 100L,
                ))
            }
            events.insert(SessionEventNodeEntity(sessionId = otherSessionId, title = "事件 999", createdAt = 100L))
            val first = events.getFilteredPageForBranch(sessionId, "main", limit = 24)
            assertEquals(24, first.size)
            val second = events.getFilteredPageForBranch(
                sessionId, "main", beforeCreatedAt = first.last().createdAt,
                beforeId = first.last().id, limit = 24,
            )
            assertEquals(1, second.size)
            assertEquals(25, (first.map { it.id } + second.map { it.id }).distinct().size)
            assertEquals(listOf("literal_%_target"), events.getFilteredPageForBranch(
                sessionId, "main", query = "%_", limit = 10,
            ).map { it.title })
            val resolvedId = first.first().id
            events.setResolved(resolvedId, true)
            assertEquals(listOf(resolvedId), events.getFilteredPageForBranch(
                sessionId, "main", resolved = true, limit = 10,
            ).map { it.id })
            events.upsertStatusOverride(BranchEventStatusEntity(sessionId, "main", first.last().id, true))
            assertEquals(listOf(resolvedId), events.getFilteredPageForBranch(
                sessionId, "main", resolved = true, limit = 10,
            ).map { it.id })
        } finally {
            db.close()
        }
    }

    @Test
    fun filteredBranchEventsUseVisibleSourcesOverridesAndBoundedCursors() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val sessionId = db.sessionDao().insert(SessionEntity(title = "可见故事线"))
            val otherSessionId = db.sessionDao().insert(SessionEntity(title = "另一会话"))
            val messages = db.messageDao()
            val events = db.sessionEventNodeDao()
            val branches = db.sessionBranchDao()
            val first = messages.insert(MessageEntity(sessionId = sessionId, content = "起点"))
            val replaced = messages.insert(MessageEntity(sessionId = sessionId, content = "将被替换"))
            val inherited = events.insert(SessionEventNodeEntity(
                sessionId = sessionId, messageId = first, title = "Alpha_%", description = "继承描述",
                createdAt = 100L,
            ))
            events.insert(SessionEventNodeEntity(
                sessionId = sessionId, messageId = replaced, title = "旧来源", createdAt = 200L,
            ))
            events.insert(SessionEventNodeEntity(sessionId = otherSessionId, title = "Alpha_%", createdAt = 300L))

            branches.insert(SessionBranchEntity(sessionId = sessionId, branchId = "B", sourceMessageId = replaced))
            val replacement = messages.insert(MessageEntity(
                sessionId = sessionId, branchId = "B", regeneratedFromMessageId = replaced,
                content = "分支替换", createdAt = 250L,
            ))
            val local = events.insert(SessionEventNodeEntity(
                sessionId = sessionId, branchId = "B", messageId = replacement,
                title = "替换后事件", description = "中文描述", createdAt = 200L,
            ))
            events.upsertStatusOverride(BranchEventStatusEntity(sessionId, "B", inherited, true))

            assertTrue(events.getFilteredPageForBranch(sessionId, "B", limit = 0).isEmpty())
            assertEquals(listOf(inherited), events.getFilteredPageForBranch(
                sessionId, "B", resolved = true, limit = 10,
            ).map { it.id })
            assertEquals(listOf(inherited), events.getFilteredPageForBranch(
                sessionId, "B", query = "_%", limit = 10,
            ).map { it.id })
            assertEquals(listOf(inherited), events.getFilteredPageForBranch(
                sessionId, "B", query = "alpha", limit = 10,
            ).map { it.id })
            assertEquals(listOf(local), events.getFilteredPageForBranch(
                sessionId, "B", query = "中文", limit = 10,
            ).map { it.id })
            assertTrue(events.getFilteredPageForBranch(
                sessionId, "B", query = "旧来源", limit = 10,
            ).isEmpty())
            assertTrue(events.getFilteredPageForBranch(
                sessionId, "B", query = "Alpha_%", limit = 10,
            ).all { it.sessionId == sessionId })

            val firstPage = events.getFilteredPageForBranch(sessionId, "B", limit = 1)
            val secondPage = events.getFilteredPageForBranch(
                sessionId, "B", beforeCreatedAt = firstPage.single().createdAt,
                beforeId = firstPage.single().id, limit = 25,
            )
            assertEquals(listOf(local, inherited), firstPage.map { it.id } + secondPage.map { it.id })
            assertTrue(runCatching {
                events.getFilteredPageForBranch(sessionId, "B", limit = -1)
            }.exceptionOrNull() is IllegalArgumentException)
        } finally {
            db.close()
        }
    }
}
