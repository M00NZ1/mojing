package com.mojing.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** 可由 session_branches 重建的查询区段，不是剧情真源。 */
@Entity(
    tableName = "branch_visibility_segments",
    primaryKeys = ["sessionId", "targetBranchId", "sourceBranchId"],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sessionId", "targetBranchId"])],
)
data class BranchVisibilitySegmentEntity(
    val sessionId: Long,
    val targetBranchId: String,
    val sourceBranchId: String,
    val maxMessageId: Long,
)
