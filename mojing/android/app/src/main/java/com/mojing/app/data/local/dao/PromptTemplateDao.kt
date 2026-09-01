package com.mojing.app.data.local.dao

import androidx.room.*
import com.mojing.app.data.local.entity.PromptTemplateEntity

@Dao
interface PromptTemplateDao {
    @Query("SELECT * FROM prompt_templates ORDER BY category ASC, label ASC")
    suspend fun getAll(): List<PromptTemplateEntity>

    @Query("SELECT * FROM prompt_templates WHERE templateId = :templateId LIMIT 1")
    suspend fun getByTemplateId(templateId: String): PromptTemplateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PromptTemplateEntity): Long

    @Query("DELETE FROM prompt_templates WHERE id = :id")
    suspend fun delete(id: Long)
}
