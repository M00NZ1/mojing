package com.mojing.app.data.local.dao

import androidx.room.*
import com.mojing.app.data.local.entity.EntryRelationEntity

/** One edge and its other endpoint, without entry bodies or relation metadata. */
data class EntryRelatedItem(
    val id: Long,
    val fromEntryId: Long,
    val toEntryId: Long,
    val relationType: String,
    val label: String,
    val otherEntryId: Long,
    val title: String,
    val entryType: String,
)

@Dao
interface EntryRelationDao {
    @Query("SELECT * FROM entry_relations WHERE encyclopediaId = :encId")
    suspend fun getByEncyclopedia(encId: Long): List<EntryRelationEntity>

    @Query("SELECT * FROM entry_relations WHERE encyclopediaId = :encId AND id < :beforeId ORDER BY id DESC LIMIT :limit")
    suspend fun getPage(encId: Long, beforeId: Long, limit: Int): List<EntryRelationEntity>

    /** Match either endpoint in this world before applying the bounded ID page. */
    @Query("""SELECT r.* FROM entry_relations r
        JOIN encyclopedia_entries fromEntry ON fromEntry.id = r.fromEntryId AND fromEntry.encyclopediaId = :encId
        JOIN encyclopedia_entries toEntry ON toEntry.id = r.toEntryId AND toEntry.encyclopediaId = :encId
        WHERE r.encyclopediaId = :encId AND r.id < :beforeId
        AND (fromEntry.entryType = :entryType OR toEntry.entryType = :entryType)
        ORDER BY r.id DESC LIMIT :limit""")
    suspend fun getTypePage(encId: Long, beforeId: Long, entryType: String, limit: Int): List<EntryRelationEntity>

    @Query("SELECT * FROM entry_relations WHERE fromEntryId = :entryId OR toEntryId = :entryId")
    suspend fun getByEntry(entryId: Long): List<EntryRelationEntity>

    @Query("""SELECT r.id, r.fromEntryId, r.toEntryId, r.relationType,
        substr(r.label, 1, 240) AS label, other.id AS otherEntryId, other.title, other.entryType
        FROM entry_relations r
        JOIN encyclopedia_entries anchor ON anchor.id = :entryId AND anchor.encyclopediaId = :encId
        JOIN encyclopedia_entries other ON other.id = CASE WHEN r.fromEntryId = :entryId THEN r.toEntryId ELSE r.fromEntryId END
            AND other.encyclopediaId = :encId
        WHERE r.encyclopediaId = :encId AND (r.fromEntryId = :entryId OR r.toEntryId = :entryId)
            AND r.id < :beforeId
        ORDER BY r.id DESC LIMIT :limit""")
    suspend fun getEntryPage(encId: Long, entryId: Long, beforeId: Long, limit: Int): List<EntryRelatedItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: EntryRelationEntity): Long

    /** Validate both endpoints and insert atomically so cross-encyclopedia edges cannot be written. */
    @Transaction
    suspend fun upsertIfEndpointsBelongToEncyclopedia(entity: EntryRelationEntity): Boolean {
        if (entity.fromEntryId == entity.toEntryId) return false
        val endpointCount = countEndpointsInEncyclopedia(
            entity.encyclopediaId,
            listOf(entity.fromEntryId, entity.toEntryId),
        )
        if (endpointCount != 2) return false
        upsert(entity)
        return true
    }

    @Query("SELECT COUNT(*) FROM encyclopedia_entries WHERE encyclopediaId = :encId AND id IN (:entryIds)")
    suspend fun countEndpointsInEncyclopedia(encId: Long, entryIds: List<Long>): Int

    @Query("DELETE FROM entry_relations WHERE id = :id")
    suspend fun delete(id: Long)
}
