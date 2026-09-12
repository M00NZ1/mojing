package com.mojing.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Stable link from an imported legacy world template to its canonical encyclopedia. */
@Entity(
    tableName = "legacy_world_mappings",
    primaryKeys = ["worldTemplateId"],
    foreignKeys = [
        ForeignKey(WorldTemplateEntity::class, ["id"], ["worldTemplateId"]),
        ForeignKey(EncyclopediaEntity::class, ["id"], ["encyclopediaId"]),
    ],
    indices = [Index("encyclopediaId")],
)
data class LegacyWorldMappingEntity(
    val worldTemplateId: Long,
    val encyclopediaId: Long,
    val sourceHash: String,
    val migrationVersion: Int = 1,
)

/** Stable link from a legacy Lore row to the copied canonical encyclopedia entry. */
@Entity(
    tableName = "legacy_lore_mappings",
    primaryKeys = ["loreEntryId"],
    foreignKeys = [
        ForeignKey(WorldLoreEntryEntity::class, ["id"], ["loreEntryId"]),
        ForeignKey(EncyclopediaEntryEntity::class, ["id"], ["encyclopediaEntryId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("encyclopediaEntryId")],
)
data class LegacyLoreMappingEntity(
    val loreEntryId: Long,
    val encyclopediaEntryId: Long?,
    val sourceHash: String,
    val migrationVersion: Int = 1,
)
