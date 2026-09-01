package com.mojing.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "world_lore_entries")
data class WorldLoreEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val worldTemplateId: Long,
    val title: String = "",
    val entryType: String = "设定",
    val keywordsJson: String = "[]",
    val content: String = "",
    val sortOrder: Int = 0,
    val isCore: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
