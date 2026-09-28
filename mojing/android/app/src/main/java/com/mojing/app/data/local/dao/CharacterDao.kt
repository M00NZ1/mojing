package com.mojing.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mojing.app.data.local.entity.CharacterEntity
import kotlinx.coroutines.flow.Flow

data class NewSessionCharacterOption(
    val id: Long,
    val name: String,
    val pinnedAt: Long,
    val favorite: Boolean,
    val createdAt: Long,
)

/** Only fields rendered by the character library; credentials and full prompts stay in Room. */
data class CharacterListItem(
    val id: Long,
    val name: String,
    val personaPreview: String,
    val avatarColor: String,
    val avatarImagePath: String,
    val cardImagePath: String,
    val boundEncyclopediaId: Long,
    val pinnedAt: Long,
    val favorite: Boolean,
    val createdAt: Long,
)

@Dao
interface CharacterDao {
    @Query(
        """
        SELECT id, name, substr(personaPrompt, 1, 72) AS personaPreview,
               avatarColor, avatarImagePath, cardImagePath, boundEncyclopediaId,
               pinnedAt, favorite, createdAt
        FROM characters
        WHERE (:encyclopediaId IS NULL OR boundEncyclopediaId = :encyclopediaId)
          AND (:cursorId IS NULL OR
               (CASE WHEN pinnedAt > 0 THEN 1 ELSE 0 END) < :cursorPinned OR
               ((CASE WHEN pinnedAt > 0 THEN 1 ELSE 0 END) = :cursorPinned AND pinnedAt < :cursorPinnedAt) OR
               ((CASE WHEN pinnedAt > 0 THEN 1 ELSE 0 END) = :cursorPinned AND pinnedAt = :cursorPinnedAt AND favorite < :cursorFavorite) OR
               ((CASE WHEN pinnedAt > 0 THEN 1 ELSE 0 END) = :cursorPinned AND pinnedAt = :cursorPinnedAt AND favorite = :cursorFavorite AND createdAt < :cursorCreatedAt) OR
               ((CASE WHEN pinnedAt > 0 THEN 1 ELSE 0 END) = :cursorPinned AND pinnedAt = :cursorPinnedAt AND favorite = :cursorFavorite AND createdAt = :cursorCreatedAt AND id < :cursorId))
        ORDER BY CASE WHEN pinnedAt > 0 THEN 1 ELSE 0 END DESC, pinnedAt DESC, favorite DESC, createdAt DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun getLibraryRecommendedPage(
        encyclopediaId: Long?, cursorPinned: Int?, cursorPinnedAt: Long?, cursorFavorite: Boolean?,
        cursorCreatedAt: Long?, cursorId: Long?, limit: Int,
    ): List<CharacterListItem>

    @Query(
        """
        SELECT id, name, substr(personaPrompt, 1, 72) AS personaPreview,
               avatarColor, avatarImagePath, cardImagePath, boundEncyclopediaId,
               pinnedAt, favorite, createdAt
        FROM characters
        WHERE (:encyclopediaId IS NULL OR boundEncyclopediaId = :encyclopediaId)
          AND (:cursorId IS NULL OR
               (CASE WHEN pinnedAt > 0 THEN 1 ELSE 0 END) < :cursorPinned OR
               ((CASE WHEN pinnedAt > 0 THEN 1 ELSE 0 END) = :cursorPinned AND createdAt < :cursorCreatedAt) OR
               ((CASE WHEN pinnedAt > 0 THEN 1 ELSE 0 END) = :cursorPinned AND createdAt = :cursorCreatedAt AND id < :cursorId))
        ORDER BY CASE WHEN pinnedAt > 0 THEN 1 ELSE 0 END DESC, createdAt DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun getLibraryRecentPage(
        encyclopediaId: Long?, cursorPinned: Int?, cursorCreatedAt: Long?, cursorId: Long?, limit: Int,
    ): List<CharacterListItem>

    @Query(
        """
        SELECT id, name, substr(personaPrompt, 1, 72) AS personaPreview,
               avatarColor, avatarImagePath, cardImagePath, boundEncyclopediaId,
               pinnedAt, favorite, createdAt
        FROM characters
        WHERE (:encyclopediaId IS NULL OR boundEncyclopediaId = :encyclopediaId)
          AND (:cursorId IS NULL OR
               (CASE WHEN pinnedAt > 0 THEN 1 ELSE 0 END) < :cursorPinned OR
               ((CASE WHEN pinnedAt > 0 THEN 1 ELSE 0 END) = :cursorPinned AND trim(name) COLLATE LOCALIZED > trim(:cursorName) COLLATE LOCALIZED) OR
               ((CASE WHEN pinnedAt > 0 THEN 1 ELSE 0 END) = :cursorPinned AND trim(name) COLLATE LOCALIZED = trim(:cursorName) COLLATE LOCALIZED AND id > :cursorId))
        ORDER BY CASE WHEN pinnedAt > 0 THEN 1 ELSE 0 END DESC, trim(name) COLLATE LOCALIZED ASC, id ASC
        LIMIT :limit
        """,
    )
    suspend fun getLibraryNamePage(
        encyclopediaId: Long?, cursorPinned: Int?, cursorName: String?, cursorId: Long?, limit: Int,
    ): List<CharacterListItem>

    @Query("SELECT * FROM characters ORDER BY CASE WHEN pinnedAt > 0 THEN 0 ELSE 1 END, pinnedAt DESC, favorite DESC, createdAt DESC, id DESC")
    fun observeAll(): Flow<List<CharacterEntity>>

    @Query(
        """
        SELECT * FROM characters
        WHERE boundEncyclopediaId = :encyclopediaId
        ORDER BY CASE WHEN pinnedAt > 0 THEN 0 ELSE 1 END, pinnedAt DESC, favorite DESC, createdAt DESC, id DESC
        """,
    )
    fun observeByEncyclopedia(encyclopediaId: Long): Flow<List<CharacterEntity>>

    @Query(
        """
        SELECT * FROM characters
        WHERE boundEncyclopediaId > 0
        ORDER BY CASE WHEN pinnedAt > 0 THEN 0 ELSE 1 END, pinnedAt DESC, favorite DESC, createdAt DESC, id DESC
        """,
    )
    suspend fun getAllBound(): List<CharacterEntity>

    @Query("SELECT COUNT(*) FROM characters WHERE (:encyclopediaId IS NULL OR boundEncyclopediaId <= 0 OR boundEncyclopediaId = :encyclopediaId)")
    suspend fun countForNewSession(encyclopediaId: Long?): Int

    @Query("SELECT id FROM characters WHERE (:encyclopediaId IS NULL OR boundEncyclopediaId <= 0 OR boundEncyclopediaId = :encyclopediaId) ORDER BY id DESC LIMIT 2")
    suspend fun firstIdsForNewSession(encyclopediaId: Long?): List<Long>

    @Query("SELECT id FROM characters WHERE id IN (:ids) AND (:encyclopediaId IS NULL OR boundEncyclopediaId <= 0 OR boundEncyclopediaId = :encyclopediaId)")
    suspend fun existingIdsForNewSession(ids: List<Long>, encyclopediaId: Long?): List<Long>

    @Query("SELECT COALESCE(MAX(id), 0) FROM characters")
    suspend fun maxId(): Long

    @Query("SELECT id FROM characters WHERE id > :afterId AND (:encyclopediaId IS NULL OR boundEncyclopediaId <= 0 OR boundEncyclopediaId = :encyclopediaId) ORDER BY id DESC LIMIT 2")
    suspend fun newIdsForNewSession(afterId: Long, encyclopediaId: Long?): List<Long>

    /** Only the visible picker page leaves Room; prompts and provider credentials stay in the database. */
    @Query(
        """
        SELECT id, name, pinnedAt, favorite, createdAt FROM characters
        WHERE (:encyclopediaId IS NULL OR boundEncyclopediaId <= 0 OR boundEncyclopediaId = :encyclopediaId)
          AND (:query = '' OR instr(lower(name), lower(:query)) > 0)
          AND (:cursorId IS NULL OR pinnedAt < :cursorPinnedAt
            OR (pinnedAt = :cursorPinnedAt AND favorite < :cursorFavorite)
            OR (pinnedAt = :cursorPinnedAt AND favorite = :cursorFavorite AND createdAt < :cursorCreatedAt)
            OR (pinnedAt = :cursorPinnedAt AND favorite = :cursorFavorite AND createdAt = :cursorCreatedAt AND id < :cursorId))
        ORDER BY pinnedAt DESC, favorite DESC, createdAt DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun getNewSessionPickerPage(
        encyclopediaId: Long?, query: String,
        cursorPinnedAt: Long?, cursorFavorite: Boolean?, cursorCreatedAt: Long?, cursorId: Long?,
        limit: Int,
    ): List<NewSessionCharacterOption>

    @Query("SELECT * FROM characters ORDER BY CASE WHEN pinnedAt > 0 THEN 0 ELSE 1 END, pinnedAt DESC, favorite DESC, createdAt DESC, id DESC")
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
