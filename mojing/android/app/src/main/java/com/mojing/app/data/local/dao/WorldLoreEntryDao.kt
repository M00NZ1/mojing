package com.mojing.app.data.local.dao

import androidx.room.*
import com.mojing.app.data.local.entity.WorldLoreEntryEntity

data class WorldLoreMergeRow(val id: Long, val title: String, val entryType: String)
data class WorldLoreFirstTitleId(val trimmedTitle: String, val firstId: Long)
data class WorldLoreMergeStats(val total: Int, val toAdd: Int, val conflicts: Int)

@Dao
interface WorldLoreEntryDao {
    @Query("SELECT * FROM world_lore_entries WHERE worldTemplateId = :templateId ORDER BY sortOrder ASC")
    suspend fun getByTemplate(templateId: Long): List<WorldLoreEntryEntity>

    @Query("SELECT id, title, entryType FROM world_lore_entries WHERE worldTemplateId = :templateId AND id > :afterId ORDER BY id ASC LIMIT :limit")
    suspend fun getMergePage(templateId: Long, afterId: Long, limit: Int): List<WorldLoreMergeRow>

    @Query("SELECT * FROM world_lore_entries WHERE worldTemplateId = :templateId AND id > :afterId ORDER BY id ASC LIMIT :limit")
    suspend fun getMergeContentPage(templateId: Long, afterId: Long, limit: Int): List<WorldLoreEntryEntity>

    @Query("SELECT trim(title) AS trimmedTitle, MIN(id) AS firstId FROM world_lore_entries WHERE worldTemplateId = :templateId AND trim(title) IN (:titles) GROUP BY trim(title)")
    suspend fun getFirstIdsForTrimmedTitles(templateId: Long, titles: List<String>): List<WorldLoreFirstTitleId>

    @Query(
        """WITH source AS (
            SELECT id, trim(title) AS trimmedTitle FROM world_lore_entries WHERE worldTemplateId = :templateId
        ), source_first AS (
            SELECT trimmedTitle, MIN(id) AS firstId FROM source GROUP BY trimmedTitle
        ), target_names AS (
            SELECT DISTINCT trim(title) AS trimmedTitle FROM encyclopedia_entries WHERE encyclopediaId = :targetId
        ), mapped AS (
            SELECT loreEntryId FROM legacy_lore_mappings
        )
        SELECT COUNT(*) AS total,
            COALESCE(SUM(CASE WHEN mapped.loreEntryId IS NULL AND source_first.firstId = source.id AND target_names.trimmedTitle IS NULL THEN 1 ELSE 0 END), 0) AS toAdd,
            COALESCE(SUM(CASE WHEN mapped.loreEntryId IS NOT NULL OR source_first.firstId != source.id OR target_names.trimmedTitle IS NOT NULL THEN 1 ELSE 0 END), 0) AS conflicts
        FROM source
        JOIN source_first ON source_first.trimmedTitle = source.trimmedTitle
        LEFT JOIN target_names ON target_names.trimmedTitle = source.trimmedTitle
        LEFT JOIN mapped ON mapped.loreEntryId = source.id""",
    )
    suspend fun getMergeStats(templateId: Long, targetId: Long): WorldLoreMergeStats

    @Query("SELECT id FROM world_lore_entries WHERE worldTemplateId = :templateId AND trim(title) = :title ORDER BY id ASC LIMIT 1")
    suspend fun findFirstIdByTrimmedTitle(templateId: Long, title: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WorldLoreEntryEntity): Long

    @Query("DELETE FROM world_lore_entries WHERE id = :id")
    suspend fun delete(id: Long)

    /** 同一模板再次生成/写入前清空，避免 Lore 条数无限叠加 */
    @Query("DELETE FROM world_lore_entries WHERE worldTemplateId = :worldTemplateId")
    suspend fun deleteByWorldTemplateId(worldTemplateId: Long): Int
}
