package com.mojing.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "session_branches",
    foreignKeys = [ForeignKey(SessionEntity::class, ["id"], ["sessionId"], ForeignKey.CASCADE)],
    indices = [Index("sessionId"), Index("branchId")]
)
data class SessionBranchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val branchId: String,
    val label: String = "",
    val sourceMessageId: Long,
    val parentBranchId: String = "main",
    val isCheckpoint: Boolean = false,
    val checkpointLabel: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
