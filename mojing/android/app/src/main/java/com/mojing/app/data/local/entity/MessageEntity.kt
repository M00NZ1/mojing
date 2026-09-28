package com.mojing.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(SessionEntity::class, ["id"], ["sessionId"], ForeignKey.CASCADE)
    ],
    indices = [
        Index("sessionId"),
        Index("branchId"),
        Index("parentMessageId"),
        Index("regeneratedFromMessageId"),
        Index("swipeGroupId"),
        Index("createdAt"),
        Index(value = ["sessionId", "branchId", "id"]),
        Index(value = ["sessionId", "branchId", "createdAt", "id"]),
    ]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val speakerType: String = "user",
    val characterId: Long? = null,
    val branchId: String = "main",
    val parentMessageId: Long? = null,
    val regeneratedFromMessageId: Long? = null,
    val swipeGroupId: String? = null,
    val content: String = "",
    val structuredContentJson: String = "{}",
    val includeInContext: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    /** 可重建的搜索派生列；所有正式写入由 MessageDao 统一刷新。 */
    val searchNormalized: String = "",
    val searchTerms: String = "",
)
