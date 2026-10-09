package com.mojing.app.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionBranchEntity
import com.mojing.app.data.local.entity.SessionEntity
import com.mojing.app.domain.story.NovelChapter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class StoryChapterTailDaoTest {
    private lateinit var db: AppDatabase
    @Before fun open() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build() }
    @After fun close() { db.close() }

    @Test fun actualTailIncludesNonChapterAndRetainsInheritedSourceWithoutFutureRows() = runBlocking {
        val sid = db.sessionDao().insert(SessionEntity(title = "尾章隔离"))
        val dao = db.messageDao()
        assertNull(dao.getStoryChapterTail(sid, "main"))
        val json = NovelChapter.draftMetadata("{}", 1, "北塔")
        val anchor = dao.insert(MessageEntity(sessionId = sid, speakerType = "narrator", content = "字".repeat(200000), structuredContentJson = json))
        assertEquals(anchor, dao.getStoryChapterTail(sid, "main")!!.id)
        assertTrue(NovelChapter.canResumeTail("main", "main", dao.getStoryChapterTail(sid, "main")!!.structuredContentJson))
        val future = dao.insert(MessageEntity(sessionId = sid, content = "之后的消息"))
        assertEquals(future, dao.getStoryChapterTail(sid, "main")!!.id)
        db.sessionBranchDao().insert(SessionBranchEntity(sessionId = sid, branchId = "B", sourceMessageId = anchor))
        val inherited = dao.getStoryChapterTail(sid, "B")!!
        assertEquals(anchor, inherited.id)
        assertEquals("main", inherited.branchId)
        assertEquals(json, inherited.structuredContentJson)
        assertFalse(NovelChapter.canResumeTail("B", inherited.branchId, inherited.structuredContentJson))
        val own = dao.insert(MessageEntity(sessionId = sid, branchId = "B", speakerType = "narrator", content = "子线草稿", structuredContentJson = json))
        val tail = dao.getStoryChapterTail(sid, "B")!!
        assertEquals(own, tail.id)
        assertTrue(NovelChapter.canResumeTail("B", tail.branchId, tail.structuredContentJson))
        db.sessionDao().insert(SessionEntity(title = "另一会话")).let {
            dao.insert(MessageEntity(sessionId = it, content = "异会话"))
        }
        assertEquals(own, dao.getStoryChapterTail(sid, "B")!!.id)
        assertEquals(dao.getVisibleMessagesTail(sid, "B", 1).single().id, tail.id)
        assertEquals(dao.getMainMessagesTail(sid, 1).single().id, dao.getStoryChapterTail(sid, "main")!!.id)
    }
}
