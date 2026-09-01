package com.mojing.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "world_encyclopedias")
data class EncyclopediaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "",
    val description: String = "",
    val coverImagePath: String = "",
    val isOfficial: Boolean = false,
    val genreTags: String = "",
    val worldPrompt: String = "",
    val gameplayMode: String = "自由剧情",
    val antiCheatPrompt: String = "",
    val narratorConfigJson: String = "{}",
    val entryCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** >0 表示置顶 */
    val pinnedAt: Long = 0L,
)
