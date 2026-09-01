package com.mojing.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "session_context_memories",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("sessionId"),
        Index("branchId"),
        Index(value = ["sessionId", "branchId"], unique = true),
        Index("updatedAt"),
    ],
)
data class SessionContextMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val branchId: String = "main",
    val globalSummary: String = "",
    val userStateJson: String = "{}",
    val characterStatesJson: String = "[]",
    val relationshipStatesJson: String = "[]",
    val worldStateJson: String = "{}",
    val recentTimelineJson: String = "[]",
    val openThreadsJson: String = "[]",
    val continuityRulesJson: String = "[]",
    val sourceStartMessageId: Long = 0,
    val sourceEndMessageId: Long = 0,
    val memoryVersion: Int = 1,
    @ColumnInfo(defaultValue = "1") val isValid: Boolean = true,
    @ColumnInfo(defaultValue = "0") val revision: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
