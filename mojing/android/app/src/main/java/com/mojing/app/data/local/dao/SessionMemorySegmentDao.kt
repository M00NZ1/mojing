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
            AND replacement.regeneratedFromMessageId <= memory.endMessageId
            AND replacement.branchId <> memory.branchId
      )
      AND (
          memory.branchId = :branchId OR NOT EXISTS (
              SELECT 1
              FROM branch_swipe_selections AS choice
              WHERE choice.sessionId = :sessionId
                AND choice.branchId IN (:branchId, memory.branchId)
                AND COALESCE((
                    SELECT selectedMessageId FROM branch_swipe_selections
                    WHERE sessionId = :sessionId AND branchId = :branchId
                      AND swipeGroupId = choice.swipeGroupId
                ), -1) <> COALESCE((
                    SELECT selectedMessageId FROM branch_swipe_selections
                    WHERE sessionId = :sessionId AND branchId = memory.branchId
                      AND swipeGroupId = choice.swipeGroupId
                ), -1)
                AND EXISTS (
                    SELECT 1 FROM messages AS source
                    JOIN branch_visibility_segments AS source_visibility
                      ON source_visibility.sessionId = source.sessionId
                     AND source_visibility.targetBranchId = :branchId
                     AND source_visibility.sourceBranchId = source.branchId
                     AND source.id <= source_visibility.maxMessageId
                    WHERE source.sessionId = :sessionId
                      AND source.swipeGroupId = choice.swipeGroupId
                      AND source.id <= memory.endMessageId
                )
          )
      )
"""

@Dao
interface SessionMemorySegmentDao {
    @Query("$VISIBLE_MEMORY_SEGMENTS_QUERY AND memory.id = :segmentId LIMIT 1")
    suspend fun getVisibleById(sessionId: Long, branchId: String, segmentId: Long): SessionMemorySegmentEntity?

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

    @Query("SELECT * FROM session_memory_segments WHERE sessionId = :sessionId AND branchId = 'main' " +
        "AND (endMessageId < :beforeEndMessageId OR (endMessageId = :beforeEndMessageId AND id < :beforeId)) " +
        "ORDER BY endMessageId DESC, id DESC LIMIT :limit")
    suspend fun getOlderMain(sessionId: Long, beforeEndMessageId: Long, beforeId: Long, limit: Int): List<SessionMemorySegmentEntity>

    @Query("$VISIBLE_MEMORY_SEGMENTS_QUERY AND (memory.endMessageId < :beforeEndMessageId " +
        "OR (memory.endMessageId = :beforeEndMessageId AND memory.id < :beforeId)) " +
        "ORDER BY memory.endMessageId DESC, memory.id DESC LIMIT :limit")
    suspend fun getOlderVisible(sessionId: Long, branchId: String, beforeEndMessageId: Long, beforeId: Long, limit: Int): List<SessionMemorySegmentEntity>

    suspend fun getOlderForBranch(sessionId: Long, branchId: String, beforeEndMessageId: Long, beforeId: Long, limit: Int): List<SessionMemorySegmentEntity> {
        require(limit in 1..100)
        return if (branchId == "main") getOlderMain(sessionId, beforeEndMessageId, beforeId, limit)
        else getOlderVisible(sessionId, branchId, beforeEndMessageId, beforeId, limit)
    }

    @Query("SELECT * FROM session_memory_segments WHERE sessionId = :sessionId AND branchId = 'main' " +
        "AND endMessageId > :afterMessageId ORDER BY startMessageId ASC, endMessageId DESC, id ASC LIMIT :limit")
    suspend fun getMainCoverageAfter(sessionId: Long, afterMessageId: Long, limit: Int): List<SessionMemorySegmentEntity>

    @Query("$VISIBLE_MEMORY_SEGMENTS_QUERY AND memory.endMessageId > :afterMessageId " +
        "ORDER BY memory.startMessageId ASC, memory.endMessageId DESC, memory.id ASC LIMIT :limit")
    suspend fun getVisibleCoverageAfter(sessionId: Long, branchId: String, afterMessageId: Long, limit: Int): List<SessionMemorySegmentEntity>

    suspend fun getCoverageAfter(sessionId: Long, branchId: String, afterMessageId: Long, limit: Int): List<SessionMemorySegmentEntity> =
        if (branchId == "main") getMainCoverageAfter(sessionId, afterMessageId, limit)
        else getVisibleCoverageAfter(sessionId, branchId, afterMessageId, limit)

    @Query("SELECT * FROM session_memory_segments WHERE sessionId = :sessionId AND branchId = 'main' " +
        "AND endMessageId <= :throughMessageId ORDER BY endMessageId DESC, id DESC LIMIT :limit")
    suspend fun getRecentMainBefore(sessionId: Long, throughMessageId: Long, limit: Int): List<SessionMemorySegmentEntity>

    @Query("$VISIBLE_MEMORY_SEGMENTS_QUERY AND memory.endMessageId <= :throughMessageId " +
        "ORDER BY memory.endMessageId DESC, memory.id DESC LIMIT :limit")
    suspend fun getRecentVisibleBefore(sessionId: Long, branchId: String, throughMessageId: Long, limit: Int): List<SessionMemorySegmentEntity>

    suspend fun getRecentBefore(sessionId: Long, branchId: String, throughMessageId: Long, limit: Int): List<SessionMemorySegmentEntity> =
        if (branchId == "main") getRecentMainBefore(sessionId, throughMessageId, limit)
        else getRecentVisibleBefore(sessionId, branchId, throughMessageId, limit)

    @Query("SELECT * FROM session_memory_segments WHERE sessionId = :sessionId AND branchId = :branchId ORDER BY segmentIndex ASC")
    suspend fun getBySessionAndBranch(sessionId: Long, branchId: String): List<SessionMemorySegmentEntity>

    @Query("SELECT * FROM session_memory_segments WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SessionMemorySegmentEntity?

    @Query(
        "SELECT COALESCE(MAX(segmentIndex), -1) + 1 FROM session_memory_segments " +
            "WHERE sessionId = :sessionId AND branchId = :branchId",
    )
    suspend fun nextSegmentIndex(sessionId: Long, branchId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SessionMemorySegmentEntity): Long

    @Insert
    suspend fun insertCompacted(entity: SessionMemorySegmentEntity): Long

    /** Updates only the summary captured by the editor. A changed row means another writer won. */
    @Query("""
        UPDATE session_memory_segments
        SET summary = :summary
        WHERE id = :id AND sessionId = :sessionId AND branchId = :branchId AND summary = :expectedSummary
    """)
    suspend fun updateSummaryIfUnchanged(
        id: Long,
        sessionId: Long,
        branchId: String,
        expectedSummary: String,
        summary: String,
    ): Int

    @Query("DELETE FROM session_memory_segments WHERE id = :id AND sessionId = :sessionId AND branchId = :branchId AND summary = :expectedSummary")
    suspend fun deleteOwnedIfUnchanged(id: Long, sessionId: Long, branchId: String, expectedSummary: String): Int

    @Query("DELETE FROM session_memory_segments WHERE sessionId = :sessionId AND branchId = :branchId AND endMessageId > :endMessageId")
    suspend fun deleteFollowing(sessionId: Long, branchId: String, endMessageId: Long): Int
}
