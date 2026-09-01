package com.mojing.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "entry_relations",
    foreignKeys = [
        ForeignKey(EncyclopediaEntity::class, ["id"], ["encyclopediaId"], ForeignKey.CASCADE),
        ForeignKey(EncyclopediaEntryEntity::class, ["id"], ["fromEntryId"], ForeignKey.CASCADE),
        ForeignKey(EncyclopediaEntryEntity::class, ["id"], ["toEntryId"], ForeignKey.CASCADE)
    ],
    indices = [Index("encyclopediaId"), Index("fromEntryId"), Index("toEntryId")]
)
data class EntryRelationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val encyclopediaId: Long,
    val fromEntryId: Long,
    val toEntryId: Long,
    val relationType: String = "关联",
    val label: String = "",
    val metadataJson: String = "{}",
    val createdAt: Long = System.currentTimeMillis()
)
