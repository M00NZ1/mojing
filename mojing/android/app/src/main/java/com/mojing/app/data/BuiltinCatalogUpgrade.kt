package com.mojing.app.data

import android.content.Context
import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojing.app.R
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.*
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Retires only an exact, unused v1 catalog. Modified or referenced graphs remain user data. */
class BuiltinCatalogUpgrade @Inject constructor(
    private val db: AppDatabase,
    @param:ApplicationContext private val context: Context,
    private val secureStorage: SecureStorage,
) {
    private val gson = Gson()

    suspend fun installCurrent(seedDataManager: SeedDataManager) = db.withTransaction {
        run()
        seedDataManager.seedIfNeeded()
    }

    /** Restore the archived rows only when their original IDs are still unused. Never overwrite user data. */
    suspend fun restoreRetiredCatalog() = db.withTransaction {
        val raw = db.configDao().get("builtin_catalog_retired_v1")?.valueJson ?: return@withTransaction
        val snapshot = JsonParser.parseString(raw).asJsonObject
        check(snapshot.get("version").asInt == 1)
        val enc = snapshot.get("encyclopedia")?.let { gson.fromJson(it, EncyclopediaEntity::class.java) }
        val characters = snapshot.getAsJsonArray("characters")?.map { gson.fromJson(it, CharacterEntity::class.java) }.orEmpty()
        val entries = snapshot.getAsJsonArray("entries")?.map { gson.fromJson(it, EncyclopediaEntryEntity::class.java) }.orEmpty()
        val timeline = snapshot.getAsJsonArray("timeline")?.map { gson.fromJson(it, TimelineEventEntity::class.java) }.orEmpty()
        val relations = snapshot.getAsJsonArray("relations")?.map { gson.fromJson(it, EntryRelationEntity::class.java) }.orEmpty()
        val templates = snapshot.getAsJsonArray("templates")?.map { gson.fromJson(it, WorldTemplateEntity::class.java) }.orEmpty()
        val rows = mapOf("world_encyclopedias" to listOfNotNull(enc?.id), "characters" to characters.map { it.id },
            "encyclopedia_entries" to entries.map { it.id }, "timeline_events" to timeline.map { it.id },
            "entry_relations" to relations.map { it.id }, "world_templates" to templates.map { it.id })
        check(rows.all { (table, ids) -> ids.none { exists("SELECT 1 FROM $table WHERE id = ?", it) } }) {
            "旧示例 ID 已被占用，恢复已停止，当前数据未改变"
        }
        enc?.let { db.encyclopediaDao().upsert(it) }
        characters.forEach { db.characterDao().upsert(it) }
        entries.forEach { db.encyclopediaEntryDao().upsert(it) }
        timeline.forEach { db.timelineEventDao().upsert(it) }
        relations.forEach { db.entryRelationDao().upsert(it) }
        templates.forEach { db.worldTemplateDao().upsert(it) }
    }

    suspend fun run() = db.withTransaction {
        if (db.configDao().get("builtin_catalog_retired_v1") != null) return@withTransaction
        val old = context.resources.openRawResource(R.raw.legacy_seed_v1).bufferedReader().use {
            gson.fromJson(it, SeedDataManager.SeedData::class.java)
        }
        val source = checkNotNull(old.encyclopedia)
        val enc = db.encyclopediaDao().getAll().singleOrNull { it.isOfficial && it.name == source.name }
        val snapshot = JsonObject().apply { addProperty("version", 1) }
        if (enc != null && secureStorage.defaultEncyclopediaIdForAi != enc.id && encyclopediaUntouched(enc, source)) {
            val characters = db.characterDao().getAll().filter { it.boundEncyclopediaId == enc.id }
            val entries = db.encyclopediaEntryDao().getByEncyclopedia(enc.id)
            val timeline = db.timelineEventDao().getByEncyclopedia(enc.id)
            val relations = db.entryRelationDao().getByEncyclopedia(enc.id)
            val byTitle = entries.associateBy { it.title }
            val pristineCharacters = characters.size == old.characters.size && characters.all { c ->
                old.characters.singleOrNull { it.name == c.name }?.let { characterUntouched(c, it) } == true
            }
            val pristineEntries = entries.size == source.entries.size + characters.size && entries.all { entry ->
                val seed = source.entries.singleOrNull { it.title == entry.title }
                if (seed != null) entryUntouched(entry, seed) else {
                    val c = characters.singleOrNull { it.name == entry.title }
                    c != null && entry == EncyclopediaEntryEntity(id = entry.id, encyclopediaId = enc.id,
                        title = c.name, entryType = "character", summary = c.personaPrompt.trim().lineSequence()
                            .map(String::trim).firstOrNull(String::isNotBlank).orEmpty().take(400),
                        content = c.personaPrompt.trim().take(8000), metaJson = entry.metaJson,
                        createdAt = entry.createdAt, updatedAt = entry.updatedAt) &&
                        JsonParser.parseString(entry.metaJson) == JsonObject().apply { addProperty("linkedCharacterId", c.id) }
                }
            }
            val pristineTimeline = timeline.size == source.timelineEvents.size && timeline.all { t ->
                source.timelineEvents.singleOrNull { it.title == t.title }?.let { s ->
                    t == TimelineEventEntity(id = t.id, encyclopediaId = enc.id,
                        entryId = byTitle[s.entryTitle]?.id, title = s.title, description = s.description,
                        eventTime = s.eventTime, sortOrder = s.sortOrder, createdAt = t.createdAt)
                } == true
            }
            val pristineRelations = relations.size == source.relations.size && relations.all { r ->
                source.relations.any { s -> r == EntryRelationEntity(id = r.id, encyclopediaId = enc.id,
                    fromEntryId = byTitle[s.fromTitle]?.id ?: -1, toEntryId = byTitle[s.toTitle]?.id ?: -1,
                    relationType = s.relationType, label = s.label, createdAt = r.createdAt) }
            }
            val unused = !exists("SELECT 1 FROM session_worlds WHERE encyclopediaId = ? LIMIT 1", enc.id) &&
                !exists("SELECT 1 FROM generation_tasks WHERE targetEncyclopediaId = ? LIMIT 1", enc.id) &&
                !exists("SELECT 1 FROM entry_versions WHERE entryId IN (SELECT id FROM encyclopedia_entries WHERE encyclopediaId = ?) LIMIT 1", enc.id) &&
                !exists("SELECT 1 FROM entry_relations r JOIN encyclopedia_entries e ON e.id = r.fromEntryId OR e.id = r.toEntryId WHERE e.encyclopediaId = ? AND r.encyclopediaId != e.encyclopediaId LIMIT 1", enc.id) &&
                !exists("SELECT 1 FROM timeline_events t JOIN encyclopedia_entries e ON e.id = t.entryId WHERE e.encyclopediaId = ? AND t.encyclopediaId != e.encyclopediaId LIMIT 1", enc.id) &&
                characters.all { c -> listOf("session_participants", "messages", "character_profiles", "character_expressions", "session_character_states", "session_event_nodes", "llm_cost_records")
                    .none { exists("SELECT 1 FROM $it WHERE characterId = ? LIMIT 1", c.id) } &&
                    !exists("SELECT 1 FROM generation_tasks WHERE targetCharacterId = ? LIMIT 1", c.id) }
            if (pristineCharacters && pristineEntries && pristineTimeline && pristineRelations && unused) {
                snapshot.add("encyclopedia", gson.toJsonTree(enc))
                snapshot.add("characters", gson.toJsonTree(characters))
                snapshot.add("entries", gson.toJsonTree(entries))
                snapshot.add("timeline", gson.toJsonTree(timeline))
                snapshot.add("relations", gson.toJsonTree(relations))
                characters.forEach { db.characterDao().delete(it.id) }
                db.encyclopediaDao().delete(enc.id)
            }
        }
        val retiredTemplates = db.worldTemplateDao().getAll().filter { t ->
            old.templates.any { templateUntouched(t, it) } &&
                secureStorage.defaultWorldTemplateId != t.templateId &&
                !exists("SELECT 1 FROM session_worlds WHERE templateId = ? LIMIT 1", t.templateId) &&
                !exists("SELECT 1 FROM world_lore_entries WHERE worldTemplateId = ? LIMIT 1", t.id) &&
                !exists("SELECT 1 FROM generation_tasks WHERE targetWorldTemplateId = ? LIMIT 1", t.id)
        }
        snapshot.add("templates", gson.toJsonTree(retiredTemplates))
        retiredTemplates.forEach { db.worldTemplateDao().delete(it.id) }
        // Snapshot and removals share the same transaction. A failure leaves the entire old graph intact.
        db.configDao().set(ConfigEntity("builtin_catalog_retired_v1", snapshot.toString()))
    }

    private fun exists(sql: String, arg: Any): Boolean =
        db.openHelper.writableDatabase.query(sql, arrayOf(arg)).use { it.moveToFirst() }

    companion object {
        internal fun characterUntouched(c: CharacterEntity, seed: SeedDataManager.SeedCharacter): Boolean =
            c == CharacterEntity(id = c.id, name = seed.name, personaPrompt = seed.personaPrompt,
                avatarColor = seed.avatarColor, temperature = seed.temperature, maxTokens = seed.maxTokens,
                boundEncyclopediaId = c.boundEncyclopediaId, createdAt = c.createdAt, updatedAt = c.updatedAt)

        internal fun templateUntouched(t: WorldTemplateEntity, seed: SeedDataManager.SeedTemplate): Boolean =
            t == WorldTemplateEntity(id = t.id, templateId = seed.templateId, label = seed.label,
                category = seed.category, summary = seed.summary, gameplayMode = seed.gameplayMode,
                worldPrompt = seed.worldPrompt, antiCheatPrompt = seed.antiCheatPrompt, isBuiltin = true,
                createdAt = t.createdAt, updatedAt = t.updatedAt)

        private fun encyclopediaUntouched(e: EncyclopediaEntity, seed: SeedDataManager.SeedEncyclopedia): Boolean =
            e == EncyclopediaEntity(id = e.id, name = seed.name, description = seed.description,
                genreTags = seed.genreTags, gameplayMode = seed.gameplayMode, worldPrompt = seed.worldPrompt,
                isOfficial = true, createdAt = e.createdAt, updatedAt = e.updatedAt)

        private fun entryUntouched(e: EncyclopediaEntryEntity, seed: SeedDataManager.SeedEntry): Boolean =
            e == EncyclopediaEntryEntity(id = e.id, encyclopediaId = e.encyclopediaId, title = seed.title,
                entryType = seed.entryType, summary = seed.summary, content = seed.content, tags = seed.tags,
                confidence = seed.confidence, metaJson = e.metaJson, createdAt = e.createdAt, updatedAt = e.updatedAt) &&
                JsonParser.parseString(e.metaJson) == Gson().toJsonTree(seed.meta ?: emptyMap<String, Any>())
    }
}
