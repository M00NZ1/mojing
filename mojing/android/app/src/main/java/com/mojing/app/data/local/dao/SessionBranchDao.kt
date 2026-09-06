package com.mojing.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.mojing.app.data.local.branch.BranchVisibilityPlanner
import com.mojing.app.data.local.entity.BranchSwipeSelectionEntity
import com.mojing.app.data.local.entity.BranchVisibilitySegmentEntity
import com.mojing.app.data.local.entity.MessageAttachmentEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionBranchEntity
import com.mojing.app.data.local.search.MessageSearchTokenizer
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionBranchDao {
    @Query("SELECT * FROM session_branches WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    fun observeBySession(sessionId: Long): Flow<List<SessionBranchEntity>>

    @Query("SELECT * FROM session_branches WHERE sessionId = :sessionId")
    suspend fun getBySession(sessionId: Long): List<SessionBranchEntity>

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

    @Query("SELECT sessionId FROM messages WHERE id = :sourceId LIMIT 1")
    suspend fun sourceSessionId(sourceId: Long): Long?

    @Transaction
    suspend fun insert(entity: SessionBranchEntity): Long {
        require(sourceSessionId(entity.sourceMessageId) == entity.sessionId) { "故事线来源已不存在或不属于当前会话" }
        val id = insertRaw(entity)
        rebuildVisibilitySegments(entity.sessionId)
        copyVisibleSwipeSelectionOverrides(
            sessionId = entity.sessionId,
            parentBranchId = entity.parentBranchId,
            branchId = entity.branchId,
        )
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
        insert(branch)
        val replacementId = insertMessage(MessageSearchTokenizer.index(replacement))
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
        return replacementId
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
