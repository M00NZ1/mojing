package com.mojing.app.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.MessageBookmarkEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookmarkNoteDaoTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun updateNoteRequiresSessionAndPreservesBookmarkIdentity() = runBlocking {
        val sessionDao = database.sessionDao()
        val messageDao = database.messageDao()
        val bookmarkDao = database.bookmarkDao()
        val firstSessionId = sessionDao.insert(SessionEntity())
        val secondSessionId = sessionDao.insert(SessionEntity())
        val messageId = messageDao.insert(MessageEntity(sessionId = firstSessionId, content = "原文"))
        val bookmarkId = bookmarkDao.insert(
            MessageBookmarkEntity(
                sessionId = firstSessionId,
                messageId = messageId,
                note = "旧备注",
                createdAt = 1234L,
            ),
        )

        assertEquals(0, bookmarkDao.updateNote(secondSessionId, bookmarkId, "越权备注"))
        assertEquals("旧备注", bookmarkDao.getFirstPage(firstSessionId, 10).single().note)
        assertEquals(1, bookmarkDao.updateNote(firstSessionId, bookmarkId, "新备注"))

        val updated = bookmarkDao.getFirstPage(firstSessionId, 10).single()
        assertEquals(bookmarkId, updated.id)
        assertEquals(messageId, updated.messageId)
        assertEquals(firstSessionId, updated.sessionId)
        assertEquals(1234L, updated.createdAt)
        assertEquals("新备注", updated.note)
        assertEquals(0, bookmarkDao.updateNote(firstSessionId, bookmarkId + 9999L, "不存在"))
    }

    @Test
    fun searchPageMatchesNotesLiterallyWithSessionAndKeysetBoundaries() = runBlocking {
        val sessionDao = database.sessionDao()
        val messageDao = database.messageDao()
        val bookmarkDao = database.bookmarkDao()
        val firstSessionId = sessionDao.insert(SessionEntity())
        val secondSessionId = sessionDao.insert(SessionEntity())
        val firstMessage = messageDao.insert(MessageEntity(sessionId = firstSessionId, content = "一"))
        val secondMessage = messageDao.insert(MessageEntity(sessionId = firstSessionId, content = "二"))
        val otherSessionMessage = messageDao.insert(MessageEntity(sessionId = secondSessionId, content = "三"))
        val firstId = bookmarkDao.insert(MessageBookmarkEntity(sessionId = firstSessionId, messageId = firstMessage, note = "中文 100%_Note", createdAt = 5_000L))
        val secondId = bookmarkDao.insert(MessageBookmarkEntity(sessionId = firstSessionId, messageId = secondMessage, note = "中文 100x_Note", createdAt = 5_000L))
        bookmarkDao.insert(MessageBookmarkEntity(sessionId = secondSessionId, messageId = otherSessionMessage, note = "中文 100%_Note", createdAt = 5_000L))
        val bulkIds = buildList {
            repeat(41) { index ->
                val messageId = messageDao.insert(MessageEntity(sessionId = firstSessionId, content = "批量$index"))
                add(bookmarkDao.insert(MessageBookmarkEntity(
                    sessionId = firstSessionId,
                    messageId = messageId,
                    note = "批量备注$index",
                    createdAt = 6_000L,
                )))
            }
        }

        assertEquals(emptyList<MessageBookmarkEntity>(), bookmarkDao.searchPage(firstSessionId, "", null, null, 0))
        assertEquals(listOf(firstId), bookmarkDao.searchPage(firstSessionId, "100%_", null, null, 40).map { it.id })
        assertEquals(listOf(secondId), bookmarkDao.searchPage(firstSessionId, "100x_", null, null, 41).map { it.id })
        assertEquals(listOf(secondId, firstId), bookmarkDao.searchPage(firstSessionId, "NOTE", null, null, 41).map { it.id })
        assertEquals(emptyList<MessageBookmarkEntity>(), bookmarkDao.searchPage(firstSessionId, "中文 100%_Note", 5_000L, firstId, 41))
        assertEquals(41, bulkIds.size)
        val firstBulkPage = bookmarkDao.searchPage(firstSessionId, "  批量备注  ", null, null, 40)
        assertEquals(40, firstBulkPage.size)
        val secondBulkPage = bookmarkDao.searchPage(
            firstSessionId,
            "批量备注",
            firstBulkPage.last().createdAt,
            firstBulkPage.last().id,
            40,
        )
        assertEquals(1, secondBulkPage.size)
        assertEquals(emptySet<Long>(), firstBulkPage.map { it.id }.toSet().intersect(secondBulkPage.map { it.id }.toSet()))
        assertEquals(bulkIds.toSet(), (firstBulkPage + secondBulkPage).map { it.id }.toSet())
        val escapedMessage = messageDao.insert(MessageEntity(sessionId = firstSessionId, content = "特殊"))
        val escapedId = bookmarkDao.insert(MessageBookmarkEntity(
            sessionId = firstSessionId,
            messageId = escapedMessage,
            note = "路径\\100%_完成",
            createdAt = 7_000L,
        ))
        assertEquals(1, bookmarkDao.searchPage(firstSessionId, "\\100%_", null, null, 41).size)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { bookmarkDao.searchPage(firstSessionId, "批量", 6_000L, null, 40) }
        }
        assertEquals(1, bookmarkDao.updateNote(firstSessionId, firstId, "改后备注"))
        assertEquals(listOf(escapedId), bookmarkDao.searchPage(firstSessionId, "100%_", null, null, 41).map { it.id })
        assertEquals(listOf(firstId), bookmarkDao.searchPage(firstSessionId, "改后", null, null, 41).map { it.id })
    }
}
