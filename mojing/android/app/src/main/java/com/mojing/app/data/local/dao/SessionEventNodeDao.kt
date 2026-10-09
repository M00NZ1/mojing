package com.mojing.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mojing.app.data.local.entity.BranchEventStatusEntity
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

    suspend fun getForBranch(sessionId: Long, branchId: String): List<SessionEventNodeEntity> {
        val rows = if (branchId == "main") getBySessionAndBranch(sessionId, branchId)
            else getVisibleForBranch(sessionId, branchId)
        if (branchId == "main" || rows.isEmpty()) return rows
        return applyEventStatusOverrides(rows, getAllStatusOverrides(sessionId, branchId))
    }

    @Query("SELECT * FROM session_event_nodes WHERE sessionId = :sessionId AND branchId = :branchId ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun getRecentBySessionAndBranch(
        sessionId: Long,
        branchId: String,
        limit: Int,
    ): List<SessionEventNodeEntity>

    @Query("""
        SELECT * FROM session_event_nodes
        WHERE sessionId = :sessionId
          AND branchId = :branchId
          AND (createdAt < :beforeCreatedAt OR (createdAt = :beforeCreatedAt AND id < :beforeId))
        ORDER BY createdAt DESC, id DESC
        LIMIT :limit
    """)
    suspend fun getOlderBySessionAndBranch(
        sessionId: Long,
        branchId: String,
        beforeCreatedAt: Long,
        beforeId: Long,
        limit: Int,
    ): List<SessionEventNodeEntity>

    @Query("$VISIBLE_EVENT_NODES_QUERY ORDER BY event.createdAt DESC, event.id DESC LIMIT :limit")
    suspend fun getRecentVisibleForBranch(
        sessionId: Long,
        branchId: String,
        limit: Int,
    ): List<SessionEventNodeEntity>

    @Query("""
        $VISIBLE_EVENT_NODES_QUERY
          AND (event.createdAt < :beforeCreatedAt OR (event.createdAt = :beforeCreatedAt AND event.id < :beforeId))
        ORDER BY event.createdAt DESC, event.id DESC
        LIMIT :limit
    """)
    suspend fun getOlderVisibleForBranch(
        sessionId: Long,
        branchId: String,
        beforeCreatedAt: Long,
        beforeId: Long,
        limit: Int,
    ): List<SessionEventNodeEntity>

    @Query("""
        SELECT event.* FROM session_event_nodes AS event
        WHERE event.sessionId = :sessionId
          AND event.branchId = 'main'
          AND (:query = '' OR instr(lower(event.title), lower(:query)) > 0
               OR instr(lower(event.description), lower(:query)) > 0)
          AND (:resolved IS NULL OR event.resolved = :resolved)
          AND (:beforeCreatedAt IS NULL OR
               event.createdAt < :beforeCreatedAt OR
               (event.createdAt = :beforeCreatedAt AND event.id < :beforeId))
        ORDER BY event.createdAt DESC, event.id DESC
        LIMIT :limit
    """)
    suspend fun getFilteredMainPage(
        sessionId: Long,
        query: String,
        resolved: Boolean?,
        beforeCreatedAt: Long?,
        beforeId: Long?,
        limit: Int,
    ): List<SessionEventNodeEntity>

    @Query("""
        $VISIBLE_EVENT_NODES_QUERY
          AND (:query = '' OR instr(lower(event.title), lower(:query)) > 0
               OR instr(lower(event.description), lower(:query)) > 0)
          AND (:resolved IS NULL OR COALESCE(
                (SELECT status.resolved FROM branch_event_status AS status
                 WHERE status.sessionId = event.sessionId AND status.branchId = :branchId
                   AND status.eventId = event.id LIMIT 1), event.resolved) = :resolved)
          AND (:beforeCreatedAt IS NULL OR
               event.createdAt < :beforeCreatedAt OR
               (event.createdAt = :beforeCreatedAt AND event.id < :beforeId))
        ORDER BY event.createdAt DESC, event.id DESC
        LIMIT :limit
    """)
    suspend fun getFilteredVisiblePage(
        sessionId: Long,
        branchId: String,
        query: String,
        resolved: Boolean?,
        beforeCreatedAt: Long?,
        beforeId: Long?,
        limit: Int,
    ): List<SessionEventNodeEntity>

    suspend fun getFilteredPageForBranch(
        sessionId: Long,
        branchId: String,
        query: String = "",
        resolved: Boolean? = null,
        beforeCreatedAt: Long? = null,
        beforeId: Long? = null,
        limit: Int,
    ): List<SessionEventNodeEntity> {
        require(limit >= 0)
        require((beforeCreatedAt == null) == (beforeId == null))
        if (limit == 0) return emptyList()
        val rows = if (branchId == "main") {
            getFilteredMainPage(sessionId, query, resolved, beforeCreatedAt, beforeId, limit)
        } else {
            getFilteredVisiblePage(sessionId, branchId, query, resolved, beforeCreatedAt, beforeId, limit)
        }
        if (branchId == "main" || rows.isEmpty()) return rows
        return applyEventStatusOverrides(rows, getStatusOverrides(sessionId, branchId, rows.map { it.id }))
    }

    suspend fun getPageForBranch(
        sessionId: Long,
        branchId: String,
        beforeCreatedAt: Long? = null,
        beforeId: Long? = null,
        limit: Int,
    ): List<SessionEventNodeEntity> {
        val rows = when {
            branchId == "main" && beforeCreatedAt == null ->
                getRecentBySessionAndBranch(sessionId, branchId, limit)
            branchId == "main" ->
                getOlderBySessionAndBranch(
                    sessionId,
                    branchId,
                    requireNotNull(beforeCreatedAt),
                    requireNotNull(beforeId),
                    limit,
                )
            beforeCreatedAt == null ->
                getRecentVisibleForBranch(sessionId, branchId, limit)
            else ->
                getOlderVisibleForBranch(
                    sessionId,
                    branchId,
                    requireNotNull(beforeCreatedAt),
                    requireNotNull(beforeId),
                    limit,
                )
        }
        if (branchId == "main" || rows.isEmpty()) return rows
        return applyEventStatusOverrides(rows, getStatusOverrides(sessionId, branchId, rows.map { it.id }))
    }

    @Query("SELECT * FROM branch_event_status WHERE sessionId = :sessionId AND branchId = :branchId AND eventId IN (:eventIds)")
    suspend fun getStatusOverrides(sessionId: Long, branchId: String, eventIds: List<Long>): List<BranchEventStatusEntity>

    @Query("SELECT * FROM branch_event_status WHERE sessionId = :sessionId AND branchId = :branchId")
    suspend fun getAllStatusOverrides(sessionId: Long, branchId: String): List<BranchEventStatusEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStatusOverride(entity: BranchEventStatusEntity)

    @Query("UPDATE session_event_nodes SET resolved = NOT resolved WHERE id = :id")
    suspend fun toggleResolved(id: Long)

    @Query("UPDATE session_event_nodes SET resolved = :resolved WHERE id = :id")
    suspend fun setResolved(id: Long, resolved: Boolean)

    @Query("DELETE FROM session_event_nodes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SessionEventNodeEntity): Long
}

internal fun applyEventStatusOverrides(
    rows: List<SessionEventNodeEntity>, overrides: List<BranchEventStatusEntity>,
): List<SessionEventNodeEntity> {
    val byEventId = overrides.associateBy { it.eventId }
    return rows.map { row ->
        byEventId[row.id]?.let { row.copy(resolved = it.resolved) } ?: row
    }
}
