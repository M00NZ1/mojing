package com.mojing.app.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BranchContextExclusionDaoTest {
    private fun database() = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
    ).allowMainThreadQueries().build()

    @Test fun inheritedExclusionsFollowVisiblePrefixAndRemainBranchLocal() = runBlocking {
        val db = database()
        try {
            val session = db.sessionDao().insert(SessionEntity(title = "合成分支"))
            val dao = db.messageDao()
            val first = dao.insert(MessageEntity(sessionId = session, content = "排除的旧剧情"))
            val fork = dao.insert(MessageEntity(sessionId = session, content = "分叉点"))
            val later = dao.insert(MessageEntity(sessionId = session, content = "分叉后排除"))
            assertTrue(dao.setContextExcluded(session, "main", first, true))
            assertTrue(dao.setContextExcluded(session, "main", later, true))
            dao.insertContextExclusion(BranchContextExclusionEntity(session, "sibling", "m$fork"))
            db.sessionBranchDao().insert(SessionBranchEntity(sessionId = session, branchId = "B", sourceMessageId = fork))
            assertEquals(listOf(fork), dao.getVisibleContextTail(session, "B", 20).map { it.id })
            assertEquals(listOf("m$first"), dao.getExcludedContextKeys(session, "B", listOf("m$first", "m$fork", "m$later")))
            assertTrue(dao.setContextExcluded(session, "B", first, false))
            assertEquals(listOf("m$first"), dao.getExcludedContextKeys(session, "main", listOf("m$first")))
            val local = dao.insert(MessageEntity(sessionId = session, branchId = "B", content = "本地剧情"))
            db.sessionBranchDao().insert(SessionBranchEntity(sessionId = session, branchId = "C", parentBranchId = "B", sourceMessageId = local))
            assertTrue(dao.getVisibleContextTail(session, "C", 20).any { it.id == first })
            assertTrue(dao.setContextExcluded(session, "B", first, true))
            assertTrue(dao.getVisibleContextTail(session, "C", 20).any { it.id == first })
        } finally { db.close() }
    }

    @Test fun excludedEditedMessageMapsToReplacementAndCanBeRestoredLocally() = runBlocking {
        val db = database()
        try {
            val session = db.sessionDao().insert(SessionEntity(title = "合成编辑"))
            val dao = db.messageDao()
            for ((index, group) in listOf<String?>(null, "reply-group").withIndex()) {
                val original = MessageEntity(sessionId = session, speakerType = if (group == null) "user" else "character",
                    swipeGroupId = group, content = "原剧情$index")
                val id = dao.insert(original)
                assertTrue(dao.setContextExcluded(session, "main", id, true))
                val branch = "edit$index"
                val replacement = original.copy(branchId = branch, regeneratedFromMessageId = id,
                    includeInContext = group == null, content = "编辑剧情$index")
                val edited = db.sessionBranchDao().insertEditedBranch(
                    SessionBranchEntity(sessionId = session, branchId = branch, sourceMessageId = id), replacement, emptyList())
                assertFalse(dao.getVisibleContextTail(session, branch, 20).any { it.id == edited })
                assertNotNull(dao.getVisibleMessageById(session, branch, edited))
                assertTrue(dao.setContextExcluded(session, branch, edited, false))
                assertTrue(dao.getVisibleContextTail(session, branch, 20).any { it.id == edited })
                assertEquals(listOf(original.copy(id = id).contextSelectionKey()),
                    dao.getExcludedContextKeys(session, "main", listOf(original.copy(id = id).contextSelectionKey())))
            }
        } finally { db.close() }
    }

    @Test fun includedEditedMessageStaysIncludedAndEarlierExcludedTextStaysOut() = runBlocking {
        val db = database()
        try {
            val session = db.sessionDao().insert(SessionEntity(title = "合成正常编辑"))
            val dao = db.messageDao()
            val earlier = dao.insert(MessageEntity(sessionId = session, content = "此前排除"))
            assertTrue(dao.setContextExcluded(session, "main", earlier, true))
            val source = MessageEntity(sessionId = session, content = "参与的输入")
            val id = dao.insert(source)
            val edited = db.sessionBranchDao().insertEditedBranch(
                SessionBranchEntity(sessionId = session, branchId = "edit", sourceMessageId = id),
                source.copy(branchId = "edit", regeneratedFromMessageId = id, content = "修改后的参与输入"), emptyList())
            assertEquals(listOf(edited), dao.getVisibleContextTail(session, "edit", 20).map { it.id })
            assertEquals(listOf("m$earlier"), dao.getExcludedContextKeys(session, "edit", listOf("m$earlier", "m$edited")))
        } finally { db.close() }
    }
}
