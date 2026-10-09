package com.mojing.app.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionBranchEntity
import com.mojing.app.data.local.entity.SessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StoryContentsSearchDaoTest {
    private lateinit var db: AppDatabase
    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
    }
    @After fun close() { db.close() }

    @Test fun findsOldChaptersByTitleNumberOrOpeningAndTreatsPunctuationLiterally() = runBlocking {
        val session = db.sessionDao().insert(SessionEntity(title = "目录搜索"))
        val dao = db.messageDao()
        val old = dao.insert(MessageEntity(sessionId = session, speakerType = "narrator", content = "钟楼的门开了",
            structuredContentJson = """{"chapter_title":"Night 100%_雾港","chapter_number":12}"""))
        repeat(50) { dao.insert(MessageEntity(sessionId = session, speakerType = "narrator", content = "普通章节")) }
        val malformed = dao.insert(MessageEntity(sessionId = session, speakerType = "narrator", content = "旧稿：100%_", structuredContentJson = "bad json"))
        dao.insert(MessageEntity(sessionId = session, speakerType = "narrator", content = "字".repeat(181) + "只在正文深处"))
        assertFalse(dao.getMainStoryContentsBefore(session, Long.MAX_VALUE, 40).any { it.id == old })
        assertEquals(listOf(old), dao.searchMainStoryContentsBefore(session, Long.MAX_VALUE, 41, "night", null).map { it.id })
        assertEquals(listOf(old), dao.searchMainStoryContentsBefore(session, Long.MAX_VALUE, 41, "钟楼", null).map { it.id })
        assertEquals(listOf(old), dao.searchMainStoryContentsBefore(session, Long.MAX_VALUE, 41, "第十二章", 12).map { it.id })
        assertEquals(listOf(malformed, old), dao.searchMainStoryContentsBefore(session, Long.MAX_VALUE, 41, "%_", null).map { it.id })
        assertTrue(dao.searchMainStoryContentsBefore(session, Long.MAX_VALUE, 41, "只在正文深处", null).isEmpty())
        assertTrue(dao.searchMainStoryContentsBefore(session, Long.MAX_VALUE, 41, "' OR 1=1", null).isEmpty())
    }

    @Test fun branchSearchOnlyReturnsVisibleAdoptedVersionsFromThisSession() = runBlocking {
        val session = db.sessionDao().insert(SessionEntity(title = "分支范围"))
        val other = db.sessionDao().insert(SessionEntity(title = "另一会话"))
        val dao = db.messageDao()
        val anchor = dao.insert(MessageEntity(sessionId = session, speakerType = "narrator", content = "雾港起点"))
        dao.insert(MessageEntity(sessionId = session, speakerType = "narrator", content = "雾港主线分叉后"))
        db.sessionBranchDao().insert(SessionBranchEntity(sessionId = session, branchId = "B", sourceMessageId = anchor))
        dao.insert(MessageEntity(sessionId = session, branchId = "B", speakerType = "narrator", content = "雾港旧版",
            swipeGroupId = "chapter", includeInContext = false))
        val selected = dao.insert(MessageEntity(sessionId = session, branchId = "B", speakerType = "narrator", content = "雾港采用版", swipeGroupId = "chapter"))
        dao.insert(MessageEntity(sessionId = session, branchId = "B", speakerType = "user", content = "雾港用户输入"))
        dao.insert(MessageEntity(sessionId = other, speakerType = "narrator", content = "雾港其他会话"))
        assertEquals(listOf(selected, anchor), dao.searchBranchStoryContentsBefore(session, "B", Long.MAX_VALUE, 41, "雾港", null).map { it.id })
    }

    @Test fun matchingPagesUseMessageIdCursorWithoutDuplicatesOrOmissions() = runBlocking {
        val session = db.sessionDao().insert(SessionEntity(title = "目录分页"))
        val dao = db.messageDao()
        val expected = (1..15).map { index ->
            dao.insert(MessageEntity(sessionId = session, speakerType = "narrator", content = if (index % 2 == 0) "远行" else "风雪"))
        }.filterIndexed { index, _ -> (index + 1) % 2 != 0 }.reversed()
        val found = mutableListOf<Long>()
        var before = Long.MAX_VALUE
        do {
            val rows = dao.searchMainStoryContentsBefore(session, before, 4, "风雪", null)
            val page = rows.take(3)
            found += page.map { it.id }
            if (rows.size <= 3) break
            before = page.last().id
        } while (true)
        assertEquals(expected, found)
    }
}
