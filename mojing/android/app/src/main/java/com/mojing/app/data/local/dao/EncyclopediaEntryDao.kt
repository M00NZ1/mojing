package com.mojing.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mojing.app.data.local.entity.EncyclopediaEntryEntity

data class EncyclopediaEntryOption(val id: Long, val title: String, val entryType: String)

/** Current relation page endpoints only; never reads entry text or metadata. */
data class EncyclopediaRelationEndpoint(val id: Long, val title: String, val entryType: String, val coverImagePath: String)

/** List presentation only; full text and metadata are read by ID when opening an entry. */
data class EncyclopediaEntryListItem(
    val id: Long,
    val encyclopediaId: Long,
    val title: String,
    val entryType: String,
    val summary: String,
    val isFeatured: Boolean,
    val coverImagePath: String,
    val updatedAt: Long,
)
data class CharacterMirrorMetadata(val id: Long, val metaJson: String)
data class EncyclopediaEntryMergeRow(val id: Long, val title: String, val entryType: String, val updatedAt: Long)
data class EncyclopediaTrimmedTitle(val trimmedTitle: String)

@Dao
interface EncyclopediaEntryDao {
    @Query("SELECT entryType AS type, COUNT(*) AS count FROM encyclopedia_entries WHERE encyclopediaId=:encId GROUP BY entryType")
    suspend fun getWorldTypeCounts(encId: Long): List<com.mojing.app.data.local.entity.WorldEntryTypeCount>
    @Query("""SELECT * FROM encyclopedia_entries WHERE encyclopediaId = :encId AND id > :afterId
        AND (:type = '' OR :type = '全部' OR entryType = :type) ORDER BY id ASC LIMIT 101""")
    suspend fun getEntryPage(encId: Long, afterId: Long, type: String): List<EncyclopediaEntryEntity>

    @Query("""SELECT id, encyclopediaId, title, entryType, summary, isFeatured, coverImagePath, updatedAt
        FROM encyclopedia_entries WHERE encyclopediaId = :encId AND id > :afterId
        AND (:type = '' OR :type = '全部' OR entryType = :type) ORDER BY id ASC LIMIT 101""")
    suspend fun getEntryListPage(encId: Long, afterId: Long, type: String): List<EncyclopediaEntryListItem>

    @Query("SELECT id, title, entryType, updatedAt FROM encyclopedia_entries WHERE encyclopediaId = :encId AND id > :afterId ORDER BY id ASC LIMIT :limit")
    suspend fun getMergePage(encId: Long, afterId: Long, limit: Int): List<EncyclopediaEntryMergeRow>

    @Query("SELECT DISTINCT trim(title) AS trimmedTitle FROM encyclopedia_entries WHERE encyclopediaId = :encId AND trim(title) IN (:titles)")
    suspend fun getExistingTrimmedTitles(encId: Long, titles: List<String>): List<EncyclopediaTrimmedTitle>

    @Query("SELECT id FROM encyclopedia_entries WHERE encyclopediaId = :encId AND trim(title) = :title ORDER BY id ASC LIMIT 1")
    suspend fun findFirstIdByTrimmedTitle(encId: Long, title: String): Long?

    @Query("SELECT COUNT(*) FROM encyclopedia_entries WHERE encyclopediaId = :encId AND (:type = '' OR :type = '全部' OR entryType = :type)")
    suspend fun countEntries(encId: Long, type: String = ""): Int

    @Query("SELECT id, title, entryType FROM encyclopedia_entries WHERE encyclopediaId = :encId AND id IN (:ids)")
    suspend fun getEntryOptionsByIds(encId: Long, ids: List<Long>): List<EncyclopediaEntryOption>

    @Query("SELECT id, title, entryType, coverImagePath FROM encyclopedia_entries WHERE encyclopediaId = :encId AND id IN (:ids)")
    suspend fun getRelationEndpointsByIds(encId: Long, ids: List<Long>): List<EncyclopediaRelationEndpoint>

