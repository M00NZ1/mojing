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

    private const val MIRROR_SCAN_PAGE_SIZE = 128

    /** 对话来源的独立资料在确认、改名或调整类型后仍保留原身份。 */
    fun isConversationNote(entry: EncyclopediaEntryEntity): Boolean =
        (entry.sourceSessionId ?: 0L) > 0L &&
            (readLinkedCharacterId(entry.metaJson) ?: 0L) <= 0L

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
        findCharacterMirrorIds(entryDao, encyclopediaId, characterId)
            .forEach { entryDao.delete(it) }
    }

    /** Metadata-only keyset scan keeps large entry bodies out of character save transactions. */
    suspend fun findCharacterMirrorIds(
        entryDao: EncyclopediaEntryDao,
        encyclopediaId: Long,
        characterId: Long,
    ): List<Long> {
        if (encyclopediaId <= 0L || characterId <= 0L) return emptyList()
        val matches = mutableListOf<Long>()
        var afterId = 0L
        do {
            val page = entryDao.getCharacterMirrorMetadataPage(encyclopediaId, afterId, MIRROR_SCAN_PAGE_SIZE)
            page.forEach { if (readLinkedCharacterId(it.metaJson) == characterId) matches += it.id }
            afterId = page.lastOrNull()?.id ?: afterId
        } while (page.size == MIRROR_SCAN_PAGE_SIZE)
        return matches
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

        val mirrorIds = findCharacterMirrorIds(entryDao, encyclopediaId, characterId)
        val existing = mirrorIds.firstOrNull()?.let { entryDao.getById(it) }
        mirrorIds.drop(1).forEach { entryDao.delete(it) }

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
