package com.mojing.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mojing.app.data.local.entity.EncyclopediaEntryEntity

@Dao
interface EncyclopediaEntryDao {
    @Query("SELECT id FROM encyclopedia_entries WHERE encyclopediaId = :encId AND sourceSessionId = :sessionId AND confidence = 'inferred' AND title = :title AND content = :content AND metaJson = :meta LIMIT 1")
    suspend fun findSedimentDuplicate(encId: Long, sessionId: Long, title: String, content: String, meta: String): Long?

    @Query("SELECT * FROM encyclopedia_entries WHERE encyclopediaId = :encId ORDER BY id ASC")
    suspend fun getByEncyclopedia(encId: Long): List<EncyclopediaEntryEntity>

    /** 批量生成 digest：按精选与更新时间取池子，避免大库全表扫描。 */
    @Query(
        """
        SELECT * FROM encyclopedia_entries
        WHERE encyclopediaId = :encId
        ORDER BY isFeatured DESC, updatedAt DESC
        LIMIT :limit
        """,
    )
    suspend fun listRecentForDigest(encId: Long, limit: Int): List<EncyclopediaEntryEntity>

    @Query(
        """
        SELECT title FROM encyclopedia_entries
        WHERE encyclopediaId = :encId AND entryType = :entryType
          AND TRIM(title) != ''
        ORDER BY updatedAt DESC
        LIMIT :limit
        """,
    )
    suspend fun listTitlesByTypeRecent(encId: Long, entryType: String, limit: Int): List<String>

    /** 会话沉淀等推断条目，或已绑定来源会话的条目（对齐 Web「沉淀」叙事 Tab） */
    @Query(
        """
        SELECT * FROM encyclopedia_entries
        WHERE encyclopediaId = :encId
          AND (confidence = 'inferred' OR sourceSessionId IS NOT NULL)
        ORDER BY updatedAt DESC
        """
    )
    suspend fun getSedimentEntries(encId: Long): List<EncyclopediaEntryEntity>

    @Query("SELECT * FROM encyclopedia_entries WHERE encyclopediaId = :encId AND entryType = :type ORDER BY id ASC")
    suspend fun getByType(encId: Long, type: String): List<EncyclopediaEntryEntity>

    @Query("SELECT * FROM encyclopedia_entries WHERE id = :id")
    suspend fun getById(id: Long): EncyclopediaEntryEntity?

    @Upsert
    suspend fun upsert(entity: EncyclopediaEntryEntity): Long

    @Query("DELETE FROM encyclopedia_entries WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM encyclopedia_entries WHERE encyclopediaId = :encId AND (title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%') ORDER BY title ASC")
    suspend fun search(encId: Long, query: String): List<EncyclopediaEntryEntity>
}
