package com.mojing.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.mojing.app.data.local.branch.BranchVisibilityPlanner
import com.mojing.app.data.local.branch.BranchContextMemoryInheritance
import com.mojing.app.data.local.entity.BranchSwipeSelectionEntity
import com.mojing.app.data.local.entity.BranchContextExclusionEntity
import com.mojing.app.data.local.entity.BranchVisibilitySegmentEntity
import com.mojing.app.data.local.entity.MessageAttachmentEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionBranchEntity
import com.mojing.app.data.local.entity.SessionContextMemoryEntity
import com.mojing.app.data.local.entity.contextSelectionKey
import com.mojing.app.data.local.search.MessageSearchTokenizer
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionBranchDao {
    @Query("SELECT * FROM session_branches WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    fun observeBySession(sessionId: Long): Flow<List<SessionBranchEntity>>

    @Query("SELECT * FROM session_branches WHERE sessionId = :sessionId")
    suspend fun getBySession(sessionId: Long): List<SessionBranchEntity>

    @Query("SELECT * FROM session_branches WHERE sessionId = :sessionId AND id > :afterId ORDER BY id ASC LIMIT :limit")
    suspend fun getPage(sessionId: Long, afterId: Long, limit: Int): List<SessionBranchEntity>

    @Query("SELECT * FROM session_branches WHERE sessionId = :sessionId AND id < :beforeId ORDER BY id DESC LIMIT :limit")
    suspend fun getPreviousPage(sessionId: Long, beforeId: Long, limit: Int): List<SessionBranchEntity>

    @Query("SELECT * FROM session_branches WHERE sessionId = :sessionId AND branchId = :branchId LIMIT 1")
    suspend fun getByBranch(sessionId: Long, branchId: String): SessionBranchEntity?

    @Query("SELECT * FROM session_branches")
    suspend fun getAll(): List<SessionBranchEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRaw(entity: SessionBranchEntity): Long

    @Query("DELETE FROM branch_visibility_segments WHERE sessionId = :sessionId")
    suspend fun deleteVisibilitySegments(sessionId: Long)

    @Query("DELETE FROM branch_visibility_segments")
    suspend fun deleteAllVisibilitySegments()

    @Query("SELECT * FROM branch_visibility_segments")
    suspend fun getAllVisibilitySegments(): List<BranchVisibilitySegmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVisibilitySegments(segments: List<BranchVisibilitySegmentEntity>)

    @Query(
        """
        INSERT OR REPLACE INTO branch_swipe_selections(
            sessionId, branchId, swipeGroupId, selectedMessageId
        )
        SELECT parent.sessionId, :branchId, parent.swipeGroupId, parent.selectedMessageId
        FROM branch_swipe_selections AS parent
        JOIN messages AS selected
          ON selected.id = parent.selectedMessageId
         AND selected.sessionId = parent.sessionId
         AND selected.swipeGroupId = parent.swipeGroupId
        JOIN branch_visibility_segments AS segment
          ON segment.sessionId = selected.sessionId
         AND segment.targetBranchId = :branchId
         AND segment.sourceBranchId = selected.branchId
         AND selected.id <= segment.maxMessageId
        WHERE parent.sessionId = :sessionId
          AND parent.branchId = :parentBranchId
          AND NOT EXISTS (
              SELECT 1
              FROM messages AS replacement
              JOIN branch_visibility_segments AS replacement_segment
                ON replacement_segment.sessionId = replacement.sessionId
               AND replacement_segment.targetBranchId = :branchId
               AND replacement_segment.sourceBranchId = replacement.branchId
               AND replacement.id <= replacement_segment.maxMessageId
              WHERE replacement.sessionId = parent.sessionId
                AND replacement.regeneratedFromMessageId = selected.id
                AND replacement.branchId <> selected.branchId
          )
        """,
    )
    suspend fun copyVisibleSwipeSelectionOverrides(
        sessionId: Long,
        parentBranchId: String,
        branchId: String,
    )

    @Query(
        """
        INSERT OR REPLACE INTO branch_event_status(sessionId, branchId, eventId, resolved)
        SELECT parent.sessionId, :branchId, parent.eventId, parent.resolved
        FROM branch_event_status AS parent
        JOIN session_event_nodes AS event
          ON event.id = parent.eventId AND event.sessionId = parent.sessionId
        JOIN branch_visibility_segments AS visibility
          ON visibility.sessionId = event.sessionId
         AND visibility.targetBranchId = :branchId
         AND visibility.sourceBranchId = event.branchId
         AND event.messageId IS NOT NULL
         AND event.messageId <= visibility.maxMessageId
        WHERE parent.sessionId = :sessionId AND parent.branchId = :parentBranchId
          AND NOT EXISTS (
              SELECT 1
              FROM messages AS replacement
              JOIN branch_visibility_segments AS replacement_visibility
                ON replacement_visibility.sessionId = replacement.sessionId
               AND replacement_visibility.targetBranchId = :branchId
               AND replacement_visibility.sourceBranchId = replacement.branchId
               AND replacement.id <= replacement_visibility.maxMessageId
              WHERE replacement.sessionId = parent.sessionId
                AND replacement.regeneratedFromMessageId = event.messageId
                AND replacement.branchId <> event.branchId
          )
        """,
    )
    suspend fun copyVisibleEventStatusOverrides(
        sessionId: Long,
        parentBranchId: String,
        branchId: String,
    )

    @Query("SELECT sessionId FROM messages WHERE id = :sourceId LIMIT 1")
    suspend fun sourceSessionId(sourceId: Long): Long?

    /** 新故事线继承可见原文的排除状态；分叉之后和其他故事线的设置不带入。 */
    @Query(
        """
        INSERT OR IGNORE INTO branch_context_exclusions(sessionId, branchId, messageKey)
        SELECT parent.sessionId, :branchId, parent.messageKey
        FROM branch_context_exclusions AS parent
        WHERE parent.sessionId = :sessionId AND parent.branchId = :parentBranchId
          AND EXISTS (
              SELECT 1 FROM messages AS message
              JOIN branch_visibility_segments AS segment
                ON segment.sessionId = message.sessionId
               AND segment.targetBranchId = :branchId
               AND segment.sourceBranchId = message.branchId
               AND message.id <= segment.maxMessageId
              WHERE message.sessionId = parent.sessionId
                AND parent.messageKey = CASE
                    WHEN message.swipeGroupId IS NULL OR trim(message.swipeGroupId) = '' THEN 'm' || message.id
                    ELSE 'g' || message.swipeGroupId END
                AND NOT EXISTS (
                    SELECT 1 FROM messages AS replacement
                    JOIN branch_visibility_segments AS replacement_segment
                      ON replacement_segment.sessionId = replacement.sessionId
                     AND replacement_segment.targetBranchId = :branchId
                     AND replacement_segment.sourceBranchId = replacement.branchId
                     AND replacement.id <= replacement_segment.maxMessageId
                    WHERE replacement.sessionId = message.sessionId
                      AND replacement.regeneratedFromMessageId = message.id
                      AND replacement.branchId <> message.branchId
                )
          )
        """,
    )
    suspend fun copyVisibleContextExclusions(sessionId: Long, parentBranchId: String, branchId: String)

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId AND id = :messageId LIMIT 1")
    suspend fun getEditSourceMessage(sessionId: Long, messageId: Long): MessageEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM branch_context_exclusions WHERE sessionId = :sessionId AND branchId = :branchId AND messageKey = :key)")
    suspend fun isContextExcluded(sessionId: Long, branchId: String, key: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertContextExclusion(entity: BranchContextExclusionEntity): Long

    @Query("DELETE FROM branch_context_exclusions WHERE sessionId = :sessionId AND branchId = :branchId AND messageKey = :key")
    suspend fun deleteContextExclusion(sessionId: Long, branchId: String, key: String)

    @Transaction
    suspend fun insert(entity: SessionBranchEntity): Long {
        val id = insertBranchState(entity)
        inheritCompatibleContextMemory(entity)
        return id
    }

    private suspend fun insertBranchState(entity: SessionBranchEntity): Long {
        require(sourceSessionId(entity.sourceMessageId) == entity.sessionId) { "故事线来源已不存在或不属于当前会话" }
        val id = insertRaw(entity)
        rebuildVisibilitySegments(entity.sessionId)
        copyVisibleSwipeSelectionOverrides(
            sessionId = entity.sessionId,
            parentBranchId = entity.parentBranchId,
            branchId = entity.branchId,
        )
        copyVisibleEventStatusOverrides(
            sessionId = entity.sessionId,
            parentBranchId = entity.parentBranchId,
            branchId = entity.branchId,
        )
        copyVisibleContextExclusions(entity.sessionId, entity.parentBranchId, entity.branchId)
        return id
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(entity: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachment(entity: MessageAttachmentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSwipeSelection(entity: BranchSwipeSelectionEntity): Long

    /** 编辑消息必须一次性创建分支、替代消息和附件副本，避免留下半成品分支。 */
    @Transaction
    suspend fun insertEditedBranch(
        branch: SessionBranchEntity,
        replacement: MessageEntity,
        attachments: List<MessageAttachmentEntity>,
    ): Long {
        require(replacement.content.isNotBlank())
        require(replacement.branchId == branch.branchId)
        require(replacement.regeneratedFromMessageId != null)
        val source = requireNotNull(getEditSourceMessage(branch.sessionId, replacement.regeneratedFromMessageId))
        val sourceKey = source.contextSelectionKey()
        val sourceExcluded = isContextExcluded(branch.sessionId, branch.parentBranchId, sourceKey)
        insertBranchState(branch)
        val replacementId = insertMessage(MessageSearchTokenizer.index(replacement))
        if (sourceExcluded) {
            val replacementKey = replacement.copy(id = replacementId).contextSelectionKey()
            insertContextExclusion(BranchContextExclusionEntity(branch.sessionId, branch.branchId, replacementKey))
            if (sourceKey != replacementKey) deleteContextExclusion(branch.sessionId, branch.branchId, sourceKey)
        }
        attachments.forEach { attachment ->
            insertAttachment(attachment.copy(id = 0, messageId = replacementId))
        }
        replacement.swipeGroupId?.takeIf(String::isNotBlank)?.let { groupId ->
            upsertSwipeSelection(
                BranchSwipeSelectionEntity(
                    sessionId = branch.sessionId,
                    branchId = branch.branchId,
                    swipeGroupId = groupId,
                    selectedMessageId = replacementId,
                ),
            )
        }
        inheritCompatibleContextMemory(branch)
        return replacementId
    }

    @Query("SELECT * FROM session_context_memories WHERE sessionId = :sessionId AND branchId = :branchId LIMIT 1")
    suspend fun getBranchContextMemory(sessionId: Long, branchId: String): SessionContextMemoryEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertInheritedContextMemory(entity: SessionContextMemoryEntity): Long

    @Query("$MAIN_CONTEXT_MESSAGES_QUERY AND message.id > :afterId AND message.id <= :endId " +
        "AND message.speakerType IN ('user', 'character', 'narrator') ORDER BY message.id ASC LIMIT :limit")
    suspend fun getMainMemoryPrefixPage(sessionId: Long, afterId: Long, endId: Long, limit: Int): List<MessageEntity>

    @Query("$VISIBLE_CONTEXT_MESSAGES_QUERY AND message.id > :afterId AND message.id <= :endId " +
        "AND message.speakerType IN ('user', 'character', 'narrator') ORDER BY message.id ASC LIMIT :limit")
    suspend fun getVisibleMemoryPrefixPage(sessionId: Long, branchId: String, afterId: Long, endId: Long, limit: Int): List<MessageEntity>

    private suspend fun inheritCompatibleContextMemory(branch: SessionBranchEntity) {
        // Never resurrect an explicitly cleared/invalidated child or overwrite an in-flight revision.
        if (getBranchContextMemory(branch.sessionId, branch.branchId) != null) return
        val parent = getBranchContextMemory(branch.sessionId, branch.parentBranchId) ?: return
        val compatible = BranchContextMemoryInheritance.isCompatible(branch, parent) { branchId, afterId, limit ->
            if (branchId == "main") getMainMemoryPrefixPage(branch.sessionId, afterId, parent.sourceEndMessageId, limit)
            else getVisibleMemoryPrefixPage(branch.sessionId, branchId, afterId, parent.sourceEndMessageId, limit)
        }
        if (!compatible) return
        val now = System.currentTimeMillis()
        insertInheritedContextMemory(parent.copy(id = 0L, branchId = branch.branchId, revision = 0L,
            createdAt = now, updatedAt = now))
    }

    private suspend fun rebuildVisibilitySegments(sessionId: Long) {
        val segments = BranchVisibilityPlanner.plan(sessionId, getBySession(sessionId))
        deleteVisibilitySegments(sessionId)
        if (segments.isNotEmpty()) insertVisibilitySegments(segments)
    }

    /** 启动时校验派生区段；计算成功后才替换，异常不会先清空现有查询数据。 */
    @Transaction
    suspend fun repairVisibilitySegmentsIfNeeded(): Boolean {
        val expected = BranchVisibilityPlanner.planAll(getAll()).toSet()
        if (getAllVisibilitySegments().toSet() == expected) return false
        deleteAllVisibilitySegments()
        if (expected.isNotEmpty()) insertVisibilitySegments(expected.toList())
        return true
    }
}
