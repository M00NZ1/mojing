package com.mojing.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "character_profiles",
    foreignKeys = [ForeignKey(CharacterEntity::class, ["id"], ["characterId"], ForeignKey.CASCADE)],
    indices = [Index("characterId", unique = true)]
)
data class CharacterProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val characterId: Long,
    val sourceFilename: String = "",
    val rawPersonaText: String = "",
    val characterCardJson: String = "{}",
    val characterCardMarkdown: String = "",
    val extractedAt: Long = System.currentTimeMillis()
)
