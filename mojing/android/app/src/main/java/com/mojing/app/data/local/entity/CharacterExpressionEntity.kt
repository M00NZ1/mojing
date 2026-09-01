package com.mojing.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "character_expressions",
    foreignKeys = [ForeignKey(CharacterEntity::class, ["id"], ["characterId"], ForeignKey.CASCADE)],
    indices = [Index("characterId")]
)
data class CharacterExpressionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val characterId: Long,
    val expression: String = "default",
    val label: String = "",
    val imagePath: String = "",
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
