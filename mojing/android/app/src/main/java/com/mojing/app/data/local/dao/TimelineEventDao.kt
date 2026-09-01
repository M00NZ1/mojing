package com.mojing.app.data.local.dao

import androidx.room.*
import com.mojing.app.data.local.entity.TimelineEventEntity

@Dao
interface TimelineEventDao {
    @Query("SELECT * FROM timeline_events WHERE encyclopediaId = :encId ORDER BY sortOrder ASC")
    suspend fun getByEncyclopedia(encId: Long): List<TimelineEventEntity>

    @Query("SELECT * FROM timeline_events WHERE id = :id")
    suspend fun getById(id: Long): TimelineEventEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TimelineEventEntity): Long

    @Query("DELETE FROM timeline_events WHERE id = :id")
    suspend fun delete(id: Long)
}
