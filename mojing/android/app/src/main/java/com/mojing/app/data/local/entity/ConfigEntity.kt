package com.mojing.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_config")
data class ConfigEntity(
    @PrimaryKey val key: String,
    val valueJson: String = "{}",
    val updatedAt: Long = System.currentTimeMillis()
)
