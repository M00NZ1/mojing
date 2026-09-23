package com.mojing.app.data.local.entity

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "session_character_states",
    foreignKeys = [
        ForeignKey(SessionEntity::class, ["id"], ["sessionId"], ForeignKey.CASCADE),
        ForeignKey(CharacterEntity::class, ["id"], ["characterId"], ForeignKey.CASCADE)
    ],
    indices = [Index("sessionId"), Index("characterId"), Index(value = ["sessionId", "characterId", "branchId"])]
)
data class SessionCharacterStateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val characterId: Long,
    @ColumnInfo(defaultValue = "'main'") val branchId: String = "main",
    @ColumnInfo(defaultValue = "0") val lastSnapshotAttemptUserMessageId: Long = 0,
    @ColumnInfo(defaultValue = "1") val snapshotIsValid: Boolean = true,
    val dynamicStateJson: String = "{}",
    val relationsJson: String = "{}",
    val privateFactsJson: String = "[]",
    val eventLogJson: String = "[]",
    val goalsJson: String = "[]",
    val emotionalState: String = "",
    val lastCompactedMessageId: Long? = null,
    val lastSignificantEventId: Long? = null,
    val updatedAt: Long = System.currentTimeMillis()
)
