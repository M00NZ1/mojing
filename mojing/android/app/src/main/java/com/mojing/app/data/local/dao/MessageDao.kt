package com.mojing.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.mojing.app.data.local.entity.BranchSwipeSelectionEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.MessageSearchIndexStateEntity
import com.mojing.app.data.local.entity.SessionBranchEntity
import com.mojing.app.data.local.entity.SessionContextMemoryEntity
import com.mojing.app.data.local.entity.SessionEventNodeEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.mojing.app.data.local.search.MessageSearchTokenizer

data class MessageSearchRebuildBatchResult(
    val indexedThroughMessageId: Long,
    val indexedCount: Int,
    val isComplete: Boolean,
)

data class MessageRecallResult(
    val deleted: Boolean,
    val deletedMessageIds: List<Long> = emptyList(),
    val attachmentStoragePaths: List<String> = emptyList(),
    val fallbackSwipeMessageId: Long? = null,
)

/** 目录专用轻量投影：不把长篇正文读入目录列表。 */
data class StoryContentsMessageProjection(
    val id: Long,
    val speakerType: String,
    val branchId: String,
    val createdAt: Long,
    val structuredContentJson: String,
    val contentPreview: String,
)

private const val CURRENT_MESSAGES_QUERY = """
    SELECT message.*
    FROM branch_visibility_segments AS segment
    CROSS JOIN messages AS message
    WHERE segment.sessionId = :sessionId
      AND segment.targetBranchId = :branchId
      AND message.sessionId = segment.sessionId
      AND message.branchId = segment.sourceBranchId
      AND message.id <= segment.maxMessageId
      AND NOT EXISTS (
          SELECT 1
          FROM messages AS replacement
          JOIN branch_visibility_segments AS replacement_segment
            ON replacement_segment.sessionId = replacement.sessionId
           AND replacement_segment.targetBranchId = :branchId
           AND replacement_segment.sourceBranchId = replacement.branchId
           AND replacement.id <= replacement_segment.maxMessageId
          WHERE replacement.sessionId = :sessionId
            AND replacement.regeneratedFromMessageId = message.id
            AND replacement.branchId <> message.branchId
      )
"""

private const val MAIN_CONTEXT_MESSAGES_QUERY = """
    SELECT message.*
    FROM messages AS message
    WHERE message.sessionId = :sessionId
      AND message.branchId = 'main'
      AND (
          (message.swipeGroupId IS NULL AND message.includeInContext = 1)
          OR (
              message.swipeGroupId IS NOT NULL
              AND message.id = COALESCE(
                  (
                      SELECT selection.selectedMessageId
                      FROM branch_swipe_selections AS selection
                      WHERE selection.sessionId = :sessionId
                        AND selection.branchId = 'main'
                        AND selection.swipeGroupId = message.swipeGroupId
                      LIMIT 1
                  ),
                  (
                      SELECT active.id
                      FROM messages AS active
                      WHERE active.sessionId = :sessionId
                        AND active.branchId = 'main'
                        AND active.swipeGroupId = message.swipeGroupId
                        AND active.includeInContext = 1
                      ORDER BY active.createdAt DESC, active.id DESC
                      LIMIT 1
                  ),
                  (
                      SELECT fallback.id
                      FROM messages AS fallback
                      WHERE fallback.sessionId = :sessionId
                        AND fallback.branchId = 'main'
                        AND fallback.swipeGroupId = message.swipeGroupId
                      ORDER BY fallback.createdAt DESC, fallback.id DESC
                      LIMIT 1
                  ),
                  -1
              )
          )
      )
"""

private const val VISIBLE_CONTEXT_MESSAGES_QUERY = CURRENT_MESSAGES_QUERY + """
      AND (
          (message.swipeGroupId IS NULL AND message.includeInContext = 1)
          OR (
              message.swipeGroupId IS NOT NULL
              AND message.id = COALESCE(
                  (
                      SELECT selection.selectedMessageId
                      FROM branch_swipe_selections AS selection
                      WHERE selection.sessionId = :sessionId
                        AND selection.branchId = :branchId
                        AND selection.swipeGroupId = message.swipeGroupId
                      LIMIT 1
                  ),
                  (
                      SELECT candidate.id
                      FROM branch_visibility_segments AS candidate_segment
                      JOIN messages AS candidate
                        ON candidate.sessionId = candidate_segment.sessionId
                       AND candidate.branchId = candidate_segment.sourceBranchId
                       AND candidate.id <= candidate_segment.maxMessageId
                      WHERE candidate_segment.sessionId = :sessionId
                        AND candidate_segment.targetBranchId = :branchId
                        AND candidate.swipeGroupId = message.swipeGroupId
                        AND NOT EXISTS (
                            SELECT 1
                            FROM messages AS candidate_replacement
                            JOIN branch_visibility_segments AS candidate_replacement_segment
                              ON candidate_replacement_segment.sessionId = candidate_replacement.sessionId
                             AND candidate_replacement_segment.targetBranchId = :branchId
                             AND candidate_replacement_segment.sourceBranchId = candidate_replacement.branchId
                             AND candidate_replacement.id <= candidate_replacement_segment.maxMessageId
                            WHERE candidate_replacement.sessionId = :sessionId
                              AND candidate_replacement.regeneratedFromMessageId = candidate.id
                              AND candidate_replacement.branchId <> candidate.branchId
                        )
                      ORDER BY candidate.includeInContext DESC, candidate.createdAt DESC, candidate.id DESC
                      LIMIT 1
                  ),
                  -1
              )
          )
      )
"""

@Dao
interface MessageDao {
    @Query("SELECT id, speakerType, branchId, createdAt, structuredContentJson, substr(content, 1, 180) AS contentPreview " +
        "FROM ($MAIN_CONTEXT_MESSAGES_QUERY) WHERE speakerType IN ('narrator', 'character') " +
        "AND id < :beforeMessageId ORDER BY id DESC LIMIT :limit")
    suspend fun getMainStoryContentsBefore(sessionId: Long, beforeMessageId: Long, limit: Int): List<StoryContentsMessageProjection>

