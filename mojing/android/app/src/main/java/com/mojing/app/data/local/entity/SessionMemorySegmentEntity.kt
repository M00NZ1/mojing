package com.mojing.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "session_memory_segments",
    foreignKeys = [ForeignKey(SessionEntity::class, ["id"], ["sessionId"], ForeignKey.CASCADE)],
    indices = [Index("sessionId"), Index("branchId")]
)
data class SessionMemorySegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val branchId: String = "main",
    val segmentIndex: Int = 0,
    val startMessageId: Long = 0,
    val endMessageId: Long = 0,
    val summary: String = "",
    val keyFactsJson: String = "[]",
    val keyCharactersJson: String = "[]",
    val emotionalTone: String = "中性",
    val createdAt: Long = System.currentTimeMillis()
)
