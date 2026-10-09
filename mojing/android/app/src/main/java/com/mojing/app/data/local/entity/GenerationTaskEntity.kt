package com.mojing.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "generation_tasks",
    indices = [
        Index("status"),
        Index("targetEncyclopediaId"),
        Index("targetCharacterId"),
        Index("targetWorldTemplateId"),
        Index("createdAt"),
    ],
)
data class GenerationTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 如 [GenerationTaskKinds.ENCYCLOPEDIA_ENTRIES] */
    val taskKind: String,
    /** 列表展示用短标题 */
    val title: String,
    val status: String,
    val progressDone: Int = 0,
    val progressTotal: Int = 0,
    val payloadJson: String,
    val errorMessage: String = "",
    val targetEncyclopediaId: Long? = null,
    val targetCharacterId: Long? = null,
    val targetWorldTemplateId: Long? = null,
    /** Versioned, allow-listed AI result snapshot. Empty for legacy/non-snapshot tasks. */
    val resultJson: String = "",
    val resultAppliedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

object GenerationTaskKinds {
    const val ENCYCLOPEDIA_ENTRIES = "encyclopedia_entries"
    const val ENCYCLOPEDIA_META_FILL = "encyclopedia_meta_fill"
    const val CHARACTER_PERSONA_AI = "character_persona_ai"
    const val WORLD_TEMPLATE_PROMPT_AI = "world_template_prompt_ai"
}

object GenerationTaskStatus {
    const val QUEUED = "QUEUED"
    const val RUNNING = "RUNNING"
    const val PAUSED = "PAUSED"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
}