    @Query("SELECT id, speakerType, branchId, createdAt, structuredContentJson, substr(content, 1, 180) AS contentPreview " +
        "FROM ($VISIBLE_CONTEXT_MESSAGES_QUERY) WHERE speakerType IN ('narrator', 'character') " +
        "AND id < :beforeMessageId ORDER BY id DESC LIMIT :limit")
    suspend fun getBranchStoryContentsBefore(sessionId: Long, branchId: String, beforeMessageId: Long, limit: Int): List<StoryContentsMessageProjection>

    suspend fun getVisibleStoryContentsBefore(sessionId: Long, branchId: String, beforeMessageId: Long, limit: Int): List<StoryContentsMessageProjection> =
        if (branchId == "main") getMainStoryContentsBefore(sessionId, beforeMessageId, limit)
        else getBranchStoryContentsBefore(sessionId, branchId, beforeMessageId, limit)

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId AND branchId = 'main' ORDER BY createdAt ASC")
    suspend fun getMainBranchMessages(sessionId: Long): List<MessageEntity>

    @Query("SELECT COALESCE(MAX(id), 0) FROM messages WHERE sessionId = :sessionId AND branchId = 'main'")
    suspend fun getMainBranchMaxMessageId(sessionId: Long): Long

    @Query(
        "SELECT * FROM messages WHERE sessionId = :sessionId AND branchId = 'main' " +
            "AND id > :afterMessageId AND id <= :maxMessageId ORDER BY id ASC LIMIT :limit",
    )
    suspend fun getMainMessagesForExport(
        sessionId: Long,
        afterMessageId: Long,
        maxMessageId: Long,
        limit: Int,
    ): List<MessageEntity>

