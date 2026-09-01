package com.mojing.app.usecase

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.MessageAttachmentEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionEntity
import com.mojing.app.domain.usecase.MessageSubmissionTransaction
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageSubmissionTransactionInstrumentedTest {
    private lateinit var database: AppDatabase
    private lateinit var submitMessage: MessageSubmissionTransaction
    private var sessionId: Long = 0L

    @Before
    fun setup() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        submitMessage = MessageSubmissionTransaction(database)
        sessionId = database.sessionDao().insert(SessionEntity(title = "附件事务测试"))
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun messageAndAllAttachmentsCommitTogether() = runBlocking {
        val messageId = submitMessage(
            message = MessageEntity(
                sessionId = sessionId,
                content = "看这两张图",
                structuredContentJson = "{\"draftSubmissionId\":\"submission-success\"}",
            ),
            attachments = listOf(
                pendingAttachment("first.png", "F:/pending/first.png"),
                pendingAttachment("second.png", "F:/pending/second.png"),
            ),
        )

        assertEquals(1, database.messageDao().messageCount(sessionId))
        val attachments = database.attachmentDao().getByMessage(messageId)
        assertEquals(2, attachments.size)
        assertTrue(attachments.all { it.messageId == messageId })
        assertEquals(
            1,
            database.messageDao().countDraftSubmission(
                sessionId,
                "\"draftSubmissionId\":\"submission-success\"",
            ),
        )
        assertEquals(
            0,
            database.messageDao().countDraftSubmission(
                sessionId,
                "\"draftSubmissionId\":\"submission-not-present\"",
            ),
        )
        assertEquals(
            setOf("F:/pending/first.png", "F:/pending/second.png"),
            database.attachmentDao().getReferencedStoragePaths(
                listOf(
                    "F:/pending/first.png",
                    "F:/pending/second.png",
                    "F:/pending/not-sent.png",
                ),
            ).toSet(),
        )
    }

    @Test
    fun secondAttachmentFailureRollsBackMessageAndFirstAttachment() = runBlocking {
        database.openHelper.writableDatabase.execSQL(
            """
                CREATE TRIGGER fail_second_attachment
                BEFORE INSERT ON message_attachments
                WHEN NEW.fileName = 'fail.png'
                BEGIN
                    SELECT RAISE(ABORT, 'forced attachment failure');
                END
            """.trimIndent(),
        )

        val result = runCatching {
            submitMessage(
                message = MessageEntity(sessionId = sessionId, content = "不能留下半条"),
                attachments = listOf(
                    pendingAttachment("first.png", "F:/pending/first.png"),
                    pendingAttachment("fail.png", "F:/pending/fail.png"),
                ),
            )
        }

        assertTrue(result.isFailure)
        assertEquals(0, countRows("messages"))
        assertEquals(0, countRows("message_attachments"))
    }

    private fun pendingAttachment(fileName: String, path: String) = MessageAttachmentEntity(
        messageId = 0L,
        fileName = fileName,
        mimeType = "image/png",
        storagePath = path,
    )

    private fun countRows(table: String): Int =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
}
