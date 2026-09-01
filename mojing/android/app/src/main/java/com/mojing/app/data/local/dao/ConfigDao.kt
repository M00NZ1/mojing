package com.mojing.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mojing.app.data.local.entity.ConfigEntity

@Dao
interface ConfigDao {
    @Query("SELECT * FROM app_config WHERE `key` = :key")
    suspend fun get(key: String): ConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(entity: ConfigEntity)

    @Query("DELETE FROM app_config WHERE `key` = :key")
    suspend fun delete(key: String)
}
