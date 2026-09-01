package com.mojing.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "entry_versions",
    foreignKeys = [ForeignKey(EncyclopediaEntryEntity::class, ["id"], ["entryId"], ForeignKey.CASCADE)],
    indices = [Index("entryId")]
)
data class EntryVersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entryId: Long,
    val version: Int = 1,
    val title: String = "",
    val summary: String = "",
    val content: String = "",
    val tags: String = "",
    val metaSnapshotJson: String = "{}",
    val changeNote: String = "",
    val createdBy: String = "system",
    val createdAt: Long = System.currentTimeMillis()
)
