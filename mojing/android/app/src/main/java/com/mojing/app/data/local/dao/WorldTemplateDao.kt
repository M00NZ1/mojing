package com.mojing.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mojing.app.data.local.entity.WorldTemplateEntity

data class NewSessionWorldOption(
    val kind: Int,
    val id: Long,
    val name: String,
    val entryCount: Int,
    val pinnedAt: Long,
    val updatedAt: Long,
)

data class WorldTemplateLibraryItem(
    val id: Long,
    val templateId: String,
    val label: String,
    val category: String,
    val summary: String,
    val coverImagePath: String,
    val pinnedAt: Long,
    val updatedAt: Long,
)

/** Defaults picker needs only the saved key, display name and stable sort fields. */
data class DefaultWorldOption(
    val id: Long,
    val templateId: String,
    val label: String,
    val pinnedAt: Long,
    val updatedAt: Long,
)

@Dao
interface WorldTemplateDao {
    @Query(
        """
        SELECT wt.id, wt.templateId, wt.label, wt.pinnedAt, wt.updatedAt FROM world_templates wt
        LEFT JOIN world_templates better ON better.templateId = wt.templateId
          AND (better.pinnedAt > wt.pinnedAt
            OR (better.pinnedAt = wt.pinnedAt AND better.updatedAt > wt.updatedAt)
            OR (better.pinnedAt = wt.pinnedAt AND better.updatedAt = wt.updatedAt AND better.id > wt.id))
        WHERE wt.templateId != 'custom' AND better.id IS NULL
          AND (:query = '' OR instr(lower(wt.label), lower(:query)) > 0)
          AND (:cursorId IS NULL OR wt.pinnedAt < :cursorPinnedAt
            OR (wt.pinnedAt = :cursorPinnedAt AND wt.updatedAt < :cursorUpdatedAt)
            OR (wt.pinnedAt = :cursorPinnedAt AND wt.updatedAt = :cursorUpdatedAt AND wt.id < :cursorId))
        ORDER BY wt.pinnedAt DESC, wt.updatedAt DESC, wt.id DESC
        LIMIT :limit
        """,
    )
    suspend fun getDefaultWorldPage(
        query: String, cursorPinnedAt: Long?, cursorUpdatedAt: Long?, cursorId: Long?, limit: Int,
    ): List<DefaultWorldOption>

    @Query("SELECT id, templateId, label, pinnedAt, updatedAt FROM world_templates WHERE templateId = :templateId ORDER BY pinnedAt DESC, updatedAt DESC, id DESC LIMIT 1")
    suspend fun getDefaultWorldByTemplateId(templateId: String): DefaultWorldOption?

    @Query(
        """
        SELECT id, templateId, label, substr(category, 1, 80) AS category,
               substr(summary, 1, 96) AS summary, coverImagePath, pinnedAt, updatedAt
        FROM world_templates
        WHERE (:query = '' OR instr(lower(label), lower(:query)) > 0
               OR instr(lower(summary), lower(:query)) > 0
               OR instr(lower(category), lower(:query)) > 0)
          AND (:cursorId IS NULL OR
               (CASE WHEN pinnedAt > 0 THEN 1 ELSE 0 END) < :cursorPinned OR
               ((CASE WHEN pinnedAt > 0 THEN 1 ELSE 0 END) = :cursorPinned AND pinnedAt < :cursorPinnedAt) OR
               ((CASE WHEN pinnedAt > 0 THEN 1 ELSE 0 END) = :cursorPinned AND pinnedAt = :cursorPinnedAt AND updatedAt < :cursorUpdatedAt) OR
               ((CASE WHEN pinnedAt > 0 THEN 1 ELSE 0 END) = :cursorPinned AND pinnedAt = :cursorPinnedAt AND updatedAt = :cursorUpdatedAt AND id < :cursorId))
        ORDER BY CASE WHEN pinnedAt > 0 THEN 1 ELSE 0 END DESC, pinnedAt DESC, updatedAt DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun getLibraryPage(
        query: String, cursorPinned: Int?, cursorPinnedAt: Long?, cursorUpdatedAt: Long?,
        cursorId: Long?, limit: Int,
    ): List<WorldTemplateLibraryItem>

    @Query("SELECT * FROM world_templates ORDER BY CASE WHEN pinnedAt > 0 THEN 0 ELSE 1 END, pinnedAt DESC, updatedAt DESC")
    suspend fun getAll(): List<WorldTemplateEntity>

    @Query("SELECT * FROM world_templates WHERE id = :id")
    suspend fun getById(id: Long): WorldTemplateEntity?

    @Query("SELECT * FROM world_templates WHERE templateId = :templateId ORDER BY pinnedAt DESC, updatedAt DESC, id DESC LIMIT 1")
    suspend fun getByTemplateId(templateId: String): WorldTemplateEntity?

    /** Encyclopedias precede unmapped legacy templates, matching the previous picker groups. */
    @Query(
        """
        SELECT kind, id, name, entryCount, pinnedAt, updatedAt FROM (
          SELECT 0 AS kind, id, name, entryCount, pinnedAt, updatedAt
          FROM world_encyclopedias
          WHERE :query = '' OR instr(lower(name), lower(:query)) > 0
          UNION ALL
          SELECT 1 AS kind, wt.id, wt.label AS name, 0 AS entryCount, wt.pinnedAt, wt.updatedAt
          FROM world_templates wt
          WHERE wt.templateId != 'custom'
            AND NOT EXISTS (SELECT 1 FROM legacy_world_mappings lm WHERE lm.worldTemplateId = wt.id)
            AND (:query = '' OR instr(lower(wt.label), lower(:query)) > 0)
        )
        WHERE (:cursorKind IS NULL OR kind > :cursorKind
          OR (kind = :cursorKind AND pinnedAt < :cursorPinnedAt)
          OR (kind = :cursorKind AND pinnedAt = :cursorPinnedAt AND updatedAt < :cursorUpdatedAt)
          OR (kind = :cursorKind AND pinnedAt = :cursorPinnedAt AND updatedAt = :cursorUpdatedAt AND id < :cursorId))
        ORDER BY kind ASC, pinnedAt DESC, updatedAt DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun getNewSessionWorldPage(
        query: String, cursorKind: Int?, cursorPinnedAt: Long?, cursorUpdatedAt: Long?,
        cursorId: Long?, limit: Int,
    ): List<NewSessionWorldOption>

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
    suspend fun delete(id: Long): Int
    @Query("SELECT worldTemplateId, encyclopediaId FROM legacy_world_mappings")
    suspend fun getWorldMappings(): List<WorldTemplateEncyclopediaMapping>
}
