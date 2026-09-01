package com.mojing.app.data.local.dao

import androidx.room.*
import com.mojing.app.data.local.entity.SessionCharacterStateEntity

@Dao
interface CharacterStateDao {
    @Query("SELECT * FROM session_character_states WHERE sessionId = :sessionId AND characterId = :characterId LIMIT 1")
    suspend fun getBySessionAndCharacter(sessionId: Long, characterId: Long): SessionCharacterStateEntity?

    @Query("SELECT * FROM session_character_states WHERE sessionId = :sessionId")
    suspend fun getBySession(sessionId: Long): List<SessionCharacterStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SessionCharacterStateEntity): Long
}
