package com.mojing.app.data.local.dao

import androidx.room.*
import com.mojing.app.data.local.entity.TimelineEventEntity

@Dao
interface TimelineEventDao {
    @Query("SELECT * FROM timeline_events WHERE encyclopediaId = :encId ORDER BY sortOrder ASC")
    suspend fun getByEncyclopedia(encId: Long): List<TimelineEventEntity>

    @Query("""SELECT * FROM timeline_events WHERE encyclopediaId = :encId
        AND (:source = '' OR (:source = 'standalone' AND entryId IS NULL) OR (:source = 'linked' AND entryId IS NOT NULL))
        AND (:cursorId IS NULL OR sortOrder > :cursorOrder OR (sortOrder = :cursorOrder AND id > :cursorId))
        ORDER BY sortOrder ASC, id ASC LIMIT :limit""")
    suspend fun getPage(encId: Long, cursorOrder: Int?, cursorId: Long?, limit: Int, source: String = ""): List<TimelineEventEntity>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM timeline_events WHERE encyclopediaId = :encId")
    suspend fun maxSortOrder(encId: Long): Int

    @Query("SELECT * FROM timeline_events WHERE id = :id")
    suspend fun getById(id: Long): TimelineEventEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TimelineEventEntity): Long

    @Query("""UPDATE timeline_events SET title = :title, description = :description,
        eventTime = :eventTime, sortOrder = :sortOrder
        WHERE id = :id AND encyclopediaId = :encId AND entryId IS NULL""")
    suspend fun updateStandalone(
        id: Long,
        encId: Long,
        title: String,
        description: String,
        eventTime: String,
        sortOrder: Int,
    ): Int

    @Query("DELETE FROM timeline_events WHERE id = :id")
    suspend fun delete(id: Long)
}
