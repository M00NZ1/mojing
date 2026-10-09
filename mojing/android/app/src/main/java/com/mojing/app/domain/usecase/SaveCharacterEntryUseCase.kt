package com.mojing.app.domain.usecase

import androidx.room.withTransaction
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.dao.NewSessionCharacterOption
import com.mojing.app.data.local.entity.EntryVersionEntity
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.EncyclopediaEntryEntity
import com.mojing.app.domain.encyclopedia.CharacterEncyclopediaSync
import com.mojing.app.domain.encyclopedia.EncyclopediaEntryMetaMerge
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 百科条目来源的角色绑定事务入口。
 *
 * 普通条目仅保存自身；角色条目还会原子完成角色新建/改绑、旧镜像清理与 linkedCharacterId 回写。
 */
@Singleton
class SaveCharacterEntryUseCase @Inject constructor(
    private val database: AppDatabase,
) {
    private companion object { const val CHARACTER_NAME_SCAN_PAGE_SIZE = 128 }

    /** Queue results fill only fields still empty at commit time; deleted/retargeted entries stay untouched. */
    suspend fun fillMissingGeneratedFields(
        requested: EncyclopediaEntryEntity,
        result: Map<String, Any>,
        allowedMetaKeys: Set<String>,
    ): Boolean = database.withTransaction {
        val current = database.encyclopediaEntryDao().getById(requested.id) ?: return@withTransaction false
        if (current.encyclopediaId != requested.encyclopediaId || current.entryType != requested.entryType) {
            return@withTransaction false
        }
        val meta = EncyclopediaEntryMetaMerge.parseMetaJson(current.metaJson)
        val metaChanged = EncyclopediaEntryMetaMerge.mergeMetaPatch(meta, result.filterKeys { it in allowedMetaKeys })
        fun fill(value: String, key: String): String = if (value.isBlank()) {
            (result[key] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: value
        } else value
        val next = current.copy(
            title = fill(current.title, "title"),
            summary = fill(current.summary, "summary"),
            tags = fill(current.tags, "tags"),
            content = fill(current.content, "content"),
            metaJson = if (metaChanged) meta.toString() else current.metaJson,
        )
        if (next == current) return@withTransaction false
        invoke(next.copy(updatedAt = System.currentTimeMillis()))
        true
    }

    /** 编辑入口：旧正文快照与新条目共享一次事务。 */
    suspend fun saveEdited(entry: EncyclopediaEntryEntity): EncyclopediaEntryEntity = database.withTransaction {
        val previous = if (entry.id > 0L) database.encyclopediaEntryDao().getById(entry.id) else null
        require(entry.id == 0L || previous != null) { "条目已被删除，请返回百科重新打开" }
        if (previous != null && entryContentDiffers(previous, entry)) {
            val versions = database.entryVersionDao()
            versions.insert(EntryVersionEntity(
                entryId = previous.id,
                version = versions.maxVersionForEntry(previous.id) + 1,
                title = previous.title,
                summary = previous.summary,
                content = previous.content,
                tags = previous.tags,
                metaSnapshotJson = previous.metaJson,
                changeNote = previous.changeNote,
                createdBy = "local",
            ))
        }
        invoke(entry)
    }

    suspend operator fun invoke(entry: EncyclopediaEntryEntity): EncyclopediaEntryEntity =
        database.withTransaction {
            val entryDao = database.encyclopediaEntryDao()
            val characterDao = database.characterDao()
            val previous = entry.id.takeIf { it > 0L }?.let { entryDao.getById(it) }
            require(previous == null || previous.encyclopediaId == entry.encyclopediaId) {
                "百科条目不能直接跨库移动，请在目标百科重新创建或导入"
            }
            val previousLinkedId = previous
                ?.takeIf { it.entryType == "character" }
                ?.let { CharacterEncyclopediaSync.readLinkedCharacterId(it.metaJson) }
                ?.takeIf { it > 0L }

            // 使用已保存的来源身份，确认状态或可编辑的扩展资料不会触发角色绑定。
            if (previous != null && CharacterEncyclopediaSync.isConversationNote(previous)) {
                return@withTransaction upsertEntry(entry.withoutLinkedCharacterId().copy(
                    sourceSessionId = previous.sourceSessionId,
                    sourceMessageId = previous.sourceMessageId,
                ))
            }

            if (entry.entryType != "character") {
                val saved = upsertEntry(entry.withoutLinkedCharacterId())
                detachPreviousCharacter(previous, previousLinkedId)
                return@withTransaction saved
            }
            require(entry.encyclopediaId > 0L) { "角色条目必须属于百科" }

            // linkedCharacterId 是内部关系键：已有条目只继承数据库中的旧值，
            // 新条目由标题/当前百科解析，禁止通过可编辑 meta 任意改绑并删除另一角色的镜像。
            val linkedId = previousLinkedId
            val linkedCharacter = linkedId?.let { characterDao.getById(it) }
            val title = entry.title.trim().ifBlank { "未命名角色" }
            // Older mirrors persisted only the first 8000 characters. Metadata-only edits
            // can retain the suffix only when the current internal binding proves its source.
            val linkedPersona = linkedCharacter?.personaPrompt?.trim()
            val restoreLegacyMirror = previous != null && linkedPersona != null &&
                previous.content.length == 8000 && linkedPersona.length > 8000 &&
                linkedPersona.startsWith(previous.content) && entry.content == previous.content
            val persona = if (restoreLegacyMirror) linkedPersona!! else entry.content.trim()
            val byName = if (linkedCharacter == null) findBoundCharacterByName(entry.encyclopediaId, title) else null
            val previousCharacter = linkedCharacter ?: byName
            val now = System.currentTimeMillis()
            val character = if (previousCharacter != null) {
                previousCharacter.copy(
                    name = title,
                    personaPrompt = persona.ifBlank { previousCharacter.personaPrompt },
                    boundEncyclopediaId = entry.encyclopediaId,
                    updatedAt = now,
                )
            } else {
                CharacterEntity(
                    name = title,
                    personaPrompt = persona,
                    boundEncyclopediaId = entry.encyclopediaId,
                    createdAt = now,
                    updatedAt = now,
                )
            }

            if (previousCharacter != null &&
                previousCharacter.boundEncyclopediaId > 0L &&
                previousCharacter.boundEncyclopediaId != entry.encyclopediaId
            ) {
                CharacterEncyclopediaSync.removeCharacterMirrors(
                    entryDao,
                    previousCharacter.boundEncyclopediaId,
                    previousCharacter.id,
                )
            }
            val rowId = characterDao.upsert(character)
            val characterId = character.id.takeIf { it > 0L } ?: rowId
            check(characterId > 0L) { "角色保存失败" }

            if (previousLinkedId != null && previousLinkedId != characterId) {
                detachPreviousCharacter(previous, previousLinkedId)
            }

            val meta = parseMeta(entry.metaJson).apply {
                addProperty("linkedCharacterId", characterId)
            }
            val summary = entry.summary.trim().ifBlank {
                persona.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }?.take(400).orEmpty()
            }
            val saved = upsertEntry(
                entry.copy(
                    title = title,
                    summary = summary,
                    content = if (restoreLegacyMirror) persona else entry.content,
                    metaJson = meta.toString(),
                    updatedAt = now,
                ),
            )

            CharacterEncyclopediaSync.findCharacterMirrorIds(entryDao, entry.encyclopediaId, characterId)
                .filter { it != saved.id }
                .forEach { entryDao.delete(it) }
            saved
        }

    /** Match Kotlin's case-insensitive name semantics and original library ordering without loading full cards. */
    private suspend fun findBoundCharacterByName(encyclopediaId: Long, title: String): CharacterEntity? {
        val characterDao = database.characterDao()
        var cursor: NewSessionCharacterOption? = null
        while (true) {
            val page = characterDao.getBoundCharacterNamePage(
                encyclopediaId, cursor?.pinnedAt, cursor?.favorite, cursor?.createdAt, cursor?.id,
                CHARACTER_NAME_SCAN_PAGE_SIZE,
            )
            page.firstOrNull { it.name.equals(title, ignoreCase = true) }
                ?.let { return characterDao.getById(it.id) }
            if (page.size < CHARACTER_NAME_SCAN_PAGE_SIZE) return null
            cursor = page.last()
        }
    }

    private suspend fun upsertEntry(entry: EncyclopediaEntryEntity): EncyclopediaEntryEntity {
        val entryDao = database.encyclopediaEntryDao()
        val rowId = entryDao.upsert(entry)
        val effectiveId = entry.id.takeIf { it > 0L } ?: rowId
        check(effectiveId > 0L) { "百科条目保存失败" }
        return entryDao.getById(effectiveId) ?: entry.copy(id = effectiveId)
    }

    private suspend fun detachPreviousCharacter(
        previousEntry: EncyclopediaEntryEntity?,
        characterId: Long?,
    ) {
        if (previousEntry == null || characterId == null) return
        val characterDao = database.characterDao()
        val character = characterDao.getById(characterId) ?: return
        if (character.boundEncyclopediaId == previousEntry.encyclopediaId) {
            characterDao.upsert(
                character.copy(boundEncyclopediaId = 0L, updatedAt = System.currentTimeMillis()),
            )
        }
        CharacterEncyclopediaSync.removeCharacterMirrors(
            database.encyclopediaEntryDao(),
            previousEntry.encyclopediaId,
            characterId,
        )
    }

    private fun EncyclopediaEntryEntity.withoutLinkedCharacterId(): EncyclopediaEntryEntity {
        val meta = parseMeta(metaJson)
        meta.remove("linkedCharacterId")
        return copy(metaJson = meta.toString(), updatedAt = System.currentTimeMillis())
    }

    private fun parseMeta(metaJson: String): JsonObject =
        runCatching { JsonParser.parseString(metaJson.ifBlank { "{}" }).asJsonObject }
            .getOrNull()
            ?: JsonObject()

    private fun entryContentDiffers(a: EncyclopediaEntryEntity, b: EncyclopediaEntryEntity): Boolean =
        a.title != b.title ||
            a.entryType != b.entryType ||
            a.summary != b.summary ||
            a.content != b.content ||
            a.tags != b.tags ||
            a.confidence != b.confidence ||
            a.metaJson != b.metaJson ||
            a.isFeatured != b.isFeatured ||
            a.changeNote != b.changeNote ||
            a.coverImagePath != b.coverImagePath
}
