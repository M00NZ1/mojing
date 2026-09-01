package com.mojing.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "session_event_nodes",
    foreignKeys = [ForeignKey(SessionEntity::class, ["id"], ["sessionId"], ForeignKey.CASCADE)],
    indices = [Index("sessionId"), Index("branchId"), Index("characterId")]
)
data class SessionEventNodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val characterId: Long? = null,
    val branchId: String = "main",
    val parentEventId: Long? = null,
    val eventType: String = "action",
    val title: String = "",
    val description: String = "",
    val importance: Int = 1,
    val messageId: Long? = null,
    val resolved: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
