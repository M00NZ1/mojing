package com.mojing.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.mojing.app.data.local.entity.SessionContextMemoryEntity

@Dao
interface SessionContextMemoryDao {
    @Query("SELECT * FROM session_context_memories WHERE sessionId = :sessionId AND branchId = :branchId LIMIT 1")
    suspend fun getBySessionAndBranch(sessionId: Long, branchId: String): SessionContextMemoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SessionContextMemoryEntity): Long

    /**
     * 在读取本轮消息快照前预留唯一 revision。后发请求会让先发请求的条件写回失效，
     * 同时保留当前仍可用的记忆正文，远程请求失败时不会清空旧记忆。
     */
    @Transaction
    suspend fun reserveNextRevision(
        sessionId: Long,
        branchId: String,
        now: Long,
    ): Long {
        require(sessionId > 0L && branchId.isNotBlank())
        val current = getBySessionAndBranch(sessionId, branchId)
        val nextRevision = (current?.revision ?: 0L) + 1L
        upsert(
            current?.copy(revision = nextRevision)
                ?: SessionContextMemoryEntity(
                    sessionId = sessionId,
                    branchId = branchId,
                    isValid = false,
                    revision = nextRevision,
                    createdAt = now,
                    updatedAt = now,
                ),
        )
        return nextRevision
    }

    /** 远程结果只能覆盖请求开始时看到的同一 revision；故事线变更后的旧结果直接丢弃。 */
    @Transaction
    suspend fun replaceIfRevisionMatches(
        entity: SessionContextMemoryEntity,
        expectedRevision: Long,
    ): Boolean {
        require(entity.sessionId > 0L && entity.branchId.isNotBlank())
        val current = getBySessionAndBranch(entity.sessionId, entity.branchId)
        if ((current?.revision ?: 0L) != expectedRevision) return false
        upsert(
            entity.copy(
                id = current?.id ?: 0L,
                revision = expectedRevision,
                createdAt = current?.createdAt ?: entity.createdAt,
            ),
        )
        return true
    }

    /** 显式清空也推进 revision，避免已经在途的旧请求随后把内容写回来。 */
    @Transaction
    suspend fun clearAndAdvanceRevision(
        sessionId: Long,
        branchId: String,
        updatedAt: Long,
    ) {
        val current = getBySessionAndBranch(sessionId, branchId)
        upsert(
            SessionContextMemoryEntity(
                id = current?.id ?: 0L,
                sessionId = sessionId,
                branchId = branchId,
                isValid = false,
                revision = (current?.revision ?: 0L) + 1L,
                createdAt = current?.createdAt ?: updatedAt,
                updatedAt = updatedAt,
            ),
        )
    }

    @Query("DELETE FROM session_context_memories WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: Long)
}
