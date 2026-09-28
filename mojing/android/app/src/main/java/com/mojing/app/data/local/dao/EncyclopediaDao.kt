package com.mojing.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mojing.app.data.local.entity.EncyclopediaEntity

data class EncyclopediaNameOption(val id: Long, val name: String)

@Dao
interface EncyclopediaDao {
    @Query("SELECT id, name FROM world_encyclopedias ORDER BY CASE WHEN pinnedAt > 0 THEN 0 ELSE 1 END, pinnedAt DESC, updatedAt DESC, id DESC")
    suspend fun getNameOptions(): List<EncyclopediaNameOption>
    @Query("SELECT * FROM world_encyclopedias ORDER BY CASE WHEN pinnedAt > 0 THEN 0 ELSE 1 END, pinnedAt DESC, updatedAt DESC")
    suspend fun getAll(): List<EncyclopediaEntity>

    @Query("SELECT * FROM world_encyclopedias WHERE id = :id")
    suspend fun getById(id: Long): EncyclopediaEntity?

    @Upsert
    suspend fun upsert(entity: EncyclopediaEntity): Long

    @Query("UPDATE world_encyclopedias SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateName(id: Long, name: String, updatedAt: Long): Int

    @Query("UPDATE world_encyclopedias SET coverImagePath = :path, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateCover(id: Long, path: String, updatedAt: Long): Int

    @Query("UPDATE world_encyclopedias SET coverImagePath = :newPath, updatedAt = :updatedAt WHERE id = :id AND coverImagePath = :expectedPath")
    suspend fun updateCoverIfUnchanged(id: Long, expectedPath: String, newPath: String, updatedAt: Long): Int

    @Query("UPDATE world_encyclopedias SET pinnedAt = :pinnedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updatePinned(id: Long, pinnedAt: Long, updatedAt: Long): Int

    @Query("DELETE FROM world_encyclopedias WHERE id = :id")
    suspend fun delete(id: Long)
    @Query("UPDATE world_encyclopedias SET name = :name, description = :description, worldPrompt = :prompt, gameplayMode = :gameplay, antiCheatPrompt = :antiCheat, updatedAt = :now WHERE id = :id AND updatedAt = :expectedUpdatedAt")
    suspend fun updateWorldSettings(id: Long, expectedUpdatedAt: Long, name: String, description: String, prompt: String, gameplay: String, antiCheat: String, now: Long): Int
}
