package com.mojing.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "message_bookmarks",
    foreignKeys = [
        ForeignKey(SessionEntity::class, ["id"], ["sessionId"], ForeignKey.CASCADE),
        ForeignKey(MessageEntity::class, ["id"], ["messageId"], ForeignKey.CASCADE)
    ],
    indices = [Index("sessionId"), Index("messageId")]
)
data class MessageBookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val messageId: Long,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
