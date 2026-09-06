package com.mojing.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mojing.app.data.local.entity.SessionMemorySegmentEntity

private const val VISIBLE_MEMORY_SEGMENTS_QUERY = """
    SELECT memory.*
    FROM branch_visibility_segments AS visibility
    JOIN session_memory_segments AS memory
      ON memory.sessionId = visibility.sessionId
     AND memory.branchId = visibility.sourceBranchId
     AND memory.endMessageId <= visibility.maxMessageId
    WHERE visibility.sessionId = :sessionId
      AND visibility.targetBranchId = :branchId
      AND NOT EXISTS (
          SELECT 1
          FROM messages AS replacement
          JOIN branch_visibility_segments AS replacement_visibility
            ON replacement_visibility.sessionId = replacement.sessionId
           AND replacement_visibility.targetBranchId = :branchId
           AND replacement_visibility.sourceBranchId = replacement.branchId
           AND replacement.id <= replacement_visibility.maxMessageId
          WHERE replacement.sessionId = :sessionId
            AND replacement.regeneratedFromMessageId BETWEEN memory.startMessageId AND memory.endMessageId
            AND replacement.branchId <> memory.branchId
      )
"""

@Dao
interface SessionMemorySegmentDao {
    @Query(
        "SELECT * FROM session_memory_segments WHERE sessionId = :sessionId AND branchId = 'main' " +
            "ORDER BY endMessageId DESC, id DESC LIMIT :limit",
    )
    suspend fun getRecentMain(sessionId: Long, limit: Int): List<SessionMemorySegmentEntity>

    @Query("$VISIBLE_MEMORY_SEGMENTS_QUERY ORDER BY memory.endMessageId DESC, memory.id DESC LIMIT :limit")
    suspend fun getRecentVisible(
        sessionId: Long,
        branchId: String,
        limit: Int,
    ): List<SessionMemorySegmentEntity>

    suspend fun getRecentForBranch(
        sessionId: Long,
        branchId: String,
        limit: Int = 6,
    ): List<SessionMemorySegmentEntity> {
        require(limit in 1..100)
        return if (branchId == "main") getRecentMain(sessionId, limit)
        else getRecentVisible(sessionId, branchId, limit)
    }

    @Query("SELECT * FROM session_memory_segments WHERE sessionId = :sessionId AND branchId = :branchId ORDER BY segmentIndex ASC")
    suspend fun getBySessionAndBranch(sessionId: Long, branchId: String): List<SessionMemorySegmentEntity>

    @Query(
        "SELECT COALESCE(MAX(segmentIndex), -1) + 1 FROM session_memory_segments " +
            "WHERE sessionId = :sessionId AND branchId = :branchId",
    )
    suspend fun nextSegmentIndex(sessionId: Long, branchId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SessionMemorySegmentEntity): Long

    @Insert
    suspend fun insertCompacted(entity: SessionMemorySegmentEntity): Long
}
