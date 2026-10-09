package com.mojing.app.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.AutoImageMetadata
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.MessageAttachmentEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AutoImageRecoveryDaoTest {
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
    fun claimIsSingleOwnerAndCompletionCommitsAttachmentAndMessageTogether() = runBlocking {
        val sessionId = database.sessionDao().insert(SessionEntity(title = "自动配图"))
        val characterId = database.characterDao().upsert(CharacterEntity(name = "角色"))
        val sourceId = database.messageDao().insert(MessageEntity(sessionId = sessionId, content = "来源"))
        val oldToken = UUID.randomUUID().toString()
        val messageId = database.messageDao().insert(MessageEntity(
            sessionId = sessionId,
            speakerType = "character",
            characterId = characterId,
            branchId = "main",
            parentMessageId = sourceId,
            content = "🖼 配图生成失败，可重试",
            structuredContentJson = AutoImageMetadata.create("一座雾港灯塔", AutoImageMetadata.STATE_FAILED, oldToken),
            includeInContext = false,
        ))
        val freshToken = UUID.randomUUID().toString()
        assertTrue(database.messageDao().claimAutoImageGeneration(messageId, sessionId, "main", oldToken, freshToken))
        assertFalse(database.messageDao().claimAutoImageGeneration(messageId, sessionId, "main", oldToken, UUID.randomUUID().toString()))

        val path = "/owned/auto-image.png"
        assertTrue(database.messageDao().completeAutoImageGeneration(
            messageId, sessionId, "main", freshToken,
            MessageAttachmentEntity(messageId = messageId, storagePath = path, mimeType = "image/png"),
        ))
        val stored = database.messageDao().getById(messageId)!!
        assertEquals("", stored.content)
        assertEquals(1, database.attachmentDao().getByMessage(messageId).size)
        assertEquals(AutoImageMetadata.STATE_COMPLETE, AutoImageMetadata.parse(stored.structuredContentJson)?.state)
        assertFalse(database.messageDao().completeAutoImageGeneration(
            messageId, sessionId, "main", oldToken,
            MessageAttachmentEntity(messageId = messageId, storagePath = "/owned/old.png", mimeType = "image/png"),
        ))
        assertEquals(1, database.attachmentDao().getByMessage(messageId).size)
    }

    @Test
    fun failureRetainsPromptAndStaleSourceOrBranchCannotWrite() = runBlocking {
        val sessionId = database.sessionDao().insert(SessionEntity(title = "自动配图"))
        val characterId = database.characterDao().upsert(CharacterEntity(name = "角色"))
        val sourceId = database.messageDao().insert(MessageEntity(sessionId = sessionId, content = "来源"))
        val token = UUID.randomUUID().toString()
        val messageId = database.messageDao().insert(MessageEntity(
            sessionId = sessionId,
            speakerType = "character",
            characterId = characterId,
            branchId = "main",
            parentMessageId = sourceId,
            content = "🖼 配图生成中…",
            structuredContentJson = AutoImageMetadata.create("不要丢失的原始提示", AutoImageMetadata.STATE_RUNNING, token),
            includeInContext = false,
        ))
        assertFalse(database.messageDao().claimAutoImageGeneration(
            messageId, sessionId, "other", token, UUID.randomUUID().toString(),
        ))
        assertFalse(database.messageDao().completeAutoImageGeneration(
            messageId, sessionId, "other", token,
            MessageAttachmentEntity(messageId = messageId, storagePath = "/owned/wrong-branch.png", mimeType = "image/png"),
        ))
        assertFalse(database.messageDao().failAutoImageGeneration(messageId, sessionId, "other", token, interrupted = false))
        assertTrue(database.messageDao().failAutoImageGeneration(messageId, sessionId, "main", token, interrupted = false))
        val failed = database.messageDao().getById(messageId)!!
        assertEquals("不要丢失的原始提示", AutoImageMetadata.parse(failed.structuredContentJson)?.prompt)
        assertEquals(AutoImageMetadata.STATE_FAILED, AutoImageMetadata.parse(failed.structuredContentJson)?.state)
    }

    @Test
    fun runningTailCanBeMarkedInterruptedWithoutVendorRetry() = runBlocking {
        val sessionId = database.sessionDao().insert(SessionEntity(title = "自动配图"))
        val characterId = database.characterDao().upsert(CharacterEntity(name = "角色"))
        val sourceId = database.messageDao().insert(MessageEntity(sessionId = sessionId, content = "来源"))
        val token = UUID.randomUUID().toString()
        val messageId = database.messageDao().insert(MessageEntity(
            sessionId = sessionId,
            speakerType = "character",
            characterId = characterId,
            branchId = "main",
            parentMessageId = sourceId,
            content = "🖼 配图生成中…",
            structuredContentJson = AutoImageMetadata.create("保留提示", AutoImageMetadata.STATE_RUNNING, token),
            includeInContext = false,
        ))
        assertTrue(database.messageDao().markAutoImageRunningInterrupted(messageId, sessionId, "main"))
        val recovered = database.messageDao().getById(messageId)!!
        assertEquals("🖼 配图生成中断，可重试", recovered.content)
        assertEquals("保留提示", AutoImageMetadata.parse(recovered.structuredContentJson)?.prompt)
        assertEquals(AutoImageMetadata.STATE_INTERRUPTED, AutoImageMetadata.parse(recovered.structuredContentJson)?.state)
        assertFalse(database.messageDao().completeAutoImageGeneration(
            messageId, sessionId, "main", token,
            MessageAttachmentEntity(messageId = messageId, storagePath = "/owned/stale.png", mimeType = "image/png"),
        ))
    }

    @Test
    fun sourceDeletionLeavesRawChildButBlocksAcquisitionAndCompletion() = runBlocking {
        val sessionId = database.sessionDao().insert(SessionEntity(title = "自动配图"))
        val characterId = database.characterDao().upsert(CharacterEntity(name = "角色"))
        val sourceId = database.messageDao().insert(MessageEntity(sessionId = sessionId, content = "来源"))
        val mediaId = database.messageDao().insert(MessageEntity(
            sessionId = sessionId,
            speakerType = "character",
            characterId = characterId,
            branchId = "main",
            parentMessageId = sourceId,
            content = "🖼 配图已中断",
            structuredContentJson = AutoImageMetadata.create(
                "旧分支来源撤回提示", AutoImageMetadata.STATE_INTERRUPTED, "legacy-token",
            ),
            includeInContext = false,
        ))
        val runningId = database.messageDao().insert(MessageEntity(
            sessionId = sessionId, speakerType = "character", characterId = characterId,
            branchId = "main", parentMessageId = sourceId, includeInContext = false,
            content = "🖼 配图生成中…",
            structuredContentJson = AutoImageMetadata.create("来源已删除", AutoImageMetadata.STATE_RUNNING, "running-token"),
        ))
        assertTrue(database.messageDao().deleteRaw(sourceId) == 1)
        assertNotNull(database.messageDao().getById(mediaId))
        assertFalse(database.messageDao().claimAutoImageGeneration(
            mediaId, sessionId, "main", "legacy-token", UUID.randomUUID().toString(),
        ))
        assertFalse(database.messageDao().completeAutoImageGeneration(
            runningId, sessionId, "main", "running-token",
            MessageAttachmentEntity(messageId = runningId, storagePath = "/owned/deleted-source.png", mimeType = "image/png"),
        ))
        assertEquals(AutoImageMetadata.STATE_RUNNING, AutoImageMetadata.parse(database.messageDao().getById(runningId)!!.structuredContentJson)?.state)
        assertEquals(0, database.attachmentDao().getByMessage(runningId).size)
    }

    @Test
    fun recallInSessionDeletesExtendedV1MediaOnlyOnSourceBranch() = runBlocking {
        val sessionId = database.sessionDao().insert(SessionEntity(title = "自动配图"))
        val sourceId = database.messageDao().insert(
            MessageEntity(sessionId = sessionId, branchId = "main", content = "来源"),
        )
        val ownedId = database.messageDao().insert(
            MessageEntity(
                sessionId = sessionId,
                speakerType = "character",
                branchId = "main",
                parentMessageId = sourceId,
                includeInContext = false,
                content = "🖼 配图已中断",
                structuredContentJson = AutoImageMetadata.create(
                    "扩展撤回提示", AutoImageMetadata.STATE_INTERRUPTED, "attempt-1",
                ),
            ),
        )
        val otherBranchId = database.messageDao().insert(
            MessageEntity(
                sessionId = sessionId,
                speakerType = "character",
                branchId = "other",
                parentMessageId = sourceId,
                includeInContext = false,
                content = "其他线配图",
                structuredContentJson = AutoImageMetadata.create(
                    "其他线提示", AutoImageMetadata.STATE_INTERRUPTED, "attempt-2",
                ),
            ),
        )
        database.messageDao().recallInSession(sessionId, sourceId)
        assertEquals(null, database.messageDao().getById(sourceId))
        assertEquals(null, database.messageDao().getById(ownedId))
        assertNotNull(database.messageDao().getById(otherBranchId))
    }

    @Test
    fun completionAbortRollsBackAttachmentContentAndMetadataTogether() = runBlocking {
        val sessionId = database.sessionDao().insert(SessionEntity(title = "自动配图"))
        val characterId = database.characterDao().upsert(CharacterEntity(name = "角色"))
        val sourceId = database.messageDao().insert(MessageEntity(sessionId = sessionId, content = "来源"))
        val oldToken = UUID.randomUUID().toString()
        val mediaId = database.messageDao().insert(MessageEntity(
            sessionId = sessionId,
            speakerType = "character",
            characterId = characterId,
            branchId = "main",
            parentMessageId = sourceId,
            content = "🖼 配图生成失败，可重试",
            structuredContentJson = AutoImageMetadata.create("事务回滚提示", AutoImageMetadata.STATE_FAILED, oldToken),
            includeInContext = false,
        ))
        val freshToken = UUID.randomUUID().toString()
        assertTrue(database.messageDao().claimAutoImageGeneration(mediaId, sessionId, "main", oldToken, freshToken))
        val runningMetadata = database.messageDao().getById(mediaId)!!.structuredContentJson
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER abort_auto_image_complete BEFORE UPDATE OF structuredContentJson ON messages " +
                "WHEN NEW.id = $mediaId AND json_extract(NEW.structuredContentJson, '$.auto_media_state') = 'complete' " +
                "BEGIN SELECT RAISE(ABORT, 'test abort'); END",
        )
        val failure = runCatching {
            database.messageDao().completeAutoImageGeneration(
                mediaId, sessionId, "main", freshToken,
                MessageAttachmentEntity(messageId = mediaId, storagePath = "/owned/rollback.png", mimeType = "image/png"),
            )
        }.exceptionOrNull()
        assertNotNull(failure)
        val current = database.messageDao().getById(mediaId)!!
        assertEquals("🖼 配图生成中…", current.content)
        assertEquals(AutoImageMetadata.STATE_RUNNING, AutoImageMetadata.parse(current.structuredContentJson)?.state)
        assertEquals(runningMetadata, current.structuredContentJson)
        assertEquals(0, database.attachmentDao().getByMessage(mediaId).size)
    }
}
