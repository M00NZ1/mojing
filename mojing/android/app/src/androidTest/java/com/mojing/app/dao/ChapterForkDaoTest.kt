package com.mojing.app.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.*
import com.mojing.app.domain.story.NovelChapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChapterForkDaoTest {
    @Test fun clonePreservesOriginalAttachmentsAndCutsFutureMemory() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            val s = db.sessionDao().insert(SessionEntity(title = "合成另线续写"))
            val seed = db.messageDao().insert(MessageEntity(sessionId = s, content = "过去"))
            val original = MessageEntity(sessionId = s, speakerType = "narrator", content = "第一章\n原文".repeat(1000),
                structuredContentJson = NovelChapter.draftMetadata("{\"custom\":\"保留\"}", 1, "第一章"))
            val source = db.messageDao().getById(db.messageDao().insert(original))!!
            val future = db.messageDao().insert(MessageEntity(sessionId = s, content = "FUTURE"))
            db.sessionContextMemoryDao().upsert(SessionContextMemoryEntity(sessionId = s, globalSummary = "FUTURE_UCM",
                sourceStartMessageId = seed, sourceEndMessageId = future))
            val segment = db.sessionMemorySegmentDao().insert(SessionMemorySegmentEntity(sessionId = s,
                startMessageId = seed, endMessageId = future, summary = "FUTURE_SEGMENT"))
            val attachment = MessageAttachmentEntity(messageId = source.id, storagePath = "synthetic/path", fileName = "synthetic")
            db.attachmentDao().insert(attachment)
            val oldAttachments = db.attachmentDao().getByMessage(source.id)
            val clone = source.copy(id = 0, branchId = "child", regeneratedFromMessageId = source.id, createdAt = 123)
            val id = db.sessionBranchDao().insertEditedBranch(SessionBranchEntity(sessionId = s, branchId = "child",
                parentBranchId = "main", sourceMessageId = source.id), clone, oldAttachments)
            assertEquals(source, db.messageDao().getById(source.id))
            val visible = db.messageDao().getVisibleMessagesTail(s, "child", 10).asReversed()
            assertEquals(listOf(seed, id), visible.map { it.id })
            assertEquals(clone.copy(id = id), visible.last())
            assertTrue(NovelChapter.canResumeTail("child", visible.last().branchId, visible.last().structuredContentJson))
            assertEquals(oldAttachments.map { it.copy(id = 0, messageId = id) },
                db.attachmentDao().getByMessage(id).map { it.copy(id = 0) })
            assertEquals(oldAttachments, db.attachmentDao().getByMessage(source.id))
            assertNull(db.sessionContextMemoryDao().getBySessionAndBranch(s, "child"))
            assertNull(db.sessionMemorySegmentDao().getVisibleById(s, "child", segment))
            assertEquals("FUTURE_UCM", db.sessionContextMemoryDao().getBySessionAndBranch(s, "main")!!.globalSummary)
        } finally { db.close() }
    }
}
