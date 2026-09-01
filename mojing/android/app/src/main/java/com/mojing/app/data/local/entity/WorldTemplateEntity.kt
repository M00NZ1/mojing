package com.mojing.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "world_templates")
data class WorldTemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val templateId: String = "",
    val label: String = "",
    val category: String = "",
    val summary: String = "",
    val gameplayMode: String = "自由剧情",
    val worldPrompt: String = "",
    val coverImagePath: String = "",
    val suggestedChoicesJson: String = "[]",
    val antiCheatPrompt: String = "",
    val isBuiltin: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** >0 表示置顶 */
    val pinnedAt: Long = 0L,
)
