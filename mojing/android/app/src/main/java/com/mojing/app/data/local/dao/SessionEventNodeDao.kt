package com.mojing.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mojing.app.data.local.entity.SessionEventNodeEntity

private const val VISIBLE_EVENT_NODES_QUERY = """
    SELECT event.*
    FROM branch_visibility_segments AS visibility
    JOIN session_event_nodes AS event
      ON event.sessionId = visibility.sessionId
     AND event.branchId = visibility.sourceBranchId
    WHERE visibility.sessionId = :sessionId
      AND visibility.targetBranchId = :branchId
      AND (
          (event.messageId IS NOT NULL AND event.messageId <= visibility.maxMessageId)
          OR (event.messageId IS NULL AND event.branchId = :branchId)
      )
      AND NOT EXISTS (
          SELECT 1
          FROM messages AS replacement
          JOIN branch_visibility_segments AS replacement_visibility
            ON replacement_visibility.sessionId = replacement.sessionId
           AND replacement_visibility.targetBranchId = :branchId
           AND replacement_visibility.sourceBranchId = replacement.branchId
           AND replacement.id <= replacement_visibility.maxMessageId
          WHERE replacement.sessionId = :sessionId
            AND replacement.regeneratedFromMessageId = event.messageId
            AND replacement.branchId <> event.branchId
      )
"""

@Dao
interface SessionEventNodeDao {
    @Query("SELECT * FROM session_event_nodes WHERE sessionId = :sessionId ORDER BY importance DESC")
    suspend fun getBySession(sessionId: Long): List<SessionEventNodeEntity>

    @Query("SELECT * FROM session_event_nodes WHERE sessionId = :sessionId AND branchId = :branchId ORDER BY importance DESC")
    suspend fun getBySessionAndBranch(sessionId: Long, branchId: String): List<SessionEventNodeEntity>

    @Query("$VISIBLE_EVENT_NODES_QUERY ORDER BY event.importance DESC, event.id DESC")
    suspend fun getVisibleForBranch(sessionId: Long, branchId: String): List<SessionEventNodeEntity>

    suspend fun getForBranch(sessionId: Long, branchId: String): List<SessionEventNodeEntity> =
        if (branchId == "main") getBySessionAndBranch(sessionId, branchId)
        else getVisibleForBranch(sessionId, branchId)

    @Query("UPDATE session_event_nodes SET resolved = NOT resolved WHERE id = :id")
    suspend fun toggleResolved(id: Long)

    @Query("UPDATE session_event_nodes SET resolved = :resolved WHERE id = :id")
    suspend fun setResolved(id: Long, resolved: Boolean)

    @Query("DELETE FROM session_event_nodes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SessionEventNodeEntity): Long
}
