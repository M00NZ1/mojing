package com.mojing.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "prompt_templates",
    indices = [Index("templateId", unique = true)]
)
data class PromptTemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val templateId: String = "",
    val label: String = "",
    val category: String = "通用",
    val scope: String = "story",
    val description: String = "",
    val systemPrompt: String = "",
    val userPrompt: String = "",
    val variablesJson: String = "[]",
    val outputFormat: String = "",
    val version: Int = 1,
    val isBuiltin: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
