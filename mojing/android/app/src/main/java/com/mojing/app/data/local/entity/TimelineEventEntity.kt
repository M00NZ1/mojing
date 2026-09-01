package com.mojing.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "timeline_events",
    foreignKeys = [
        ForeignKey(EncyclopediaEntity::class, ["id"], ["encyclopediaId"], ForeignKey.CASCADE),
        ForeignKey(EncyclopediaEntryEntity::class, ["id"], ["entryId"], ForeignKey.CASCADE)
    ],
    indices = [Index("encyclopediaId"), Index("entryId")]
)
data class TimelineEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val encyclopediaId: Long,
    val entryId: Long? = null,
    val title: String = "",
    val description: String = "",
    val eventTime: String = "",
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
