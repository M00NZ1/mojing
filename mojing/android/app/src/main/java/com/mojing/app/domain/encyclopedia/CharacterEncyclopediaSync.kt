package com.mojing.app.domain.encyclopedia

import com.mojing.app.data.local.dao.EncyclopediaEntryDao
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.EncyclopediaEntryEntity
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 百科「角色」类条目与 [CharacterEntity] 双向镜像：批量生成写入百科后需同步到角色表，对话/人物页才能选用。
 */
object CharacterEncyclopediaSync {

    fun readLinkedCharacterId(metaJson: String): Long? =
        runCatching {
            JsonParser.parseString(metaJson.ifBlank { "{}" }).asJsonObject
                .get("linkedCharacterId")?.asLong
        }.getOrNull()

    /** 删除指定角色在一个百科中的全部镜像，只按 linkedCharacterId 匹配，避免误删同名用户条目。 */
    suspend fun removeCharacterMirrors(
        entryDao: EncyclopediaEntryDao,
        encyclopediaId: Long,
        characterId: Long,
    ) {
        if (encyclopediaId <= 0L || characterId <= 0L) return
        entryDao.getByType(encyclopediaId, "character")
            .filter { readLinkedCharacterId(it.metaJson) == characterId }
            .forEach { entryDao.delete(it.id) }
    }

    /**
     * 角色表 → 百科角色条目。复用最早创建的镜像并清理重复项，确保每个绑定只有一个镜像。
     */
    suspend fun syncCharacterToEntry(
        entryDao: EncyclopediaEntryDao,
        character: CharacterEntity,
    ): EncyclopediaEntryEntity? {
        val characterId = character.id
        val encyclopediaId = character.boundEncyclopediaId
        if (characterId <= 0L || encyclopediaId <= 0L) return null

        val mirrors = entryDao.getByType(encyclopediaId, "character")
            .filter { readLinkedCharacterId(it.metaJson) == characterId }
        val existing = mirrors.firstOrNull()
        mirrors.drop(1).forEach { entryDao.delete(it.id) }

        val now = System.currentTimeMillis()
        val persona = character.personaPrompt.trim()
        val summary = persona.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?.take(400)
            .orEmpty()
        val metaRoot = runCatching {
            JsonParser.parseString(existing?.metaJson?.ifBlank { "{}" } ?: "{}").asJsonObject
        }.getOrNull() ?: JsonObject()
        metaRoot.addProperty("linkedCharacterId", characterId)

        val entry = if (existing != null) {
            existing.copy(
                title = character.name.trim().ifBlank { "未命名角色" },
                entryType = "character",
                summary = summary,
                content = persona.take(8000),
                metaJson = metaRoot.toString(),
                updatedAt = now,
            )
        } else {
            EncyclopediaEntryEntity(
                encyclopediaId = encyclopediaId,
                title = character.name.trim().ifBlank { "未命名角色" },
                entryType = "character",
                summary = summary,
                content = persona.take(8000),
                metaJson = metaRoot.toString(),
                createdAt = now,
                updatedAt = now,
            )
        }
        val rowId = entryDao.upsert(entry)
        return if (entry.id > 0L) entry else entry.copy(id = rowId)
    }

}
