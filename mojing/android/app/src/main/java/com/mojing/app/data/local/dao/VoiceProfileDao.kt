package com.mojing.app.data.local.dao

import androidx.room.*
import com.mojing.app.data.local.entity.VoiceProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VoiceProfileDao {
    @Query("SELECT * FROM voice_profiles ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<VoiceProfileEntity>>

    @Query("SELECT * FROM voice_profiles ORDER BY createdAt DESC")
    suspend fun getAll(): List<VoiceProfileEntity>

    @Query("SELECT * FROM voice_profiles WHERE id = :id")
    suspend fun getById(id: Long): VoiceProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: VoiceProfileEntity): Long

    @Query("DELETE FROM voice_profiles WHERE id = :id")
    suspend fun delete(id: Long)
}
