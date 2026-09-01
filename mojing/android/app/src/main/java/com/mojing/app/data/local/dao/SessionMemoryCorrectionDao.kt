package com.mojing.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mojing.app.data.local.entity.SessionMemoryCorrectionEntity

@Dao
interface SessionMemoryCorrectionDao {
    @Query(
        "SELECT * FROM session_memory_corrections " +
            "WHERE sessionId = :sessionId AND (branchId IS NULL OR branchId = :branchId) " +
            "ORDER BY createdAt ASC, id ASC",
    )
    suspend fun getVisible(sessionId: Long, branchId: String): List<SessionMemoryCorrectionEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: SessionMemoryCorrectionEntity): Long

    @Update
    suspend fun update(entity: SessionMemoryCorrectionEntity)

    @Query("SELECT * FROM session_memory_corrections WHERE sessionId = :sessionId AND id = :id LIMIT 1")
    suspend fun getById(sessionId: Long, id: Long): SessionMemoryCorrectionEntity?

    @Query("DELETE FROM session_memory_corrections WHERE sessionId = :sessionId AND id = :id")
    suspend fun deleteById(sessionId: Long, id: Long): Int
}
