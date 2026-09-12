package com.mojing.app.domain.usecase

import androidx.room.withTransaction
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.SessionEntity
import com.mojing.app.data.local.entity.SessionParticipantEntity
import com.mojing.app.data.local.entity.SessionWorldEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.ConfigEntity
import com.mojing.app.domain.story.StoryOpeningDraftCodec
import com.mojing.app.domain.story.StoryOpeningRecord
import javax.inject.Inject
import javax.inject.Singleton

/** 会话、世界配置、参与角色与开篇消息的整包写入事务。 */
@Singleton
class SessionCreationTransaction @Inject constructor(
    private val database: AppDatabase,
) {
    suspend operator fun invoke(
        session: SessionEntity,
        world: SessionWorldEntity,
        participants: List<SessionParticipantEntity>,
        initialMessages: List<MessageEntity> = emptyList(),
        storyDraftId: String? = null,
    ): Long = database.withTransaction {
        if (storyDraftId != null) {
            val raw = database.configDao().get(StoryOpeningDraftCodec.KEY)?.valueJson
                ?: error("Story draft is missing")
            val record = StoryOpeningDraftCodec.decode(raw)
            check(record.id == storyDraftId) { "Story draft changed" }
            if (record is StoryOpeningRecord.Saved) {
                check(database.sessionDao().getById(record.sessionId) != null) { "Saved story no longer exists" }
                return@withTransaction record.sessionId
            }
        }
        val sessionId = database.sessionDao().insert(session)
        check(sessionId > 0L) { "会话创建失败" }
        database.sessionWorldDao().upsert(world.copy(id = 0L, sessionId = sessionId))
        participants.forEach { participant ->
            database.participantDao().upsert(
                participant.copy(id = 0L, sessionId = sessionId),
            )
        }
        initialMessages.forEach { message ->
            database.messageDao().insert(message.copy(id = 0L, sessionId = sessionId, branchId = "main"))
        }
        if (initialMessages.isNotEmpty()) database.sessionDao().bumpUpdatedAt(sessionId)
        if (storyDraftId != null) database.configDao().set(ConfigEntity(StoryOpeningDraftCodec.KEY,
            StoryOpeningDraftCodec.encode(StoryOpeningRecord.Saved(storyDraftId, sessionId, session.title))))
        sessionId
    }
}
