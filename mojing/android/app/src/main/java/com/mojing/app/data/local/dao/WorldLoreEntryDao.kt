package com.mojing.app.data.local.dao

import androidx.room.*
import com.mojing.app.data.local.entity.WorldLoreEntryEntity

@Dao
interface WorldLoreEntryDao {
    @Query("SELECT * FROM world_lore_entries WHERE worldTemplateId = :templateId ORDER BY sortOrder ASC")
    suspend fun getByTemplate(templateId: Long): List<WorldLoreEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WorldLoreEntryEntity): Long

    @Query("DELETE FROM world_lore_entries WHERE id = :id")
    suspend fun delete(id: Long)

    /** 同一模板再次生成/写入前清空，避免 Lore 条数无限叠加 */
    @Query("DELETE FROM world_lore_entries WHERE worldTemplateId = :worldTemplateId")
    suspend fun deleteByWorldTemplateId(worldTemplateId: Long)
}
