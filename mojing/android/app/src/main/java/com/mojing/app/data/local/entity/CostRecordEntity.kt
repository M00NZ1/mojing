package com.mojing.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "llm_cost_records", indices = [Index("sessionId")])
data class CostRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long? = null,
    val characterId: Long? = null,
    val modelName: String = "",
    val provider: String = "openai",
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val estimatedCost: Double = 0.0,
    val durationMs: Int = 0,
    val success: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
