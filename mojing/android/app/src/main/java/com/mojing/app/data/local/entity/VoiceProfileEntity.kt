package com.mojing.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "voice_profiles")
data class VoiceProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val provider: String = "xtts_v2",
    val referenceAudioPath: String = "",
    val language: String = "zh-cn",
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
