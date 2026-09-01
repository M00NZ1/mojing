package com.mojing.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** 每条故事线对同一回复版本组的独立采用状态。 */
@Entity(
    tableName = "branch_swipe_selections",
    primaryKeys = ["sessionId", "branchId", "swipeGroupId"],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["selectedMessageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sessionId", "swipeGroupId"]),
        Index("selectedMessageId"),
    ],
)
data class BranchSwipeSelectionEntity(
    val sessionId: Long,
    val branchId: String,
    val swipeGroupId: String,
    val selectedMessageId: Long,
)
