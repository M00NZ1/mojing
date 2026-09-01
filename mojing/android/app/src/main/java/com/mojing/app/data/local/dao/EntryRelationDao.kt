package com.mojing.app.data.local.dao

import androidx.room.*
import com.mojing.app.data.local.entity.EntryRelationEntity

@Dao
interface EntryRelationDao {
    @Query("SELECT * FROM entry_relations WHERE encyclopediaId = :encId")
    suspend fun getByEncyclopedia(encId: Long): List<EntryRelationEntity>

    @Query("SELECT * FROM entry_relations WHERE fromEntryId = :entryId OR toEntryId = :entryId")
    suspend fun getByEntry(entryId: Long): List<EntryRelationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: EntryRelationEntity): Long

    @Query("DELETE FROM entry_relations WHERE id = :id")
    suspend fun delete(id: Long)
}
