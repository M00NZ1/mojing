package com.mojing.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mojing.app.data.local.entity.LegacyLoreMappingEntity
import com.mojing.app.data.local.entity.LegacyWorldMappingEntity
import kotlinx.coroutines.flow.Flow

data class WorldTemplateEncyclopediaMapping(
    val worldTemplateId: Long,
    val encyclopediaId: Long,
)

@Dao
interface LegacyWorldMappingDao {
    @Query("SELECT * FROM legacy_world_mappings WHERE worldTemplateId = :templateId")
    suspend fun getByTemplateId(templateId: Long): LegacyWorldMappingEntity?

    @Query("SELECT worldTemplateId, encyclopediaId FROM legacy_world_mappings ORDER BY worldTemplateId ASC")
    fun observeAll(): Flow<List<WorldTemplateEncyclopediaMapping>>

    @Query("SELECT worldTemplateId, encyclopediaId FROM legacy_world_mappings ORDER BY worldTemplateId ASC")
    suspend fun getAll(): List<WorldTemplateEncyclopediaMapping>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(mapping: LegacyWorldMappingEntity): Long

    @Query("SELECT * FROM legacy_lore_mappings WHERE loreEntryId = :loreEntryId")
    suspend fun getLoreById(loreEntryId: Long): LegacyLoreMappingEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLore(mapping: LegacyLoreMappingEntity): Long
}
