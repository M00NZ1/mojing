package com.mojing.app.domain.usecase

import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.dao.LegacyWorldMappingDao
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.EncyclopediaEntryEntity
import com.mojing.app.data.local.entity.LegacyLoreMappingEntity
import com.mojing.app.data.local.entity.LegacyWorldMappingEntity
import com.mojing.app.data.local.entity.WorldLoreEntryEntity
import com.mojing.app.data.local.entity.WorldTemplateEntity
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Promotes one legacy template while keeping both legacy tables intact. */
@Singleton
class PromoteWorldTemplateUseCase @Inject constructor(
    private val database: AppDatabase,
) {
    suspend operator fun invoke(worldTemplateId: Long): EncyclopediaEntity = database.withTransaction {
        val mappingDao = database.legacyWorldMappingDao()
        val template = database.worldTemplateDao().getById(worldTemplateId)
            ?: error("世界模板不存在: $worldTemplateId")

        mappingDao.getByTemplateId(worldTemplateId)?.let { mapping ->
            return@withTransaction database.encyclopediaDao().getById(mapping.encyclopediaId)
                ?: error("旧世界映射指向不存在的百科: ${mapping.encyclopediaId}")
        }

        val lore = database.worldLoreEntryDao().getByTemplate(worldTemplateId)
        val sourceHash = hashWorld(template, lore)
        val encyclopediaDao = database.encyclopediaDao()
        val entryDao = database.encyclopediaEntryDao()
        val existingWorlds = encyclopediaDao.getAll()
        val canonical = encyclopediaDao.upsert(
                EncyclopediaEntity(
                    name = canonicalName(existingWorlds, template, sourceHash),
                    description = template.summary,
                    coverImagePath = template.coverImagePath,
                    isOfficial = template.isBuiltin,
                    worldPrompt = template.worldPrompt,
                    gameplayMode = template.gameplayMode,
                    antiCheatPrompt = template.antiCheatPrompt,
                    createdAt = template.createdAt,
                    updatedAt = template.updatedAt,
                ),
            ).let { id -> encyclopediaDao.getById(id) ?: error("百科创建后无法读回: $id") }

        val worldMapping = LegacyWorldMappingEntity(worldTemplateId, canonical.id, sourceHash)
        if (mappingDao.insert(worldMapping) == -1L) {
            val existing = mappingDao.getByTemplateId(worldTemplateId)
                ?: error("世界映射写入竞争后无法读回")
            return@withTransaction encyclopediaDao.getById(existing.encyclopediaId)
                ?: error("旧世界映射指向不存在的百科: ${existing.encyclopediaId}")
        }

        // Existing canonical edits are never overwritten. Only unmapped legacy Lore is copied.
        val existingEntries = entryDao.getByEncyclopedia(canonical.id).toMutableList()
        lore.forEach { legacy ->
            if (mappingDao.getLoreById(legacy.id) != null) return@forEach
            val copied = existingEntries.firstOrNull { isCopiedFrom(it, legacy) }
                ?: entryDao.upsert(toCanonicalEntry(canonical.id, template, legacy)).let { id ->
                    entryDao.getById(id) ?: error("百科条目创建后无法读回: $id")
                }
            if (existingEntries.none { it.id == copied.id }) existingEntries.add(copied)
            mappingDao.insertLore(
                LegacyLoreMappingEntity(
                    loreEntryId = legacy.id,
                    encyclopediaEntryId = copied.id,
                    sourceHash = hashLore(legacy),
                ),
            )
        }
        encyclopediaDao.upsert(canonical.copy(entryCount = existingEntries.size))
        encyclopediaDao.getById(canonical.id) ?: error("百科不存在: ${canonical.id}")
    }

    suspend operator fun invoke(template: WorldTemplateEntity): EncyclopediaEntity = invoke(template.id)

    private fun isCopiedFrom(entry: EncyclopediaEntryEntity, lore: WorldLoreEntryEntity): Boolean {
        val meta = parseObject(entry.metaJson)
        return meta.get("source")?.asString == SOURCE &&
            meta.get("source_hash")?.asString == hashLore(lore) &&
            entry.title == lore.title && entry.entryType == mapEntryType(lore.entryType) &&
            entry.summary.isEmpty() && entry.content == lore.content &&
            entry.tags == keywordsJson(lore.keywordsJson) && entry.isFeatured == lore.isCore &&
            meta.get("sort_order")?.asInt == lore.sortOrder
    }

    private fun toCanonicalEntry(
        encyclopediaId: Long,
        template: WorldTemplateEntity,
        lore: WorldLoreEntryEntity,
    ): EncyclopediaEntryEntity = EncyclopediaEntryEntity(
        encyclopediaId = encyclopediaId,
        title = lore.title,
        entryType = mapEntryType(lore.entryType),
        content = lore.content,
        tags = keywordsJson(lore.keywordsJson),
        isFeatured = lore.isCore,
        metaJson = Gson().toJson(JsonObject().apply {
            addProperty("source", SOURCE)
            addProperty("world_template_id", template.id)
            addProperty("lore_entry_id", lore.id)
            addProperty("legacy_entry_type", lore.entryType)
            add("trigger_keywords", keywords(lore.keywordsJson))
            addProperty("activation_mode", if (lore.isCore) "constant" else "normal")
            addProperty("source_trust_level", "manual")
            addProperty("source_hash", hashLore(lore))
            addProperty("migration_version", 1)
            addProperty("sort_order", lore.sortOrder)
        }),
        createdAt = lore.createdAt,
        updatedAt = lore.updatedAt,
    )

    private fun canonicalName(all: List<EncyclopediaEntity>, template: WorldTemplateEntity, hash: String): String {
        val base = template.label.trim().ifEmpty { template.templateId }
        if (all.none { it.name == base }) return base
        val stem = "$base · ${hash.take(8)}"
        if (all.none { it.name == stem }) return stem
        var index = 2
        while (all.any { it.name == "$stem-$index" }) index++
        return "$stem-$index"
    }

    private fun hashWorld(template: WorldTemplateEntity, lore: List<WorldLoreEntryEntity>): String =
        sha256(Gson().toJson(mapOf("template" to mapOf(
            "label" to template.label, "category" to template.category, "summary" to template.summary,
            "gameplay_mode" to template.gameplayMode, "world_prompt" to template.worldPrompt,
            "cover_image_path" to template.coverImagePath, "suggested_choices_json" to template.suggestedChoicesJson,
            "anti_cheat_prompt" to template.antiCheatPrompt, "is_builtin" to template.isBuiltin,
        ), "lore" to lore.map { loreSemantics(it) })))

    private fun hashLore(lore: WorldLoreEntryEntity): String = sha256(Gson().toJson(loreSemantics(lore)))

    private fun loreSemantics(lore: WorldLoreEntryEntity): Map<String, Any> = mapOf(
        "title" to lore.title, "entry_type" to lore.entryType, "keywords" to keywords(lore.keywordsJson),
        "content" to lore.content, "sort_order" to lore.sortOrder, "is_core" to lore.isCore,
    )

    private fun keywordsJson(raw: String): String = Gson().toJson(keywords(raw))
    private fun keywords(raw: String): JsonArray = runCatching {
        JsonParser.parseString(raw.ifBlank { "[]" }).asJsonArray
    }.getOrElse { JsonArray() }
    private fun parseObject(raw: String): JsonObject = runCatching {
        JsonParser.parseString(raw.ifBlank { "{}" }).asJsonObject
    }.getOrElse { JsonObject() }
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun mapEntryType(value: String): String = LEGACY_TYPES[value] ?: value.takeIf { it in CANONICAL_TYPES } ?: "other"

    private companion object {
        const val SOURCE = "legacy_world_lore"
        val CANONICAL_TYPES = setOf("character", "location", "faction", "event", "item", "skill", "profession", "concept", "species", "world", "timeline", "other")
        val LEGACY_TYPES = mapOf("人物" to "character", "地点" to "location", "势力" to "faction", "事件" to "event", "物品" to "item", "技能/法术" to "skill", "职业/等级" to "profession", "概念术语" to "concept", "设定" to "concept", "种族" to "species", "世界观总览" to "world", "时间线" to "timeline")
    }
}
