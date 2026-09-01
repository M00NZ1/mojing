package com.mojing.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "message_attachments",
    foreignKeys = [ForeignKey(MessageEntity::class, ["id"], ["messageId"], ForeignKey.CASCADE)],
    indices = [Index("messageId")]
)
data class MessageAttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: Long,
    val assetType: String = "image",
    val fileName: String = "",
    val mimeType: String = "",
    val storagePath: String = "",
    val generationPrompt: String = "",
    val generationModel: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
