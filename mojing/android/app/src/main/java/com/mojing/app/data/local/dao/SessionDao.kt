package com.mojing.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.mojing.app.data.local.entity.SessionEntity
import com.mojing.app.data.local.entity.SessionWithListMeta
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY CASE WHEN pinnedAt > 0 THEN 0 ELSE 1 END, pinnedAt DESC, updatedAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    /** Fetch one bounded page before calculating card metadata; IDs break timestamp ties. */
    @Query(
        """
        SELECT s.*,
          (SELECT substr(m.content, 1, 1024) FROM messages m WHERE m.sessionId = s.id AND m.branchId = 'main' ORDER BY m.id DESC LIMIT 1) AS last_msg_preview,
          (SELECT m.speakerType FROM messages m WHERE m.sessionId = s.id AND m.branchId = 'main' ORDER BY m.id DESC LIMIT 1) AS last_msg_speaker_type,
          (SELECT COUNT(*) FROM messages m2 WHERE m2.sessionId = s.id AND m2.branchId = 'main') AS msg_count,
          (SELECT COUNT(*) FROM session_participants sp WHERE sp.sessionId = s.id) AS participant_count
        FROM (
          SELECT * FROM sessions
          WHERE (:query = '' OR instr(lower(title), lower(:query)) > 0)
            AND (:cursorId IS NULL OR pinnedAt < :cursorPinnedAt
              OR (pinnedAt = :cursorPinnedAt AND updatedAt < :cursorUpdatedAt)
              OR (pinnedAt = :cursorPinnedAt AND updatedAt = :cursorUpdatedAt AND id < :cursorId))
          ORDER BY pinnedAt DESC, updatedAt DESC, id DESC
          LIMIT :limit
        ) s
        ORDER BY s.pinnedAt DESC, s.updatedAt DESC, s.id DESC
        """,
    )
    fun observeListPageWithMeta(
        query: String,
        cursorPinnedAt: Long?,
        cursorUpdatedAt: Long?,
        cursorId: Long?,
        limit: Int,
    ): Flow<List<SessionWithListMeta>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getById(id: Long): SessionEntity?

    @Query("SELECT * FROM sessions WHERE creationRequestId = :requestId LIMIT 1")
    suspend fun getByCreationRequestId(requestId: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE title LIKE '%' || :query || '%' ORDER BY CASE WHEN pinnedAt > 0 THEN 0 ELSE 1 END, pinnedAt DESC, updatedAt DESC")
    suspend fun search(query: String): List<SessionEntity>

    @Update
    suspend fun update(entity: SessionEntity)

    /** 会话插入不得 REPLACE 已有行，否则会级联删除原故事数据。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: SessionEntity): Long

    @Query("UPDATE sessions SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String, updatedAt: Long = System.currentTimeMillis())

    @Query(
        """
        UPDATE sessions
        SET title = CASE
            WHEN TRIM(title) = '' OR title = '新对话' THEN :title
            ELSE title
        END,
        updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun touchWithGeneratedTitle(
        id: Long,
        title: String,
        updatedAt: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE sessions SET summary = :summary, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateSummary(id: Long, summary: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE sessions SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun bumpUpdatedAt(id: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE sessions SET thinkMaxEnabled = :enabled, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateThinkMax(id: Long, enabled: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteRaw(id: Long)

    @Query("DELETE FROM app_config WHERE `key` LIKE 'memory_compaction_checkpoint_v1:' || :id || ':%'")
    suspend fun deleteMemoryCompactionCheckpoints(id: Long)

    @Query("DELETE FROM app_config WHERE `key` LIKE 'memory_compaction_gap_checkpoint_v1:' || :id || ':%' OR `key` LIKE 'memory_gap_scan_v1:' || :id || ':%'")
    suspend fun deleteMemoryGapProgress(id: Long)

    @Query("DELETE FROM app_config WHERE `key` = 'message_search_session_state_v2:' || :id")
    suspend fun deleteSearchIndexCheckpoint(id: Long)

    @Transaction
    suspend fun delete(id: Long) {
        deleteMemoryCompactionCheckpoints(id)
        deleteMemoryGapProgress(id)
        deleteSearchIndexCheckpoint(id)
        deleteRaw(id)
    }
}
