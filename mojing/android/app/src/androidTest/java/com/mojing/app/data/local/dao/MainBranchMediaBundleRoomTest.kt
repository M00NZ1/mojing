package com.mojing.app.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.MessageAttachmentEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainBranchMediaBundleRoomTest {
    private lateinit var database: AppDatabase
    private lateinit var messageDao: MessageDao
    private var sessionId: Long = 0L

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        messageDao = database.messageDao()
        sessionId = database.sessionDao().insert(SessionEntity(title = "媒体包事务验收"))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun commitsEmptyMediaRowsPreservesParentRolesMetadataAndAttachments() = runBlocking {
        val parent = messageDao.insert(MessageEntity(
            sessionId = sessionId,
            speakerType = "user",
            content = "原始父消息",
        ))
        val batch = "bundle-commit"
        val written = writeBundle(batch, 4) { insertMessage, insertAttachments ->
            val manual = insertMessage(mediaMessage(batch, "image", parentMessageId = parent, content = "", includeInContext = false))
            insertAttachments(listOf(attachment(manual, "manual.png", "image/png", "manual-image")))
            val automatic = insertMessage(mediaMessage(batch, "image", parentMessageId = parent, content = "", includeInContext = false,
                metadata = "\"generationPrompt\":\"海边灯塔\",\"generationModel\":\"local-image\""))
            insertAttachments(listOf(attachment(automatic, "auto.png", "image/png", "auto-image", prompt = "海边灯塔", model = "local-image")))
            val voice1 = insertMessage(mediaMessage(batch, "voice", parentMessageId = parent, speakerType = "character", characterId = 7L,
                content = "", includeInContext = false, metadata = "\"voiceNonce\":\"v1\""))
            val voice2 = insertMessage(mediaMessage(batch, "voice", parentMessageId = voice1, speakerType = "narrator", content = "",
                includeInContext = false, metadata = "\"voiceNonce\":\"v2\""))
            insertAttachments(listOf(
                attachment(voice1, "voice-1.wav", "audio/wav", "voice-1"),
                attachment(voice2, "voice-2.wav", "audio/wav", "voice-2"),
            ))
            4
        }

        assertEquals(4, written)
        val rows = messageDao.getMainBranchMessages(sessionId).filter { it.structuredContentJson.contains(batch) }
        assertEquals(4, rows.size)
        assertTrue(rows.all { it.content.isEmpty() && !it.includeInContext })
        assertEquals(parent, rows[0].parentMessageId)
        assertEquals("character", rows[2].speakerType)
        assertEquals(7L, rows[2].characterId)
        assertEquals("narrator", rows[3].speakerType)
        assertEquals(rows[2].id, rows[3].parentMessageId)
        assertTrue(rows[1].structuredContentJson.contains("generationPrompt"))
        assertTrue(rows[2].structuredContentJson.contains("voiceNonce"))
        assertEquals(4, database.attachmentDao().getByMessages(rows.map { it.id }).size)
        assertEquals(1, database.attachmentDao().getByMessage(rows[0].id).size)
        assertEquals("audio/wav", database.attachmentDao().getByMessage(rows[2].id).single().mimeType)
    }

    @Test
    fun duplicateBatchIsIdempotentAndTextBatchGuardRejects() = runBlocking {
        val batch = "bundle-duplicate"
        assertEquals(1, writeBundle(batch, 1, textBatchId = null) { insertMessage, _ ->
            insertMessage(mediaMessage(batch, "image", content = ""))
            1
        })
        assertEquals(0, writeBundle(batch, 1, textBatchId = null) { _, _ -> error("duplicate must not write") })
        assertEquals(1, messageDao.countImportBatch(sessionId, "main", "\"mojing_media_bundle_batch\":\"$batch\""))

        val textBatch = "text-already-imported"
        messageDao.insert(MessageEntity(
            sessionId = sessionId,
            structuredContentJson = "{\"st_import_batch\":\"$textBatch\"}",
        ))
        val failure = runCatching {
            writeBundle("bundle-text-conflict", 1, textBatchId = textBatch) { insertMessage, _ ->
                insertMessage(mediaMessage("bundle-text-conflict", "image", content = ""))
                1
            }
        }
        assertTrue(failure.isFailure)
        assertEquals(1, messageDao.countImportBatch(sessionId, "main", "\"st_import_batch\":\"$textBatch\""))
    }

    @Test
    fun writesBeyond256RowsUsingBoundedCallback() = runBlocking {
        val batch = "bundle-large"
        val count = 300
        assertEquals(count, writeBundle(batch, count) { insertMessage, _ ->
            repeat(count) { index ->
                insertMessage(mediaMessage(batch, "image", content = if (index == 0) "" else "row-$index", createdAt = index.toLong()))
            }
            count
        })
        assertEquals(count, messageDao.countImportBatch(sessionId, "main", "\"mojing_media_bundle_batch\":\"$batch\""))
    }

    @Test
    fun secondAttachmentFailureRollsBackWholeBundle() = runBlocking {
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_media_bundle_attachment
            BEFORE INSERT ON message_attachments
            WHEN NEW.fileName = 'fail.wav'
            BEGIN SELECT RAISE(ABORT, 'forced media bundle attachment failure'); END
            """.trimIndent(),
        )
        val failure = runCatching {
            writeBundle("bundle-trigger-failure", 2) { insertMessage, insertAttachments ->
                val first = insertMessage(mediaMessage("bundle-trigger-failure", "voice", content = ""))
                val second = insertMessage(mediaMessage("bundle-trigger-failure", "voice", content = ""))
                insertAttachments(listOf(attachment(first, "ok.wav", "audio/wav", "ok"), attachment(second, "fail.wav", "audio/wav", "fail")))
                2
            }
        }
        assertTrue(failure.isFailure)
        assertEquals(0, messageDao.countImportBatch(sessionId, "main", "\"mojing_media_bundle_batch\":\"bundle-trigger-failure\""))
        assertEquals(0, database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM message_attachments").use { it.moveToFirst(); it.getInt(0) })
    }

    @Test
    fun coroutineCancellationDuringRowsRollsBackWholeBundle() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        lateinit var job: Job
        job = launch {
            writeBundle("bundle-cancel", 64) { insertMessage, _ ->
                repeat(64) { index ->
                    insertMessage(mediaMessage("bundle-cancel", "image", content = "row-$index"))
                    if (index == 0) {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }
                64
            }
        }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(0, messageDao.countImportBatch(sessionId, "main", "\"mojing_media_bundle_batch\":\"bundle-cancel\""))
        assertEquals(0, messageDao.messageCount(sessionId))
    }

    private suspend fun writeBundle(
        batchId: String,
        expectedCount: Int,
        textBatchId: String? = null,
        writeRows: suspend (
            insertMessage: suspend (MessageEntity) -> Long,
            insertAttachments: suspend (List<MessageAttachmentEntity>) -> Int,
        ) -> Int,
    ): Int = messageDao.insertMediaBundleIfAbsent(
        sessionId = sessionId,
        branchId = "main",
        batchId = batchId,
        expectedCount = expectedCount,
        textBatchId = textBatchId,
        beforeCommit = {},
        writeRows = writeRows,
    )

    private fun mediaMessage(
        batch: String,
        assetType: String,
        parentMessageId: Long? = null,
        speakerType: String = "character",
        characterId: Long? = null,
        content: String,
        includeInContext: Boolean = false,
        metadata: String = "",
        createdAt: Long = 1L,
    ) = MessageEntity(
        sessionId = sessionId,
        speakerType = speakerType,
        characterId = characterId,
        branchId = "main",
        parentMessageId = parentMessageId,
        content = content,
        structuredContentJson = "{\"mojing_media_bundle_batch\":\"$batch\",\"assetType\":\"$assetType\"${if (metadata.isBlank()) "" else ",$metadata"}}",
        includeInContext = includeInContext,
        createdAt = createdAt,
    )

    private fun attachment(
        messageId: Long,
        fileName: String,
        mimeType: String,
        storagePath: String,
        prompt: String = "",
        model: String = "",
    ) = MessageAttachmentEntity(
        messageId = messageId,
        assetType = if (mimeType.startsWith("audio")) "voice" else "image",
        fileName = fileName,
        mimeType = mimeType,
        storagePath = storagePath,
        generationPrompt = prompt,
        generationModel = model,
    )
}
