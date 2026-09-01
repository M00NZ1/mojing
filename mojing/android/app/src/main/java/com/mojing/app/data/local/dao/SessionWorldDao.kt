package com.mojing.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mojing.app.data.local.entity.SessionWorldEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionWorldDao {
    @Query("SELECT * FROM session_worlds WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getBySession(sessionId: Long): SessionWorldEntity?

    @Query("SELECT * FROM session_worlds WHERE sessionId = :sessionId LIMIT 1")
    fun observeBySession(sessionId: Long): Flow<SessionWorldEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SessionWorldEntity): Long
}
