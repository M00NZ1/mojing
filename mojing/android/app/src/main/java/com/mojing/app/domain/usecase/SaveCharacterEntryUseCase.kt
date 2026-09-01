package com.mojing.app.domain.usecase

import androidx.room.withTransaction
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.EncyclopediaEntryEntity
import com.mojing.app.domain.encyclopedia.CharacterEncyclopediaSync
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
            val persona = entry.content.trim()
            val byName = if (linkedCharacter == null) {
                characterDao.getAll().firstOrNull {
                    it.boundEncyclopediaId == entry.encyclopediaId &&
                        it.name.equals(title, ignoreCase = true)
                }
            } else {
                null
            }
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
                    metaJson = meta.toString(),
                    updatedAt = now,
                ),
            )

            entryDao.getByType(entry.encyclopediaId, "character")
                .filter {
                    it.id != saved.id &&
                        CharacterEncyclopediaSync.readLinkedCharacterId(it.metaJson) == characterId
                }
                .forEach { entryDao.delete(it.id) }
            saved
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
}
