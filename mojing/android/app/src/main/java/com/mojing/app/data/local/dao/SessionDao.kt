package com.mojing.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mojing.app.data.local.entity.SessionEntity
import com.mojing.app.data.local.entity.SessionWithListMeta
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY CASE WHEN pinnedAt > 0 THEN 0 ELSE 1 END, pinnedAt DESC, updatedAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query(
        """
        SELECT s.*,
          (SELECT m.content FROM messages m WHERE m.sessionId = s.id AND m.branchId = 'main' ORDER BY m.createdAt DESC, m.id DESC LIMIT 1) AS last_msg_preview,
          (SELECT m.speakerType FROM messages m WHERE m.sessionId = s.id AND m.branchId = 'main' ORDER BY m.createdAt DESC, m.id DESC LIMIT 1) AS last_msg_speaker_type,
          (SELECT COUNT(*) FROM messages m2 WHERE m2.sessionId = s.id AND m2.branchId = 'main') AS msg_count,
          (SELECT COUNT(*) FROM session_participants sp WHERE sp.sessionId = s.id) AS participant_count
        FROM sessions s
        ORDER BY CASE WHEN s.pinnedAt > 0 THEN 0 ELSE 1 END, s.pinnedAt DESC, s.updatedAt DESC
        """,
    )
    fun observeAllWithListMeta(): Flow<List<SessionWithListMeta>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getById(id: Long): SessionEntity?

    @Query("SELECT * FROM sessions WHERE title LIKE '%' || :query || '%' ORDER BY CASE WHEN pinnedAt > 0 THEN 0 ELSE 1 END, pinnedAt DESC, updatedAt DESC")
    suspend fun search(query: String): List<SessionEntity>

    @Update
    suspend fun update(entity: SessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
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
    suspend fun delete(id: Long)
}
