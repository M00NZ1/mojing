package com.mojing.app.data.local.dao

import androidx.room.*
import com.mojing.app.data.local.entity.CharacterExpressionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpressionDao {
    @Query("SELECT * FROM character_expressions WHERE characterId = :characterId ORDER BY sortOrder ASC")
    fun observeByCharacter(characterId: Long): Flow<List<CharacterExpressionEntity>>

    @Query("SELECT * FROM character_expressions WHERE characterId = :characterId")
    suspend fun getByCharacter(characterId: Long): List<CharacterExpressionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CharacterExpressionEntity): Long

    @Query("DELETE FROM character_expressions WHERE id = :id")
    suspend fun delete(id: Long)
}
