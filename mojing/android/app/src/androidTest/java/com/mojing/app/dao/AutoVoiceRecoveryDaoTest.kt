package com.mojing.app.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.AutoVoiceMetadata
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.MessageAttachmentEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AutoVoiceRecoveryDaoTest {
    private lateinit var database: AppDatabase

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() = database.close()

    @Test fun claimAndCompleteKeepOrderedVoiceChunksAndRejectDoubleClaim() = runBlocking {
        val (sessionId, characterId, sourceId) = seed()
        val oldToken = UUID.randomUUID().toString()
        val messageId = voiceMessage(sessionId, characterId, sourceId, AutoVoiceMetadata.STATE_FAILED, oldToken)
        val freshToken = UUID.randomUUID().toString()

        assertTrue(database.messageDao().claimAutoVoiceGeneration(messageId, sessionId, "main", oldToken, freshToken))
        assertFalse(database.messageDao().claimAutoVoiceGeneration(messageId, sessionId, "main", oldToken, UUID.randomUUID().toString()))
        val attachments = listOf(
            MessageAttachmentEntity(messageId = messageId, assetType = "voice", mimeType = "audio/wav", storagePath = "/owned/voice-0.wav"),
            MessageAttachmentEntity(messageId = messageId, assetType = "voice", mimeType = "audio/wav", storagePath = "/owned/voice-1.wav"),
        )
        assertTrue(database.messageDao().completeAutoVoiceGeneration(messageId, sessionId, "main", freshToken, attachments))
        assertEquals(attachments.map { it.storagePath }, database.attachmentDao().getByMessage(messageId).map { it.storagePath })
        val stored = database.messageDao().getById(messageId)!!
        assertEquals("", stored.content)
        assertEquals(AutoVoiceMetadata.STATE_COMPLETE, AutoVoiceMetadata.parse(stored.structuredContentJson)?.state)
        assertFalse(database.messageDao().completeAutoVoiceGeneration(messageId, sessionId, "main", freshToken, attachments))
    }

    @Test fun failureRetainsOriginalTextAndRejectsWrongBranchOrSource() = runBlocking {
        val (sessionId, characterId, sourceId) = seed()
        val token = UUID.randomUUID().toString()
        val messageId = voiceMessage(sessionId, characterId, sourceId, AutoVoiceMetadata.STATE_RUNNING, token)

        assertFalse(database.messageDao().failAutoVoiceGeneration(messageId, sessionId, "other", token, false))
        assertTrue(database.messageDao().failAutoVoiceGeneration(messageId, sessionId, "main", token, false))
        val stored = database.messageDao().getById(messageId)!!
        assertEquals("完整原文必须保留", AutoVoiceMetadata.parse(stored.structuredContentJson)?.text)
        assertEquals(AutoVoiceMetadata.STATE_FAILED, AutoVoiceMetadata.parse(stored.structuredContentJson)?.state)
    }

    @Test fun sourceDeletionWithCorrectTokenStillRejectsVoiceWrites() = runBlocking {
        val (sessionId, characterId, sourceId) = seed()
        val token = UUID.randomUUID().toString()
        val messageId = voiceMessage(sessionId, characterId, sourceId, AutoVoiceMetadata.STATE_RUNNING, token)
        assertEquals(1, database.messageDao().deleteRaw(sourceId))
        assertFalse(database.messageDao().failAutoVoiceGeneration(messageId, sessionId, "main", token, interrupted = true))
        assertFalse(database.messageDao().completeAutoVoiceGeneration(
            messageId, sessionId, "main", token,
            listOf(MessageAttachmentEntity(messageId = messageId, assetType = "voice", mimeType = "audio/wav", storagePath = "/owned/deleted.wav")),
        ))
        assertTrue(database.attachmentDao().getByMessage(messageId).isEmpty())
    }

    @Test fun runningRecoveryUsesExactOptionalTokenAndDoesNotCreateRetryForLegacyRow() = runBlocking {
        val (sessionId, characterId, sourceId) = seed()
        val token = UUID.randomUUID().toString()
        val messageId = voiceMessage(sessionId, characterId, sourceId, AutoVoiceMetadata.STATE_RUNNING, token)
        assertFalse(database.messageDao().markAutoVoiceRunningInterrupted(messageId, sessionId, "main", "wrong"))
        assertTrue(database.messageDao().markAutoVoiceRunningInterrupted(messageId, sessionId, "main", token))
        assertEquals(AutoVoiceMetadata.STATE_INTERRUPTED, AutoVoiceMetadata.parse(database.messageDao().getById(messageId)!!.structuredContentJson)?.state)

        val legacyId = database.messageDao().insert(MessageEntity(
            sessionId = sessionId, speakerType = "character", characterId = characterId,
            branchId = "main", parentMessageId = sourceId, includeInContext = false,
            content = "🔊 配音生成中…", structuredContentJson = "{}",
        ))
        assertTrue(database.messageDao().markAutoVoiceRunningInterrupted(legacyId, sessionId, "main"))
        assertEquals("配音已中断", database.messageDao().getById(legacyId)!!.content)
        assertEquals(AutoVoiceMetadata.STATE_INTERRUPTED, AutoVoiceMetadata.parse(database.messageDao().getById(legacyId)!!.structuredContentJson)?.state)

        val blankMetadataId = database.messageDao().insert(MessageEntity(
            sessionId = sessionId, speakerType = "character", characterId = characterId,
            branchId = "main", parentMessageId = sourceId, includeInContext = false,
            content = "", structuredContentJson = AutoVoiceMetadata.create("", "", ""),
        ))
        assertTrue(database.messageDao().markAutoVoiceRunningInterrupted(blankMetadataId, sessionId, "main"))
        assertEquals("配音已中断", database.messageDao().getById(blankMetadataId)!!.content)
    }

    @Test fun recallCascadesOnlyDerivedVoiceOnSourceBranch() = runBlocking {
        val sessionId = database.sessionDao().insert(SessionEntity(title = "自动配音"))
        val sourceId = database.messageDao().insert(MessageEntity(sessionId = sessionId, branchId = "main", content = "来源"))
        val ownedId = database.messageDao().insert(MessageEntity(
            sessionId = sessionId, speakerType = "character", branchId = "main", parentMessageId = sourceId,
            includeInContext = false, structuredContentJson = AutoVoiceMetadata.create("main", AutoVoiceMetadata.STATE_FAILED, "a"),
        ))
        val otherId = database.messageDao().insert(MessageEntity(
            sessionId = sessionId, speakerType = "character", branchId = "other", parentMessageId = sourceId,
            includeInContext = false, structuredContentJson = AutoVoiceMetadata.create("other", AutoVoiceMetadata.STATE_FAILED, "b"),
        ))
        database.messageDao().recallInSession(sessionId, sourceId)
        assertNull(database.messageDao().getById(ownedId))
        assertNotNull(database.messageDao().getById(otherId))
    }

    @Test fun completionAbortRollsBackAllVoiceAttachmentAndMessageWrites() = runBlocking {
        val (sessionId, characterId, sourceId) = seed()
        val oldToken = UUID.randomUUID().toString()
        val messageId = voiceMessage(sessionId, characterId, sourceId, AutoVoiceMetadata.STATE_FAILED, oldToken)
        val freshToken = UUID.randomUUID().toString()
        assertTrue(database.messageDao().claimAutoVoiceGeneration(messageId, sessionId, "main", oldToken, freshToken))
        val running = database.messageDao().getById(messageId)!!
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER abort_auto_voice_second_attachment BEFORE INSERT ON message_attachments " +
                "WHEN NEW.messageId = $messageId AND NEW.storagePath = '/owned/rollback-second.wav' " +
                "BEGIN SELECT RAISE(ABORT, 'test abort'); END",
        )
        val error = runCatching {
            database.messageDao().completeAutoVoiceGeneration(
                messageId, sessionId, "main", freshToken,
                listOf(
                    MessageAttachmentEntity(messageId = messageId, assetType = "voice", mimeType = "audio/wav", storagePath = "/owned/rollback-first.wav"),
                    MessageAttachmentEntity(messageId = messageId, assetType = "voice", mimeType = "audio/wav", storagePath = "/owned/rollback-second.wav"),
                ),
            )
        }.exceptionOrNull()
        assertNotNull(error)
        val current = database.messageDao().getById(messageId)!!
        assertEquals(running.content, current.content)
        assertEquals(running.structuredContentJson, current.structuredContentJson)
        assertTrue(database.attachmentDao().getByMessage(messageId).isEmpty())
    }

    private suspend fun seed(): Triple<Long, Long, Long> {
        val sessionId = database.sessionDao().insert(SessionEntity(title = "自动配音"))
        val characterId = database.characterDao().upsert(CharacterEntity(name = "角色"))
        val sourceId = database.messageDao().insert(MessageEntity(sessionId = sessionId, content = "来源"))
        return Triple(sessionId, characterId, sourceId)
    }

    private suspend fun voiceMessage(
        sessionId: Long,
        characterId: Long,
        sourceId: Long,
        state: String,
        token: String,
    ): Long = database.messageDao().insert(MessageEntity(
        sessionId = sessionId, speakerType = "character", characterId = characterId,
        branchId = "main", parentMessageId = sourceId, includeInContext = false,
        content = "🔊 配音生成中…", structuredContentJson = AutoVoiceMetadata.create("完整原文必须保留", state, token),
    ))
}
