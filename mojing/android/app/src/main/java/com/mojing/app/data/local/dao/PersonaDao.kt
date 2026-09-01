package com.mojing.app.data.local.dao

import androidx.room.*
import com.mojing.app.data.local.entity.PersonaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonaDao {
    @Query("SELECT * FROM personas ORDER BY isActive DESC, createdAt DESC")
    fun observeAll(): Flow<List<PersonaEntity>>

    @Query("SELECT * FROM personas WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): PersonaEntity?

    @Query("SELECT * FROM personas WHERE id = :id")
    suspend fun getById(id: Long): PersonaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PersonaEntity): Long

    @Query("DELETE FROM personas WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE personas SET isActive = :active WHERE id = :id")
    suspend fun setActive(id: Long, active: Boolean)
}
