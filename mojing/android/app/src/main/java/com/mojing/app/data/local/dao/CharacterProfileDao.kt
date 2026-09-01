package com.mojing.app.data.local.dao

import androidx.room.*
import com.mojing.app.data.local.entity.CharacterProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CharacterProfileDao {
    @Query("SELECT * FROM character_profiles WHERE characterId = :characterId LIMIT 1")
    suspend fun getByCharacter(characterId: Long): CharacterProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CharacterProfileEntity): Long

    @Query("DELETE FROM character_profiles WHERE characterId = :characterId")
    suspend fun deleteByCharacter(characterId: Long)
}
