package com.mojing.app.data

import android.content.Context
import com.mojing.app.data.local.dao.CharacterDao
import com.mojing.app.data.local.dao.ConfigDao
import com.mojing.app.data.local.dao.EntryRelationDao
import com.mojing.app.data.local.dao.EncyclopediaDao
import com.mojing.app.data.local.dao.EncyclopediaEntryDao
import com.mojing.app.data.local.dao.TimelineEventDao
import com.mojing.app.data.local.dao.WorldTemplateDao
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.ConfigEntity
import com.mojing.app.data.local.entity.EntryRelationEntity
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.EncyclopediaEntryEntity
import com.mojing.app.data.local.entity.TimelineEventEntity
import com.mojing.app.data.local.entity.WorldTemplateEntity
import com.mojing.app.R
import com.mojing.app.domain.usecase.SaveCharacterBindingUseCase
import com.mojing.app.domain.usecase.SaveCharacterEntryUseCase
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SeedDataManager @Inject constructor(
    private val characterDao: CharacterDao,
    private val worldTemplateDao: WorldTemplateDao,
    private val encyclopediaDao: EncyclopediaDao,
    private val encyclopediaEntryDao: EncyclopediaEntryDao,
    private val entryRelationDao: EntryRelationDao,
    private val timelineEventDao: TimelineEventDao,
    private val configDao: ConfigDao,
    private val saveCharacterBinding: SaveCharacterBindingUseCase,
    private val saveCharacterEntry: SaveCharacterEntryUseCase,
    @ApplicationContext private val context: Context,
) {
    private val gson = Gson()

    data class SeedData(
        val characters: List<SeedCharacter> = emptyList(),
        val templates: List<SeedTemplate> = emptyList(),
        val encyclopedia: SeedEncyclopedia? = null,
    )

    data class SeedCharacter(
        val name: String = "", val personaPrompt: String = "",
        val avatarColor: String = "", val temperature: Float = 0.9f,
        val maxTokens: Int = 1200,
    )

    data class SeedTemplate(
        val templateId: String = "", val label: String = "", val category: String = "",
        val summary: String = "", val gameplayMode: String = "自由剧情",
        val worldPrompt: String = "", val antiCheatPrompt: String = "",
    )

    data class SeedEncyclopedia(
        val name: String = "", val description: String = "",
        val genreTags: String = "", val gameplayMode: String = "",
        val worldPrompt: String = "", val entries: List<SeedEntry> = emptyList(),
        val timelineEvents: List<SeedTimelineEvent> = emptyList(),
        val relations: List<SeedRelation> = emptyList(),
    )

    data class SeedEntry(
        val title: String = "", val entryType: String = "",
        val summary: String = "", val content: String = "",
        val tags: String = "",
        val confidence: String = "confirmed",
        val meta: Map<String, @JvmSuppressWildcards Any>? = null,
    )

    data class SeedTimelineEvent(
        val title: String = "",
        val description: String = "",
        val eventTime: String = "",
        val sortOrder: Int = 0,
        /** 可选：关联已有条目标题（含角色镜像名） */
        val entryTitle: String = "",
    )

    data class SeedRelation(
        val fromTitle: String = "",
        val toTitle: String = "",
        val relationType: String = "关联",
        val label: String = "",
    )

    suspend fun seedIfNeeded() {
        mergeBuiltinPresetsFromAsset()
        if (configDao.get("data_seeded") == null) {
            configDao.set(ConfigEntity(key = "data_seeded", valueJson = "true"))
        }
    }

    /** Versioned one-time catalog installation. Deleting a sample must stay deleted. */
    suspend fun mergeBuiltinPresetsFromAsset() {
        if (configDao.get("builtin_catalog_v2") != null) return
        val data = loadSeedData()
        installCatalog(data)
        configDao.set(ConfigEntity(key = "builtin_catalog_v2", valueJson = "true"))
    }

    internal suspend fun installCatalog(data: SeedData) {
        val encId = resolveBuiltinEncyclopediaId(data)
        seedCharacters(data.characters, encId)
        ensureSeedCharacterBindings(data.characters, encId)
        seedTemplates(data.templates)
        data.encyclopedia?.let { seedEncyclopediaExtras(it, encId) }
    }

    private fun loadSeedData(): SeedData {
        val json = context.resources.openRawResource(R.raw.seed_data)
            .bufferedReader().readText()
        return gson.fromJson(json, SeedData::class.java)
    }

    /** 优先使用 JSON 中的百科；否则建默认官方库（与旧包无 encyclopedia 字段时兼容）。 */
    private suspend fun resolveBuiltinEncyclopediaId(data: SeedData): Long {
        val fromJson = data.encyclopedia?.takeIf { it.name.isNotBlank() }?.let { enc ->
            encyclopediaDao.getAll().find { it.name == enc.name.trim() && it.isOfficial && it.worldPrompt == enc.worldPrompt }?.id
                ?: insertEncyclopediaWithEntries(enc)
        }
        if (fromJson != null && fromJson > 0L) return fromJson
        return ensureDefaultBuiltinEncyclopediaId()
    }

    private suspend fun ensureDefaultBuiltinEncyclopediaId(): Long {
        val defaultName = "墨境·内置示例百科"
        encyclopediaDao.getAll().find { it.name == defaultName }?.id?.let { return it }
        val now = System.currentTimeMillis()
        return encyclopediaDao.upsert(
            EncyclopediaEntity(
                name = defaultName,
                description = "与安装包内置示例角色配套的索引库；可自行增删条目。",
                isOfficial = true,
                genreTags = "内置,示例",
                worldPrompt = "多体裁示例角色的公用设定锚；实际剧情以角色人设与会话为准。",
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private suspend fun insertEncyclopediaWithEntries(enc: SeedEncyclopedia): Long {
        val encId = encyclopediaDao.upsert(
            EncyclopediaEntity(
                name = enc.name.trim(),
                description = enc.description,
                genreTags = enc.genreTags,
                gameplayMode = enc.gameplayMode.ifBlank { "自由剧情" },
                worldPrompt = enc.worldPrompt,
                isOfficial = true,
            ),
        )
        enc.entries.forEach { upsertSeedEntry(encId, it) }
        return encId
    }

    private suspend fun seedEncyclopediaExtras(enc: SeedEncyclopedia, encId: Long) {
        insertEncyclopediaEntriesIfNew(enc, encId)
        seedTimelineEventsIfNew(enc, encId)
        seedRelationsIfNew(enc, encId)
    }

    /** 若百科已存在则只补写尚未有的条目（按标题去重）。 */
    private suspend fun insertEncyclopediaEntriesIfNew(enc: SeedEncyclopedia, encId: Long) {
        val existingTitles = encyclopediaEntryDao.getByEncyclopedia(encId).map { it.title.trim() }.toSet()
        enc.entries.filter { it.title.trim() !in existingTitles }.forEach { upsertSeedEntry(encId, it) }
    }

    private suspend fun upsertSeedEntry(encId: Long, entry: SeedEntry) {
        val now = System.currentTimeMillis()
        saveCharacterEntry(
            EncyclopediaEntryEntity(
                encyclopediaId = encId,
                title = entry.title.trim(),
                entryType = entry.entryType.ifBlank { "world" },
                summary = entry.summary,
                content = entry.content,
                tags = entry.tags,
                confidence = entry.confidence.ifBlank { "confirmed" },
                metaJson = metaJsonFromSeed(entry.meta),
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private fun metaJsonFromSeed(meta: Map<String, Any>?): String {
        if (meta.isNullOrEmpty()) return "{}"
        return gson.toJson(meta)
    }

    private suspend fun titleToEntryId(encId: Long): Map<String, Long> =
        encyclopediaEntryDao.getByEncyclopedia(encId).associate { it.title.trim() to it.id }

    private suspend fun seedTimelineEventsIfNew(enc: SeedEncyclopedia, encId: Long) {
        if (enc.timelineEvents.isEmpty()) return
        val existingTitles = timelineEventDao.getByEncyclopedia(encId).map { it.title.trim() }.toSet()
        val entryIds = titleToEntryId(encId)
        val now = System.currentTimeMillis()
        enc.timelineEvents.filter { it.title.trim() !in existingTitles }.forEach { ev ->
            timelineEventDao.upsert(
                TimelineEventEntity(
                    encyclopediaId = encId,
                    entryId = ev.entryTitle.trim().takeIf { it.isNotBlank() }?.let { entryIds[it] },
                    title = ev.title.trim(),
                    description = ev.description,
                    eventTime = ev.eventTime,
                    sortOrder = ev.sortOrder,
                    createdAt = now,
                ),
            )
        }
    }

    private suspend fun seedRelationsIfNew(enc: SeedEncyclopedia, encId: Long) {
        if (enc.relations.isEmpty()) return
        val entryIds = titleToEntryId(encId)
        val existing = entryRelationDao.getByEncyclopedia(encId)
        val existingKeys = existing.map { "${it.fromEntryId}|${it.toEntryId}|${it.relationType}" }.toSet()
        val now = System.currentTimeMillis()
        enc.relations.forEach { rel ->
            val fromId = entryIds[rel.fromTitle.trim()] ?: return@forEach
            val toId = entryIds[rel.toTitle.trim()] ?: return@forEach
            val key = "$fromId|$toId|${rel.relationType}"
            if (key in existingKeys) return@forEach
            entryRelationDao.upsert(
                EntryRelationEntity(
                    encyclopediaId = encId,
                    fromEntryId = fromId,
                    toEntryId = toId,
                    relationType = rel.relationType.ifBlank { "关联" },
                    label = rel.label,
                    createdAt = now,
                ),
            )
        }
    }

    private suspend fun seedCharacters(characters: List<SeedCharacter>, boundEncyclopediaId: Long) {
        val existing = characterDao.getAll()
        val existingNames = existing.map { it.name }.toSet()
        val bind = boundEncyclopediaId.coerceAtLeast(0L)
        characters.filter { it.name !in existingNames }.forEach { c ->
            saveCharacterBinding(
                CharacterEntity(
                    name = c.name,
                    personaPrompt = c.personaPrompt,
                    avatarColor = c.avatarColor,
                    temperature = c.temperature,
                    maxTokens = c.maxTokens,
                    boundEncyclopediaId = bind,
                ),
            )
        }
    }

    /** 已存在且已绑定的种子角色也经统一事务入口校准镜像，兼容旧库缺失镜像。 */
    private suspend fun ensureSeedCharacterBindings(characters: List<SeedCharacter>, encId: Long) {
        if (encId <= 0L) return
        val nameSet = characters.map { it.name }.toSet()
        characterDao.getAll()
            .filter { it.name in nameSet && it.boundEncyclopediaId == encId }
            .forEach { saveCharacterBinding(it) }
    }

    private suspend fun seedTemplates(templates: List<SeedTemplate>) {
        val existing = worldTemplateDao.getAll()
        val existingIds = existing.map { it.templateId }.toSet()
        templates.filter { it.templateId !in existingIds }.forEach { t ->
            worldTemplateDao.upsert(
                WorldTemplateEntity(
                    templateId = t.templateId,
                    label = t.label,
                    category = t.category,
                    summary = t.summary,
                    gameplayMode = t.gameplayMode,
                    worldPrompt = t.worldPrompt,
                    antiCheatPrompt = t.antiCheatPrompt,
                    isBuiltin = true,
                ),
            )
        }
    }
}