    @Query("SELECT id FROM encyclopedia_entries WHERE encyclopediaId = :encId AND (:type = '' OR :type = '全部' OR entryType = :type) ORDER BY id ASC")
    suspend fun getEntryIdsForType(encId: Long, type: String): List<Long>

    @Query("""SELECT id, title, entryType FROM encyclopedia_entries
        WHERE encyclopediaId = :encId AND id > :afterId
        AND (:query = '' OR instr(lower(title), lower(:query)) > 0)
        ORDER BY id ASC LIMIT 51""")
    suspend fun getRelationOptions(encId: Long, afterId: Long, query: String): List<EncyclopediaEntryOption>

    @Query("SELECT id FROM encyclopedia_entries WHERE encyclopediaId = :encId AND sourceSessionId = :sessionId AND confidence = 'inferred' AND title = :title AND content = :content AND metaJson = :meta LIMIT 1")
    suspend fun findSedimentDuplicate(encId: Long, sessionId: Long, title: String, content: String, meta: String): Long?

    @Query("SELECT * FROM encyclopedia_entries WHERE encyclopediaId = :encId ORDER BY id ASC")
    suspend fun getByEncyclopedia(encId: Long): List<EncyclopediaEntryEntity>

    @Query("SELECT * FROM encyclopedia_entries WHERE encyclopediaId = :encId AND id > :afterId ORDER BY id ASC LIMIT :limit")
    suspend fun getExportPage(encId: Long, afterId: Long, limit: Int): List<EncyclopediaEntryEntity>

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

    /** 沉淀资料按稳定 ID 分页；确认状态在查询阶段筛选。 */
    @Query("""SELECT * FROM encyclopedia_entries WHERE encyclopediaId = :encId
        AND (confidence = 'inferred' OR sourceSessionId IS NOT NULL) AND id < :beforeId
        AND (:filter = 'all' OR (:filter = 'pending' AND confidence != 'confirmed') OR (:filter = 'confirmed' AND confidence = 'confirmed'))
        ORDER BY id DESC LIMIT 101""")
    suspend fun getSedimentPage(encId: Long, beforeId: Long, filter: String): List<EncyclopediaEntryEntity>

    @Query("""SELECT COUNT(*) FROM encyclopedia_entries WHERE encyclopediaId = :encId
        AND (confidence = 'inferred' OR sourceSessionId IS NOT NULL)
        AND (:confirmedOnly = 0 OR confidence = 'confirmed')""")
    suspend fun countSediment(encId: Long, confirmedOnly: Boolean): Int

    @Query("UPDATE encyclopedia_entries SET confidence = 'confirmed', updatedAt = :updatedAt WHERE encyclopediaId = :encId AND id IN (:ids) AND confidence != 'confirmed' AND (confidence = 'inferred' OR sourceSessionId IS NOT NULL)")
    suspend fun confirmSedimentEntries(encId: Long, ids: List<Long>, updatedAt: Long): Int

    @Query("SELECT * FROM encyclopedia_entries WHERE encyclopediaId = :encId AND entryType = :type ORDER BY id ASC")
    suspend fun getByType(encId: Long, type: String): List<EncyclopediaEntryEntity>

    /** Mirror scans never need the potentially long entry content. */
    @Query("SELECT id, metaJson FROM encyclopedia_entries WHERE encyclopediaId = :encId AND entryType = 'character' AND id > :afterId ORDER BY id ASC LIMIT :limit")
    suspend fun getCharacterMirrorMetadataPage(encId: Long, afterId: Long, limit: Int): List<CharacterMirrorMetadata>

    @Query("SELECT * FROM encyclopedia_entries WHERE id = :id")
    suspend fun getById(id: Long): EncyclopediaEntryEntity?

    @Upsert
    suspend fun upsert(entity: EncyclopediaEntryEntity): Long

    @Query("DELETE FROM encyclopedia_entries WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM encyclopedia_entries WHERE encyclopediaId = :encId AND (title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%') ORDER BY title ASC")
    suspend fun search(encId: Long, query: String): List<EncyclopediaEntryEntity>
}
