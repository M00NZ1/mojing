package com.mojing.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mojing.app.data.local.entity.EncyclopediaEntity

data class EncyclopediaFilterOption(
    val id: Long,
    val name: String,
    val pinnedAt: Long,
    val updatedAt: Long,
)

data class EncyclopediaNameOption(val id: Long, val name: String)

/** Only the fields needed by the world library; full prompts stay in Room until detail/export. */
data class EncyclopediaLibraryItem(
    val id: Long,
    val name: String,
    val coverImagePath: String,
    val pinnedAt: Long,
    val updatedAt: Long,
    val genreTags: String,
    val preview: String,
    val entryCount: Int = 0,
    val characterCount: Int = 0,
    val locationCount: Int = 0,
    val factionCount: Int = 0,
)

@Dao
interface EncyclopediaDao {
    @Query(
        """
        WITH ranked AS (
        SELECT id, name, coverImagePath, pinnedAt, updatedAt,
               substr(genreTags, 1, 120) AS genreTags,
               CASE WHEN length(trim(description)) > 0
                    THEN substr(trim(description), 1, 160)
                    ELSE substr(trim(worldPrompt), 1, 160) END AS preview,
               CASE WHEN :sort = 'pinned' AND pinnedAt > 0 THEN 1 ELSE 0 END AS sortPinned,
               CASE WHEN :sort = 'pinned' THEN pinnedAt ELSE 0 END AS sortPinnedAt,
               CASE WHEN :sort = 'name' THEN name ELSE '' END AS sortName
        FROM world_encyclopedias
        WHERE (:query = '' OR instr(lower(name), lower(:query)) > 0)
          AND (:onlyPinned = 0 OR pinnedAt > 0)
        ), page AS (
        SELECT * FROM ranked
        WHERE (:cursorId IS NULL OR
               sortPinned < :cursorPinned OR
               (sortPinned = :cursorPinned AND sortPinnedAt < :cursorPinnedAt) OR
               (sortPinned = :cursorPinned AND sortPinnedAt = :cursorPinnedAt AND sortName > coalesce(:cursorName, '')) OR
               (sortPinned = :cursorPinned AND sortPinnedAt = :cursorPinnedAt AND sortName = coalesce(:cursorName, '') AND updatedAt < :cursorUpdatedAt) OR
               (sortPinned = :cursorPinned AND sortPinnedAt = :cursorPinnedAt AND sortName = coalesce(:cursorName, '') AND updatedAt = :cursorUpdatedAt AND id < :cursorId))
        ORDER BY sortPinned DESC, sortPinnedAt DESC, sortName ASC, updatedAt DESC, id DESC
        LIMIT :limit
        )
        SELECT p.id, p.name, p.coverImagePath, p.pinnedAt, p.updatedAt, p.genreTags, p.preview,
               count(e.id) AS entryCount,
               sum(CASE WHEN e.entryType = 'character' THEN 1 ELSE 0 END) AS characterCount,
               sum(CASE WHEN e.entryType = 'location' THEN 1 ELSE 0 END) AS locationCount,
               sum(CASE WHEN e.entryType = 'faction' THEN 1 ELSE 0 END) AS factionCount
        FROM page p LEFT JOIN encyclopedia_entries e ON e.encyclopediaId = p.id
        GROUP BY p.id
        ORDER BY p.sortPinned DESC, p.sortPinnedAt DESC, p.sortName ASC, p.updatedAt DESC, p.id DESC
        """,
    )
    suspend fun getLibraryPage(
        query: String, cursorPinned: Int?, cursorPinnedAt: Long?, cursorUpdatedAt: Long?,
        cursorId: Long?, limit: Int,
        onlyPinned: Boolean = false, sort: String = "pinned", cursorName: String? = null,
    ): List<EncyclopediaLibraryItem>

    @Query(
        """
        SELECT id, name, pinnedAt, updatedAt FROM world_encyclopedias
        WHERE (:query = '' OR instr(lower(name), lower(:query)) > 0)
          AND (:cursorId IS NULL OR
               (CASE WHEN pinnedAt > 0 THEN 1 ELSE 0 END) < :cursorPinned OR
               ((CASE WHEN pinnedAt > 0 THEN 1 ELSE 0 END) = :cursorPinned AND pinnedAt < :cursorPinnedAt) OR
               ((CASE WHEN pinnedAt > 0 THEN 1 ELSE 0 END) = :cursorPinned AND pinnedAt = :cursorPinnedAt AND updatedAt < :cursorUpdatedAt) OR
               ((CASE WHEN pinnedAt > 0 THEN 1 ELSE 0 END) = :cursorPinned AND pinnedAt = :cursorPinnedAt AND updatedAt = :cursorUpdatedAt AND id < :cursorId))
        ORDER BY CASE WHEN pinnedAt > 0 THEN 1 ELSE 0 END DESC, pinnedAt DESC, updatedAt DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun getCharacterFilterPage(
        query: String, cursorPinned: Int?, cursorPinnedAt: Long?, cursorUpdatedAt: Long?,
        cursorId: Long?, limit: Int,
    ): List<EncyclopediaFilterOption>

    @Query("SELECT name FROM world_encyclopedias WHERE id = :id")
    suspend fun getNameById(id: Long): String?

    /** Import binding scans only the lightweight name projection with a stable ID keyset. */
    @Query("SELECT id, name FROM world_encyclopedias WHERE id > :afterId ORDER BY id LIMIT :limit")
    suspend fun getNameOptionsPage(afterId: Long, limit: Int): List<EncyclopediaNameOption>

    @Query("SELECT id, name FROM world_encyclopedias WHERE id IN (:ids)")
    suspend fun getNameOptionsByIds(ids: List<Long>): List<EncyclopediaNameOption>

    @Query("SELECT * FROM world_encyclopedias ORDER BY CASE WHEN pinnedAt > 0 THEN 0 ELSE 1 END, pinnedAt DESC, updatedAt DESC")
    suspend fun getAll(): List<EncyclopediaEntity>

    @Query(
        """SELECT * FROM world_encyclopedias
        WHERE (:cursorId IS NULL
          OR (CASE WHEN pinnedAt > 0 THEN 0 ELSE 1 END) > :cursorGroup
          OR ((CASE WHEN pinnedAt > 0 THEN 0 ELSE 1 END) = :cursorGroup AND pinnedAt < :cursorPinnedAt)
          OR ((CASE WHEN pinnedAt > 0 THEN 0 ELSE 1 END) = :cursorGroup AND pinnedAt = :cursorPinnedAt AND updatedAt < :cursorUpdatedAt)
          OR ((CASE WHEN pinnedAt > 0 THEN 0 ELSE 1 END) = :cursorGroup AND pinnedAt = :cursorPinnedAt AND updatedAt = :cursorUpdatedAt AND id < :cursorId))
        ORDER BY CASE WHEN pinnedAt > 0 THEN 0 ELSE 1 END ASC, pinnedAt DESC, updatedAt DESC, id DESC
        LIMIT :limit""",
    )
    suspend fun getExportPage(
        cursorGroup: Int?, cursorPinnedAt: Long?, cursorUpdatedAt: Long?, cursorId: Long?, limit: Int,
    ): List<EncyclopediaEntity>

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
