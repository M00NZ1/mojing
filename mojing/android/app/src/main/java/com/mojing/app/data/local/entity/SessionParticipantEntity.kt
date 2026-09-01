package com.mojing.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "session_participants",
    foreignKeys = [
        ForeignKey(SessionEntity::class, ["id"], ["sessionId"], ForeignKey.CASCADE),
        ForeignKey(CharacterEntity::class, ["id"], ["characterId"], ForeignKey.CASCADE)
    ],
    indices = [Index("sessionId"), Index("characterId")]
)
data class SessionParticipantEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val characterId: Long,
    val sortOrder: Int = 0,
    val talkativeness: Float = 0.7f,
    val muted: Boolean = false,
    val forceNext: Boolean = false,
    val allowSelfResponse: Boolean = false,
    val speakerStrategy: String = "natural"
)
