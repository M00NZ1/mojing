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
            assertEquals(listOf(visibleId), events.getAllStatusOverrides(sessionId, "C").map { it.eventId })

            events.upsertStatusOverride(BranchEventStatusEntity(sessionId, "B", visibleId, false))
            assertEquals(false, events.getPageForBranch(sessionId, "B", limit = 10).single().resolved)
            assertEquals(true, events.getPageForBranch(sessionId, "C", limit = 10).single().resolved)
        } finally {
            db.close()
        }
    }
}
