package com.mojing.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mojing.app.data.local.entity.CharacterEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CharacterDao {
    @Query("SELECT * FROM characters ORDER BY CASE WHEN pinnedAt > 0 THEN 0 ELSE 1 END, pinnedAt DESC, favorite DESC, updatedAt DESC, id DESC")
    fun observeAll(): Flow<List<CharacterEntity>>

    @Query(
        """
        SELECT * FROM characters
        WHERE boundEncyclopediaId = :encyclopediaId
        ORDER BY CASE WHEN pinnedAt > 0 THEN 0 ELSE 1 END, pinnedAt DESC, name COLLATE NOCASE ASC, id ASC
        """,
    )
    fun observeByEncyclopedia(encyclopediaId: Long): Flow<List<CharacterEntity>>

    @Query(
        """
        SELECT * FROM characters
        WHERE boundEncyclopediaId > 0
        ORDER BY CASE WHEN pinnedAt > 0 THEN 0 ELSE 1 END, pinnedAt DESC, favorite DESC, updatedAt DESC, id DESC
        """,
    )
    suspend fun getAllBound(): List<CharacterEntity>

    @Query("SELECT * FROM characters ORDER BY CASE WHEN pinnedAt > 0 THEN 0 ELSE 1 END, pinnedAt DESC, favorite DESC, updatedAt DESC, id DESC")
    suspend fun getAll(): List<CharacterEntity>

    @Query("SELECT * FROM characters WHERE id = :id")
    suspend fun getById(id: Long): CharacterEntity?

    @Upsert
    suspend fun upsert(entity: CharacterEntity): Long

    @Query("DELETE FROM characters WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM characters WHERE boundEncyclopediaId = :encyclopediaId")
    suspend fun deleteByEncyclopediaId(encyclopediaId: Long)

    @Query("UPDATE characters SET favorite = NOT favorite WHERE id = :id")
    suspend fun toggleFavorite(id: Long)
}
