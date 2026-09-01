package com.mojing.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mojing.app.data.local.entity.SessionParticipantEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ParticipantDao {
    @Query("SELECT * FROM session_participants WHERE sessionId = :sessionId ORDER BY sortOrder ASC")
    fun observeBySession(sessionId: Long): Flow<List<SessionParticipantEntity>>

    @Query("SELECT * FROM session_participants WHERE sessionId = :sessionId")
    suspend fun getBySession(sessionId: Long): List<SessionParticipantEntity>

    @Query("SELECT * FROM session_participants WHERE id = :id")
    suspend fun getById(id: Long): SessionParticipantEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SessionParticipantEntity): Long

    @Query("DELETE FROM session_participants WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM session_participants WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: Long)
}
