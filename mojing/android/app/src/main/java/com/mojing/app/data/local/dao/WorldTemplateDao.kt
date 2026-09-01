package com.mojing.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mojing.app.data.local.entity.WorldTemplateEntity

@Dao
interface WorldTemplateDao {
    @Query("SELECT * FROM world_templates ORDER BY CASE WHEN pinnedAt > 0 THEN 0 ELSE 1 END, pinnedAt DESC, updatedAt DESC")
    suspend fun getAll(): List<WorldTemplateEntity>

    @Query("SELECT * FROM world_templates WHERE id = :id")
    suspend fun getById(id: Long): WorldTemplateEntity?

    @Query("SELECT * FROM world_templates WHERE templateId = :templateId LIMIT 1")
    suspend fun getByTemplateId(templateId: String): WorldTemplateEntity?

    @Upsert
    suspend fun upsert(entity: WorldTemplateEntity): Long

    @Query("UPDATE world_templates SET coverImagePath = :path, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateCover(id: Long, path: String, updatedAt: Long): Int

    @Query("UPDATE world_templates SET coverImagePath = :newPath, updatedAt = :updatedAt WHERE id = :id AND coverImagePath = :expectedPath")
    suspend fun updateCoverIfUnchanged(id: Long, expectedPath: String, newPath: String, updatedAt: Long): Int

    @Query("UPDATE world_templates SET pinnedAt = :pinnedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updatePinned(id: Long, pinnedAt: Long, updatedAt: Long): Int

    @Query("UPDATE world_templates SET summary = :summary, worldPrompt = :worldPrompt, updatedAt = :updatedAt WHERE id = :id AND summary = :expectedSummary AND worldPrompt = :expectedWorldPrompt")
    suspend fun updateGeneratedContentIfUnchanged(
        id: Long,
        expectedSummary: String,
        expectedWorldPrompt: String,
        summary: String,
        worldPrompt: String,
        updatedAt: Long,
    ): Int

    @Query("DELETE FROM world_templates WHERE id = :id")
    suspend fun delete(id: Long)
}
