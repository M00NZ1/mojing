package com.mojing.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "encyclopedia_entries",
    foreignKeys = [ForeignKey(EncyclopediaEntity::class, ["id"], ["encyclopediaId"], ForeignKey.CASCADE)],
    indices = [Index("encyclopediaId"), Index("entryType"), Index("title")]
)
data class EncyclopediaEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val encyclopediaId: Long,
    val title: String = "",
    val entryType: String = "world",
    val summary: String = "",
    val content: String = "",
    val tags: String = "",
    val confidence: String = "confirmed",
    val isFeatured: Boolean = false,
    val changeNote: String = "",
    val sourceSessionId: Long? = null,
    val sourceMessageId: Long? = null,
    /** 列表/预览侧栏封面；与后端 cover_image_path 对齐 */
    val coverImagePath: String = "",
    val metaJson: String = "{}",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