    /** 主线模型历史：显式覆盖优先，未覆盖的旧组继续使用冻结的 includeInContext 默认。 */
    @Query("$MAIN_CONTEXT_MESSAGES_QUERY ORDER BY message.createdAt ASC, message.id ASC")
    suspend fun getMainBranchContextMessages(sessionId: Long): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): MessageEntity?

    @Query("SELECT * FROM messages WHERE id = :id AND sessionId = :sessionId LIMIT 1")
    suspend fun getByIdInSession(id: Long, sessionId: Long): MessageEntity?

    @Query(
        "SELECT * FROM messages WHERE sessionId = :sessionId " +
            "AND parentMessageId = :parentMessageId AND includeInContext = 0 ORDER BY id ASC",
    )
    suspend fun getDerivedChildrenInSession(
        sessionId: Long,
        parentMessageId: Long,
    ): List<MessageEntity>

    @Query(
        "SELECT DISTINCT storagePath FROM message_attachments " +
            "WHERE messageId IN (:messageIds) AND storagePath <> ''",
    )
    suspend fun getAttachmentStoragePaths(messageIds: List<Long>): List<String>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId AND id IN (:messageIds)")
    suspend fun getByIdsInSession(sessionId: Long, messageIds: List<Long>): List<MessageEntity>

    @Query("$MAIN_CONTEXT_MESSAGES_QUERY AND message.id IN (:messageIds)")
    suspend fun getMainEventSources(sessionId: Long, messageIds: List<Long>): List<MessageEntity>

    @Query("$VISIBLE_CONTEXT_MESSAGES_QUERY AND message.id IN (:messageIds)")
    suspend fun getVisibleEventSources(sessionId: Long, branchId: String, messageIds: List<Long>): List<MessageEntity>

    @Query("SELECT id FROM session_event_nodes WHERE sessionId = :sessionId AND branchId = :branchId AND characterId IS :characterId AND messageId = :messageId AND title = :title LIMIT 1")
    suspend fun findEquivalentEvent(sessionId: Long, branchId: String, characterId: Long?, messageId: Long, title: String): Long?

    @Insert
    suspend fun insertDerivedEvent(entity: SessionEventNodeEntity): Long

    /** 来源复核、去重与整批写入共用 Room 事务；拒绝晚到或已失效的模型结果。 */
    @Transaction
    suspend fun commitDerivedEvents(sessionId: Long, branchId: String, sources: List<MessageEntity>, events: List<SessionEventNodeEntity>): List<SessionEventNodeEntity> {
        require(sources.size in 1..20 && sources.all { it.sessionId == sessionId && it.id > 0 })
        require(events.size <= 5)
        val ids = sources.map { it.id }
        require(ids.distinct().size == ids.size)
        require(events.all { it.id == 0L && it.sessionId == sessionId && it.branchId == branchId && it.messageId in ids && it.title.isNotBlank() && it.importance in 1..5 })
        val current = if (branchId == "main") getMainEventSources(sessionId, ids) else getVisibleEventSources(sessionId, branchId, ids)
        fun versions(rows: List<MessageEntity>) = rows.associate { it.id to listOf(it.content, it.structuredContentJson, it.speakerType, it.characterId, it.branchId) }
        if (versions(current) != versions(sources)) return emptyList()
        return events.map { event ->
            currentCoroutineContext().ensureActive()
            val existingId = findEquivalentEvent(sessionId, branchId, event.characterId, requireNotNull(event.messageId), event.title)
            event.copy(id = existingId ?: insertDerivedEvent(event))
        }
    }

    @Query("UPDATE messages SET swipeGroupId = :gid WHERE id = :id")
    suspend fun updateSwipeGroupId(id: Long, gid: String)

    @Query(
        "SELECT * FROM messages WHERE sessionId = :sessionId AND swipeGroupId = :gid " +
            "ORDER BY id ASC",
    )
    suspend fun getSwipeGroupMessages(sessionId: Long, gid: String): List<MessageEntity>

    @Query(
        "SELECT * FROM messages WHERE sessionId = :sessionId AND branchId = 'main' " +
            "AND swipeGroupId = :gid ORDER BY createdAt ASC, id ASC",
    )
    suspend fun getMainSwipeGroupMessages(sessionId: Long, gid: String): List<MessageEntity>

    @Query(
        "$CURRENT_MESSAGES_QUERY AND message.swipeGroupId = :gid " +
            "ORDER BY message.createdAt ASC, message.id ASC",
    )
    suspend fun getVisibleSwipeGroupMessages(
        sessionId: Long,
        branchId: String,
        gid: String,
    ): List<MessageEntity>

    @Query("SELECT branchId FROM session_branches WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    suspend fun getSessionBranchIdsForSwipeRepair(sessionId: Long): List<String>

    @Query(
        "SELECT segment.targetBranchId FROM branch_visibility_segments AS segment " +
            "WHERE segment.sessionId = :sessionId AND segment.sourceBranchId = :sourceBranchId " +
            "AND segment.maxMessageId >= :messageId AND NOT EXISTS (" +
            "SELECT 1 FROM messages AS replacement " +
            "JOIN branch_visibility_segments AS replacement_segment " +
            "ON replacement_segment.sessionId = replacement.sessionId " +
            "AND replacement_segment.targetBranchId = segment.targetBranchId " +
            "AND replacement_segment.sourceBranchId = replacement.branchId " +
            "AND replacement.id <= replacement_segment.maxMessageId " +
            "WHERE replacement.sessionId = :sessionId " +
            "AND replacement.regeneratedFromMessageId = :messageId " +
            "AND replacement.branchId <> :sourceBranchId)",
    )
    suspend fun getStorylinesSeeingSourceMessage(
        sessionId: Long,
        sourceBranchId: String,
        messageId: Long,
    ): List<String>

    @Query(
        "SELECT * FROM branch_swipe_selections WHERE sessionId = :sessionId " +
            "AND branchId = :branchId AND swipeGroupId = :gid LIMIT 1",
    )
    suspend fun getBranchSwipeSelection(
        sessionId: Long,
        branchId: String,
        gid: String,
    ): BranchSwipeSelectionEntity?

    @Query(
        "SELECT * FROM branch_swipe_selections WHERE sessionId = :sessionId " +
            "AND branchId = :branchId AND swipeGroupId IN (:groupIds)",
    )
    suspend fun getBranchSwipeSelectionsForGroups(
        sessionId: Long,
        branchId: String,
        groupIds: List<String>,
    ): List<BranchSwipeSelectionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBranchSwipeSelectionRaw(entity: BranchSwipeSelectionEntity): Long

    @Query(
        "DELETE FROM branch_swipe_selections WHERE sessionId = :sessionId " +
            "AND branchId = :branchId AND swipeGroupId = :gid",
    )
    suspend fun deleteBranchSwipeSelection(sessionId: Long, branchId: String, gid: String): Int

    /** 分支级采用状态的唯一写入口；不再改写作为旧库默认值的消息字段。 */
    @Transaction
    suspend fun selectSwipeVariantForBranch(
        sessionId: Long,
        branchId: String,
        gid: String,
        messageId: Long,
    ): Int {
        if (branchId.isBlank() || gid.isBlank()) return 0
        val visibleVariants = visibleSwipeVariants(sessionId, branchId, gid)
        if (visibleVariants.none { it.id == messageId }) return 0
        val previousId = effectiveSwipeSelection(sessionId, branchId, gid, visibleVariants)
        if (previousId == messageId) return 1
        upsertBranchSwipeSelectionRaw(
            BranchSwipeSelectionEntity(
                sessionId = sessionId,
                branchId = branchId,
                swipeGroupId = gid,
                selectedMessageId = messageId,
            ),
        )
        deleteMemorySegmentTail(sessionId, branchId, minOf(previousId ?: messageId, messageId))
        invalidateContextMemoryForBranch(sessionId, branchId, System.currentTimeMillis())
        return 1
    }

    @Query("""
        SELECT * FROM messages
        WHERE sessionId = :sessionId
          AND (branchId = 'main' AND id <= :sourceMsgId OR branchId = :branchId)
        ORDER BY createdAt ASC, id ASC
    """)
    suspend fun getVisibleMessages(sessionId: Long, branchId: String, sourceMsgId: Long): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId AND branchId = :branchId ORDER BY createdAt ASC, id ASC")
    suspend fun getBranchMessages(sessionId: Long, branchId: String): List<MessageEntity>

    @Query(
        "SELECT * FROM messages WHERE sessionId = :sessionId AND branchId = 'main' " +
            "ORDER BY id DESC LIMIT :limit",
    )
    suspend fun getMainMessagesTail(sessionId: Long, limit: Int): List<MessageEntity>

    @Query(
        "SELECT * FROM messages WHERE sessionId = :sessionId AND branchId = 'main' " +
            "AND id < :beforeMessageId ORDER BY id DESC LIMIT :limit",
    )
    suspend fun getMainMessagesBefore(
        sessionId: Long,
        beforeMessageId: Long,
        limit: Int,
    ): List<MessageEntity>

    @Query(
        "SELECT * FROM messages WHERE sessionId = :sessionId AND branchId = 'main' " +
            "AND id > :afterMessageId ORDER BY id ASC LIMIT :limit",
    )
    suspend fun getMainMessagesAfter(
        sessionId: Long,
        afterMessageId: Long,
        limit: Int,
    ): List<MessageEntity>

    @Query(
        "SELECT * FROM messages WHERE sessionId = :sessionId AND branchId = 'main' " +
            "AND id = :messageId LIMIT 1",
    )
    suspend fun getMainMessageById(sessionId: Long, messageId: Long): MessageEntity?

    @Query("$MAIN_CONTEXT_MESSAGES_QUERY ORDER BY message.id DESC LIMIT :limit")
    suspend fun getMainContextTail(sessionId: Long, limit: Int): List<MessageEntity>

    @Query(
        "$MAIN_CONTEXT_MESSAGES_QUERY AND message.id > :afterMessageId " +
            "AND message.speakerType IN ('user', 'character', 'narrator') " +
            "ORDER BY message.id ASC LIMIT :limit",
    )
    suspend fun getMainStoryContextAfter(
        sessionId: Long,
        afterMessageId: Long,
        limit: Int,
    ): List<MessageEntity>

    @Query(
        "SELECT * FROM messages WHERE sessionId = :sessionId AND branchId = 'main' " +
            "AND speakerType = 'character' AND characterId IS NOT NULL ORDER BY id DESC LIMIT 1",
    )
    suspend fun getLatestMainCharacterMessage(sessionId: Long): MessageEntity?

    @Query(
        "SELECT * FROM messages AS message " +
            "WHERE message.sessionId = :sessionId AND message.branchId = 'main' " +
            "AND (message.id IN (" +
            "SELECT rowid FROM message_search_fts WHERE message_search_fts MATCH :matchExpression" +
            ") OR (:indexComplete = 0 AND message.id > :indexedThroughMessageId)) " +
            "AND ((:exactMatch = 1 AND ((message.searchNormalized <> '' " +
            "AND message.searchNormalized = :normalizedQuery) OR " +
            "(message.searchNormalized = '' AND message.content = :query))) OR " +
            "(:exactMatch = 0 AND ((message.searchNormalized <> '' " +
            "AND instr(message.searchNormalized, :normalizedQuery) > 0) OR " +
            "(message.searchNormalized = '' AND instr(lower(message.content), lower(:query)) > 0)))) " +
            "AND message.id < :beforeMessageId ORDER BY message.id DESC LIMIT :limit",
    )
    suspend fun searchMainMessagesIndexed(
        sessionId: Long,
        query: String,
        normalizedQuery: String,
        matchExpression: String,
        exactMatch: Int,
        indexedThroughMessageId: Long,
        indexComplete: Int,
        limit: Int,
        beforeMessageId: Long = Long.MAX_VALUE,
    ): List<MessageEntity>

    suspend fun searchMainMessages(
        sessionId: Long,
        query: String,
        exactMatch: Int,
        limit: Int,
        beforeMessageId: Long = Long.MAX_VALUE,
    ): List<MessageEntity> {
        val state = currentSearchIndexState()
        return searchMainMessagesIndexed(
            sessionId = sessionId,
            query = query,
            normalizedQuery = MessageSearchTokenizer.normalize(query),
            matchExpression = MessageSearchTokenizer.matchExpression(sessionId, query),
            exactMatch = exactMatch,
            indexedThroughMessageId = state.indexedThroughMessageId,
            indexComplete = if (state.isComplete) 1 else 0,
            limit = limit,
            beforeMessageId = beforeMessageId,
        )
    }

    /** 搜索页展示真实命中消息数，不以当前分页大小冒充总数。 */
    @Query(
        "SELECT COUNT(*) FROM messages AS message WHERE message.sessionId = :sessionId AND message.branchId = 'main' " +
            "AND (message.id IN (SELECT rowid FROM message_search_fts WHERE message_search_fts MATCH :matchExpression) " +
            "OR (:indexComplete = 0 AND message.id > :indexedThroughMessageId)) " +
            "AND ((:exactMatch = 1 AND ((message.searchNormalized <> '' AND message.searchNormalized = :normalizedQuery) OR (message.searchNormalized = '' AND message.content = :query))) " +
            "OR (:exactMatch = 0 AND ((message.searchNormalized <> '' AND instr(message.searchNormalized, :normalizedQuery) > 0) OR (message.searchNormalized = '' AND instr(lower(message.content), lower(:query)) > 0))))",
    )
    suspend fun countMainMessagesIndexed(sessionId: Long, query: String, normalizedQuery: String, matchExpression: String, exactMatch: Int, indexedThroughMessageId: Long, indexComplete: Int): Int

    suspend fun countMainMessages(sessionId: Long, query: String, exactMatch: Int): Int {
        val state = currentSearchIndexState()
        return countMainMessagesIndexed(sessionId, query, MessageSearchTokenizer.normalize(query), MessageSearchTokenizer.matchExpression(sessionId, query), exactMatch, state.indexedThroughMessageId, if (state.isComplete) 1 else 0)
    }

    /** 当前故事线最后一页。返回倒序，调用方只反转本页，不读取整段历史。 */
    @Query("$CURRENT_MESSAGES_QUERY ORDER BY message.id DESC LIMIT :limit")
    suspend fun getVisibleMessagesTail(
        sessionId: Long,
        branchId: String,
        limit: Int,
    ): List<MessageEntity>

    /** 以稳定主键向更早方向翻页。返回倒序。 */
    @Query(
        "$CURRENT_MESSAGES_QUERY AND message.id < :beforeMessageId " +
            "ORDER BY message.id DESC LIMIT :limit",
    )
    suspend fun getVisibleMessagesBefore(
        sessionId: Long,
        branchId: String,
        beforeMessageId: Long,
        limit: Int,
    ): List<MessageEntity>

    /** 以稳定主键向较新方向翻页。返回正序。 */
    @Query(
        "$CURRENT_MESSAGES_QUERY AND message.id > :afterMessageId " +
            "ORDER BY message.id ASC LIMIT :limit",
    )
    suspend fun getVisibleMessagesAfter(
        sessionId: Long,
        branchId: String,
        afterMessageId: Long,
        limit: Int,
    ): List<MessageEntity>

    @Query(
        "$CURRENT_MESSAGES_QUERY AND message.id = :messageId LIMIT 1",
    )
    suspend fun getVisibleMessageById(
        sessionId: Long,
        branchId: String,
        messageId: Long,
    ): MessageEntity?

    /** 模型热路径按当前故事线有效选择读取近期上下文；原始消息仍完整保存在 Room。返回倒序。 */
    @Query("$VISIBLE_CONTEXT_MESSAGES_QUERY ORDER BY message.id DESC LIMIT :limit")
    suspend fun getVisibleContextTail(
        sessionId: Long,
        branchId: String,
        limit: Int,
    ): List<MessageEntity>

    @Query(
        "$VISIBLE_CONTEXT_MESSAGES_QUERY AND message.id > :afterMessageId " +
            "AND message.speakerType IN ('user', 'character', 'narrator') " +
            "ORDER BY message.id ASC LIMIT :limit",
    )
    suspend fun getVisibleStoryContextAfter(
        sessionId: Long,
        branchId: String,
        afterMessageId: Long,
        limit: Int,
    ): List<MessageEntity>

    /** 自动摘要专用 keyset 读取；先投影当前故事线有效版本，再应用游标和上限。 */
    suspend fun getNextStoryContextBatch(
        sessionId: Long,
        branchId: String,
        afterMessageId: Long,
        limit: Int,
    ): List<MessageEntity> {
        require(branchId.isNotBlank())
        require(afterMessageId >= 0L)
        require(limit in 1..2000)
        return if (branchId == "main") {
            getMainStoryContextAfter(sessionId, afterMessageId, limit)
        } else {
            getVisibleStoryContextAfter(sessionId, branchId, afterMessageId, limit)
        }
    }

    @Query(
        "$CURRENT_MESSAGES_QUERY AND message.speakerType = 'character' " +
            "AND message.characterId IS NOT NULL ORDER BY message.id DESC LIMIT 1",
    )
    suspend fun getLatestVisibleCharacterMessage(
        sessionId: Long,
        branchId: String,
    ): MessageEntity?

    /** 搜索结果有硬上限；点击结果后再按 id 读取目标附近窗口。 */
    @Query(
        "$CURRENT_MESSAGES_QUERY AND (message.id IN (" +
            "SELECT rowid FROM message_search_fts WHERE message_search_fts MATCH :matchExpression" +
            ") OR (:indexComplete = 0 AND message.id > :indexedThroughMessageId)) " +
            "AND ((:exactMatch = 1 AND ((message.searchNormalized <> '' " +
            "AND message.searchNormalized = :normalizedQuery) OR " +
            "(message.searchNormalized = '' AND message.content = :query))) OR " +
            "(:exactMatch = 0 AND ((message.searchNormalized <> '' " +
            "AND instr(message.searchNormalized, :normalizedQuery) > 0) OR " +
            "(message.searchNormalized = '' AND instr(lower(message.content), lower(:query)) > 0)))) " +
            "AND message.id < :beforeMessageId ORDER BY message.id DESC LIMIT :limit",
    )
    suspend fun searchVisibleMessagesIndexed(
        sessionId: Long,
        branchId: String,
        query: String,
        normalizedQuery: String,
        matchExpression: String,
        exactMatch: Int,
        indexedThroughMessageId: Long,
        indexComplete: Int,
        limit: Int,
        beforeMessageId: Long = Long.MAX_VALUE,
    ): List<MessageEntity>

    suspend fun searchVisibleMessages(
        sessionId: Long,
        branchId: String,
        query: String,
        exactMatch: Int,
        limit: Int,
        beforeMessageId: Long = Long.MAX_VALUE,
    ): List<MessageEntity> {
        val state = currentSearchIndexState()
        return searchVisibleMessagesIndexed(
            sessionId = sessionId,
            branchId = branchId,
            query = query,
            normalizedQuery = MessageSearchTokenizer.normalize(query),
            matchExpression = MessageSearchTokenizer.matchExpression(sessionId, query),
            exactMatch = exactMatch,
            indexedThroughMessageId = state.indexedThroughMessageId,
            indexComplete = if (state.isComplete) 1 else 0,
            limit = limit,
            beforeMessageId = beforeMessageId,
        )
    }

    @Query(
        "SELECT COUNT(*) FROM ($CURRENT_MESSAGES_QUERY) AS message WHERE (message.id IN (SELECT rowid FROM message_search_fts WHERE message_search_fts MATCH :matchExpression) " +
            "OR (:indexComplete = 0 AND message.id > :indexedThroughMessageId)) " +
            "AND ((:exactMatch = 1 AND ((message.searchNormalized <> '' AND message.searchNormalized = :normalizedQuery) OR (message.searchNormalized = '' AND message.content = :query))) " +
            "OR (:exactMatch = 0 AND ((message.searchNormalized <> '' AND instr(message.searchNormalized, :normalizedQuery) > 0) OR (message.searchNormalized = '' AND instr(lower(message.content), lower(:query)) > 0))))",
    )
    suspend fun countVisibleMessagesIndexed(sessionId: Long, branchId: String, query: String, normalizedQuery: String, matchExpression: String, exactMatch: Int, indexedThroughMessageId: Long, indexComplete: Int): Int

    suspend fun countVisibleMessages(sessionId: Long, branchId: String, query: String, exactMatch: Int): Int {
        val state = currentSearchIndexState()
        return countVisibleMessagesIndexed(sessionId, branchId, query, MessageSearchTokenizer.normalize(query), MessageSearchTokenizer.matchExpression(sessionId, query), exactMatch, state.indexedThroughMessageId, if (state.isComplete) 1 else 0)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRaw(entity: MessageEntity): Long

    @Transaction
    suspend fun insert(entity: MessageEntity): Long =
        insertRaw(MessageSearchTokenizer.index(entity))

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllRaw(entities: List<MessageEntity>): List<Long>

    @Transaction
    suspend fun insertAll(entities: List<MessageEntity>): List<Long> =
        insertAllRaw(entities.map(MessageSearchTokenizer::index))

    @Query("""
        SELECT COUNT(*) FROM messages
        WHERE sessionId = :sessionId
          AND branchId = :branchId
          AND instr(structuredContentJson, :batchMarker) > 0
    """)
    suspend fun countImportBatch(sessionId: Long, branchId: String, batchMarker: String): Int

    @Query("""
        SELECT COUNT(*) FROM messages
        WHERE sessionId = :sessionId
          AND speakerType = 'user'
          AND instr(structuredContentJson, :submissionMarker) > 0
    """)
    suspend fun countDraftSubmission(sessionId: Long, submissionMarker: String): Int

    /** 查重与整批写入共享同一事务；任何一条失败时整批回滚。 */
    @Transaction
    suspend fun insertImportBatchIfAbsent(
        sessionId: Long,
        branchId: String,
        batchMarker: String,
        entities: List<MessageEntity>,
    ): Int {
        require(entities.isNotEmpty())
        require(entities.all { entity ->
            entity.id == 0L &&
                entity.sessionId == sessionId &&
                entity.branchId == branchId &&
                entity.content.isNotBlank() &&
                entity.structuredContentJson.contains(batchMarker)
        })
        val existingCount = countImportBatch(sessionId, branchId, batchMarker)
        if (existingCount == entities.size) return 0
        check(existingCount == 0) { "导入批次状态不完整，请先检查当前故事线" }
        val insertedIds = insertAll(entities)
        check(insertedIds.size == entities.size)
        return insertedIds.size
    }

    /** 组绑定、新版本写入与当前故事线采用必须原子完成；远程失败前数据库保持不变。 */
    @Transaction
    suspend fun insertAndSelectSwipeVariant(
        entity: MessageEntity,
        branchId: String,
        targetMessageId: Long,
    ): Long {
        val groupId = requireNotNull(entity.swipeGroupId?.takeIf { it.isNotBlank() })
        require(entity.content.isNotBlank())
        require(branchId.isNotBlank() && entity.branchId == branchId)
        val target = requireNotNull(getByIdInSession(targetMessageId, entity.sessionId)) {
            "原回复已不存在"
        }
        val existingGroupId = target.swipeGroupId?.takeIf { it.isNotBlank() }
        require(existingGroupId == null || existingGroupId == groupId) { "回复版本组已变化" }
        if (existingGroupId == null) updateSwipeGroupId(targetMessageId, groupId)
        val visibleVariants = visibleSwipeVariants(entity.sessionId, branchId, groupId)
        check(visibleVariants.any { it.id == targetMessageId }) {
            "原回复已不属于当前故事线"
        }
        val previousId = effectiveSwipeSelection(entity.sessionId, branchId, groupId, visibleVariants)
        val id = insert(entity.copy(includeInContext = false))
        upsertBranchSwipeSelectionRaw(
            BranchSwipeSelectionEntity(
                sessionId = entity.sessionId,
                branchId = branchId,
                swipeGroupId = groupId,
                selectedMessageId = id,
            ),
        )
        deleteMemorySegmentTail(entity.sessionId, branchId, minOf(previousId ?: targetMessageId, id))
        invalidateContextMemoryForBranch(entity.sessionId, branchId, System.currentTimeMillis())
        return id
    }

    @Query(
        "UPDATE messages SET content = :content, searchNormalized = :searchNormalized, " +
            "searchTerms = :searchTerms WHERE id = :id",
    )
    suspend fun updateContentRaw(
        id: Long,
        content: String,
        searchNormalized: String,
        searchTerms: String,
    ): Int

    @Transaction
    suspend fun updateContent(id: Long, content: String) {
        val current = getById(id) ?: return
        if (current.content == content) return
        val affectedBranches = contextMemoryBranchesAffectedBy(current)
        val indexed = MessageSearchTokenizer.index(current.copy(content = content))
        check(updateContentRaw(
            id = id,
            content = content,
            searchNormalized = indexed.searchNormalized,
            searchTerms = indexed.searchTerms,
        ) == 1) { "消息已不存在" }
        affectedBranches.forEach { branchId ->
            deleteMemorySegmentTail(current.sessionId, branchId, id)
            invalidateContextMemoryForBranch(current.sessionId, branchId, System.currentTimeMillis())
        }
    }

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteRaw(id: Long): Int

    @Query("DELETE FROM session_memory_segments WHERE sessionId = :sessionId AND branchId = :branchId AND endMessageId >= :messageId")
    suspend fun deleteMemorySegmentTail(sessionId: Long, branchId: String, messageId: Long): Int

    @Query("SELECT COUNT(*) FROM session_memory_segments WHERE sessionId = :sessionId AND branchId = :branchId AND endMessageId >= :messageId")
    suspend fun countMemorySegmentTail(sessionId: Long, branchId: String, messageId: Long): Int

    /** 在原文仍存在时计算；同一故事线仅保留最早失效点，不重复计数。 */
    private suspend fun memoryTailCutoffs(messages: List<MessageEntity>): Map<String, Long> {
        val cutoffs = mutableMapOf<String, Long>()
        for (message in messages) {
            for (branchId in contextMemoryBranchesAffectedBy(message)) {
                val groupId = message.swipeGroupId?.takeIf(String::isNotBlank)
                val cutoff = if (groupId == null) message.id else {
                    // 撤回选中版本后，更早的同组原文可能重新参与上下文。
                    visibleSwipeVariants(message.sessionId, branchId, groupId).minOfOrNull { it.id } ?: message.id
                }
                cutoffs[branchId] = minOf(cutoffs[branchId] ?: cutoff, cutoff)
            }
        }
        return cutoffs
    }

    @Query("SELECT * FROM session_context_memories WHERE sessionId = :sessionId AND branchId = :branchId LIMIT 1")
    suspend fun getContextMemoryForInvalidation(
        sessionId: Long,
        branchId: String,
    ): SessionContextMemoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContextMemoryInvalidation(entity: SessionContextMemoryEntity): Long

    @Query(
        "UPDATE session_context_memories SET isValid = 0, revision = revision + 1, updatedAt = :updatedAt " +
            "WHERE sessionId = :sessionId AND branchId = :branchId",
    )
    suspend fun markExistingContextMemoryInvalid(
        sessionId: Long,
        branchId: String,
        updatedAt: Long,
    ): Int

    @Transaction
    suspend fun invalidateContextMemoryForBranch(
        sessionId: Long,
        branchId: String,
        updatedAt: Long,
    ): Int {
        if (branchId.isBlank()) return 0
        val current = getContextMemoryForInvalidation(sessionId, branchId)
        if (current != null) {
            return markExistingContextMemoryInvalid(sessionId, branchId, updatedAt)
        }
        insertContextMemoryInvalidation(
            SessionContextMemoryEntity(
                sessionId = sessionId,
                branchId = branchId,
                isValid = false,
                revision = 1L,
                createdAt = updatedAt,
                updatedAt = updatedAt,
            ),
        )
        return 1
    }

    @Query("DELETE FROM session_event_nodes WHERE sessionId = :sessionId AND messageId = :messageId")
    suspend fun deleteEventNodesForMessage(sessionId: Long, messageId: Long): Int

    @Query("SELECT COUNT(*) FROM session_branches WHERE sessionId = :sessionId AND sourceMessageId IN (:messageIds)")
    suspend fun countRecallReferences(sessionId: Long, messageIds: List<Long>): Int

    @Query("SELECT * FROM session_branches WHERE sessionId = :sessionId AND sourceMessageId IN (:messageIds) ORDER BY id LIMIT 10")
    suspend fun getRecallReferences(sessionId: Long, messageIds: List<Long>): List<SessionBranchEntity>

    @Query("SELECT COUNT(*) FROM messages AS replacement JOIN messages AS source ON source.id = replacement.regeneratedFromMessageId WHERE replacement.sessionId = :sessionId AND source.sessionId = :sessionId AND source.id IN (:messageIds) AND replacement.branchId <> source.branchId")
    suspend fun countCrossBranchReplacements(sessionId: Long, messageIds: List<Long>): Int

    @Transaction
    suspend fun previewRecallInSession(sessionId: Long, id: Long): MessageRecallImpact {
        val target = getByIdInSession(id, sessionId)
            ?: return MessageRecallImpact(false, "消息不存在或已经撤回")
        val plan = MessageRecallPolicy.plan(target, getDerivedChildrenInSession(sessionId, id))
        return recallImpact(target, plan.messagesToDelete)
    }

    private suspend fun recallImpact(target: MessageEntity, messages: List<MessageEntity>): MessageRecallImpact {
        val ids = messages.map(MessageEntity::id)
        val referenceCount = countRecallReferences(target.sessionId, ids)
        val hasReplacements = countCrossBranchReplacements(target.sessionId, ids) > 0
        val isEditedVersion = messages.any { message ->
            message.regeneratedFromMessageId?.let { sourceId ->
                getByIdInSession(sourceId, target.sessionId)?.branchId?.let { it != message.branchId }
            } == true
        }
        val reason = when {
            referenceCount > 0 || hasReplacements -> "这条消息或其附属内容是故事线、检查点或编辑版本的来源。请保留原文，使用编辑创建新的故事线。"
            isEditedVersion -> "这条消息是编辑后的版本，撤回会让旧版本重新出现。请使用编辑创建新的故事线。"
            else -> ""
        }
        val memoryCutoffs = if (reason.isEmpty()) memoryTailCutoffs(messages) else emptyMap()
        var affectedSummaries = 0
        for ((branchId, cutoff) in memoryCutoffs) affectedSummaries += countMemorySegmentTail(target.sessionId, branchId, cutoff)
        return MessageRecallImpact(
            canRecall = reason.isEmpty(), reason = reason, referenceCount = referenceCount,
            references = getRecallReferences(target.sessionId, ids).map {
                MessageRecallReference(it.branchId, it.label.ifBlank { it.branchId }, it.isCheckpoint)
            },
            removesDerivedMessages = messages.size > 1,
            maySelectRemainingReply = !target.swipeGroupId.isNullOrBlank(),
            affectedSummaryCount = affectedSummaries,
        )
    }

    /** 原文与受影响故事线的摘要尾部同事务删除，避免留下整理游标空洞。 */
    @Transaction
    suspend fun delete(id: Long): Boolean {
        val message = getById(id) ?: return false
        val impact = recallImpact(message, listOf(message))
        if (!impact.canRecall) throw MessageRecallBlockedException(impact.reason)
        val memoryCutoffs = memoryTailCutoffs(listOf(message))
        for ((branchId, cutoff) in memoryCutoffs) deleteMemorySegmentTail(message.sessionId, branchId, cutoff)
        deleteEventNodesForMessage(message.sessionId, id)
        check(deleteRaw(id) == 1) { "消息删除事务未完整提交" }
        memoryCutoffs.keys.forEach { branchId ->
            invalidateContextMemoryForBranch(message.sessionId, branchId, System.currentTimeMillis())
        }
        return true
    }

    /**
     * 用户撤回消息时，原子删除明确归属的非上下文子消息，并在删除当前 swipe 版本后选中剩余版本。
     * 附件行由 Room 外键级联删除；返回路径交给调用方在事务提交后做私有文件清理。
     */
    @Transaction
    suspend fun recallInSession(sessionId: Long, id: Long): MessageRecallResult {
        val target = getByIdInSession(id, sessionId) ?: return MessageRecallResult(deleted = false)
        val children = getDerivedChildrenInSession(sessionId, id)
        val groupId = target.swipeGroupId?.takeIf(String::isNotBlank)
        val plan = MessageRecallPolicy.plan(target, children)
        val impact = recallImpact(target, plan.messagesToDelete)
        if (!impact.canRecall) throw MessageRecallBlockedException(impact.reason)
        val messageIds = plan.messagesToDelete.map(MessageEntity::id)
        val attachmentPaths = getAttachmentStoragePaths(messageIds)
        val affectedBranches = contextMemoryBranchesAffectedBy(target)
        val memoryCutoffs = memoryTailCutoffs(plan.messagesToDelete)
        for ((branchId, cutoff) in memoryCutoffs) deleteMemorySegmentTail(sessionId, branchId, cutoff)

        for (message in plan.messagesToDelete) {
            deleteEventNodesForMessage(message.sessionId, message.id)
            check(deleteRaw(message.id) == 1) { "消息撤回事务未完整提交" }
        }
        var mainFallbackId: Long? = null
        if (groupId != null) {
            for (branchId in affectedBranches) {
                val remaining = visibleSwipeVariants(sessionId, branchId, groupId)
                val fallbackId = remaining.maxWithOrNull(
                    compareBy<MessageEntity> { it.createdAt }.thenBy { it.id },
                )?.id
                if (fallbackId == null) {
                    deleteBranchSwipeSelection(sessionId, branchId, groupId)
                } else {
                    upsertBranchSwipeSelectionRaw(
                        BranchSwipeSelectionEntity(
                            sessionId = sessionId,
                            branchId = branchId,
                            swipeGroupId = groupId,
                            selectedMessageId = fallbackId,
                        ),
                    )
                    if (branchId == "main") mainFallbackId = fallbackId
                }
            }
        }
        (affectedBranches + memoryCutoffs.keys).distinct().forEach { branchId ->
            invalidateContextMemoryForBranch(sessionId, branchId, System.currentTimeMillis())
        }
        return MessageRecallResult(
            deleted = true,
            deletedMessageIds = messageIds,
            attachmentStoragePaths = attachmentPaths,
            fallbackSwipeMessageId = mainFallbackId,
        )
    }

    private suspend fun visibleSwipeVariants(
        sessionId: Long,
        branchId: String,
        gid: String,
    ): List<MessageEntity> = if (branchId == "main") {
        getMainSwipeGroupMessages(sessionId, gid)
    } else {
        getVisibleSwipeGroupMessages(sessionId, branchId, gid)
    }

    private suspend fun effectiveSwipeSelection(
        sessionId: Long,
        branchId: String,
        gid: String,
        visibleVariants: List<MessageEntity>,
    ): Long? {
        if (visibleVariants.isEmpty()) return null
        val visibleIds = visibleVariants.mapTo(mutableSetOf(), MessageEntity::id)
        return getBranchSwipeSelection(sessionId, branchId, gid)
            ?.selectedMessageId
            ?.takeIf(visibleIds::contains)
            ?: visibleVariants.lastOrNull { it.includeInContext }?.id
            ?: visibleVariants.maxWithOrNull(
                compareBy<MessageEntity> { it.createdAt }.thenBy { it.id },
            )?.id
    }

    private suspend fun contextMemoryBranchesAffectedBy(message: MessageEntity): List<String> {
        val groupId = message.swipeGroupId?.takeIf(String::isNotBlank)
        if (groupId != null) {
            return (listOf("main", message.branchId) + getSessionBranchIdsForSwipeRepair(message.sessionId))
                .filter(String::isNotBlank)
                .distinct()
                .filter { branchId ->
                    val variants = visibleSwipeVariants(message.sessionId, branchId, groupId)
                    effectiveSwipeSelection(message.sessionId, branchId, groupId, variants) == message.id
                }
        }
        if (!message.includeInContext) return emptyList()
        return (listOf(message.branchId) + getStorylinesSeeingSourceMessage(
            sessionId = message.sessionId,
            sourceBranchId = message.branchId,
            messageId = message.id,
        )).filter(String::isNotBlank).distinct()
    }

    @Query("SELECT * FROM message_search_index_state WHERE id = 1 LIMIT 1")
    suspend fun getSearchIndexState(): MessageSearchIndexStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSearchIndexState(state: MessageSearchIndexStateEntity)

    @Query("SELECT * FROM messages WHERE id > :afterMessageId ORDER BY id ASC LIMIT :limit")
    suspend fun getMessagesForSearchRebuild(afterMessageId: Long, limit: Int): List<MessageEntity>

    @Query(
        "UPDATE messages SET searchNormalized = :searchNormalized, searchTerms = :searchTerms " +
            "WHERE id = :messageId",
    )
    suspend fun updateSearchFields(
        messageId: Long,
        searchNormalized: String,
        searchTerms: String,
    )

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE id > :messageId LIMIT 1)")
    suspend fun hasMessagesAfter(messageId: Long): Boolean

    @Transaction
    suspend fun rebuildSearchIndexBatch(
        batchSize: Int = MessageSearchTokenizer.REBUILD_BATCH_SIZE,
        now: Long = System.currentTimeMillis(),
    ): MessageSearchRebuildBatchResult {
        require(batchSize in 1..1000)
        var state = getSearchIndexState()
        if (state?.indexVersion != MessageSearchTokenizer.INDEX_VERSION) {
            state = MessageSearchIndexStateEntity(
                indexVersion = MessageSearchTokenizer.INDEX_VERSION,
                updatedAt = now,
            )
            upsertSearchIndexState(state)
        }
        if (state.isComplete) {
            return MessageSearchRebuildBatchResult(
                indexedThroughMessageId = state.indexedThroughMessageId,
                indexedCount = 0,
                isComplete = true,
            )
        }

        val messages = getMessagesForSearchRebuild(state.indexedThroughMessageId, batchSize)
        messages.forEach { message ->
            val indexed = MessageSearchTokenizer.index(message)
            updateSearchFields(
                messageId = message.id,
                searchNormalized = indexed.searchNormalized,
                searchTerms = indexed.searchTerms,
            )
        }
        val cursor = messages.lastOrNull()?.id ?: state.indexedThroughMessageId
        val complete = !hasMessagesAfter(cursor)
        upsertSearchIndexState(
            state.copy(
                indexedThroughMessageId = cursor,
                isComplete = complete,
                updatedAt = now,
            ),
        )
        return MessageSearchRebuildBatchResult(
            indexedThroughMessageId = cursor,
            indexedCount = messages.size,
            isComplete = complete,
        )
    }

    private suspend fun currentSearchIndexState(): MessageSearchIndexStateEntity {
        val state = getSearchIndexState()
        return if (state?.indexVersion == MessageSearchTokenizer.INDEX_VERSION) {
            state
        } else {
            MessageSearchIndexStateEntity(indexVersion = MessageSearchTokenizer.INDEX_VERSION)
        }
    }

    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId AND branchId = 'main'")
    suspend fun messageCount(sessionId: Long): Int
}
