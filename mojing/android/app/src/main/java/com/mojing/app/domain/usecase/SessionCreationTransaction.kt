package com.mojing.app.domain.usecase

import androidx.room.withTransaction
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.SessionEntity
import com.mojing.app.data.local.entity.SessionParticipantEntity
import com.mojing.app.data.local.entity.SessionWorldEntity
import javax.inject.Inject
import javax.inject.Singleton

/** 会话、世界配置与参与角色的唯一整包写入事务。 */
@Singleton
class SessionCreationTransaction @Inject constructor(
    private val database: AppDatabase,
) {
    suspend operator fun invoke(
        session: SessionEntity,
        world: SessionWorldEntity,
        participants: List<SessionParticipantEntity>,
    ): Long = database.withTransaction {
        val sessionId = database.sessionDao().insert(session)
        check(sessionId > 0L) { "会话创建失败" }
        database.sessionWorldDao().upsert(world.copy(id = 0L, sessionId = sessionId))
        participants.forEach { participant ->
            database.participantDao().upsert(
                participant.copy(id = 0L, sessionId = sessionId),
            )
        }
        sessionId
    }
}
