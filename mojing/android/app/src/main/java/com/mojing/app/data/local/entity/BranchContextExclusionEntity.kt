package com.mojing.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** 当前故事线主动排除的阅读单元；回复组的状态不随采用版本切换。 */
@Entity(
    tableName = "branch_context_exclusions",
    primaryKeys = ["sessionId", "branchId", "messageKey"],
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sessionId")],
)
data class BranchContextExclusionEntity(
    val sessionId: Long,
    val branchId: String,
    val messageKey: String,
)

fun MessageEntity.contextSelectionKey(): String =
    swipeGroupId?.takeIf(String::isNotBlank)?.let { "g$it" } ?: "m$id"
