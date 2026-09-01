package com.mojing.app.data.local.dao

import androidx.room.*
import com.mojing.app.data.local.entity.EntryVersionEntity

@Dao
interface EntryVersionDao {
    @Query("SELECT * FROM entry_versions WHERE entryId = :entryId ORDER BY version DESC")
    suspend fun getByEntry(entryId: Long): List<EntryVersionEntity>

    @Query("SELECT COALESCE(MAX(version), 0) FROM entry_versions WHERE entryId = :entryId")
    suspend fun maxVersionForEntry(entryId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: EntryVersionEntity): Long
}
