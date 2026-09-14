package com.mojing.app.usecase

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.*
import com.mojing.app.domain.story.NovelChapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NovelDraftInstrumentedTest {
    @Test fun checkpointCompletionIsAtomicIndexedAndSessionIsolated() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            val session = db.sessionDao().insert(SessionEntity(title = "小说"))
            val other = db.sessionDao().insert(SessionEntity(title = "其他小说"))
            val dao = db.messageDao()
            val id = dao.insert(MessageEntity(sessionId = session, speakerType = "narrator", content = "草稿",
                structuredContentJson = NovelChapter.draftMetadata("{}", 2, "第二章")))
            assertEquals(2, dao.getMainMaxChapter(session))
            assertEquals(0, dao.getMainMaxChapter(other))
            assertTrue(runCatching { dao.updateNovelDraft(id, other, "main", "错误覆盖", "{}") }.isFailure)
            assertEquals("草稿", dao.getById(id)!!.content)
            dao.updateNovelDraft(id, session, "main", "夜雨落下", NovelChapter.metadata("{}", 2, "第二章"))
            assertFalse(NovelChapter.incomplete(dao.getById(id)!!.structuredContentJson))
            assertEquals(1, dao.countMainMessages(session, "夜雨", 0))
            assertTrue(runCatching { dao.updateNovelDraft(id, session, "main", "过期草稿", "{}") }.isFailure)
            assertEquals("夜雨落下", dao.getById(id)!!.content)
            val laterId = dao.insert(MessageEntity(sessionId = session, speakerType = "narrator", content = "后续正文"))
            dao.renameNovelChapter(id, session, "渡口")
            assertEquals("渡口", NovelChapter.title(dao.getById(id)!!.structuredContentJson))
            assertTrue(dao.getById(id)!!.content.contains("夜雨落下"))
            assertEquals("后续正文", dao.getById(laterId)!!.content)
            assertEquals(1, dao.countMainMessages(session, "渡口", 0))
            val fragmentId = dao.insert(MessageEntity(sessionId = session, speakerType = "narrator", content = "<NARRATION>船靠岸了</NARRATION>"))
            dao.renameNovelChapter(fragmentId, session, "新标题")
            assertEquals("船靠岸了", NovelChapter.body(dao.getById(fragmentId)!!))
        } finally { db.close() }
    }
}
