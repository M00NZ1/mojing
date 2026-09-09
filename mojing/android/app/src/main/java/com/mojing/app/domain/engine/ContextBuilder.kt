package com.mojing.app.domain.engine

import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.SessionWorldEntity
import com.mojing.app.data.local.entity.SessionMemoryCorrectionEntity
import com.mojing.app.data.local.dao.SessionDao
import javax.inject.Inject
import javax.inject.Singleton

data class ChatContext(
    val systemPrompt: String = "",
    val recentMessages: List<String> = emptyList(),
    val encyclopediaHits: List<String> = emptyList(),
    val loreHits: List<String> = emptyList(),
    val characterBookHits: List<String> = emptyList(),
    val memorySummary: String = "",
    val universalContextMemoryText: String = "",
    val activeCharacters: List<String> = emptyList(),
    val personaName: String = "玩家"
)

data class SharedWorldContext(
    val encyclopediaHits: List<String> = emptyList(),
    val loreHits: List<String> = emptyList(),
)

@Singleton
class ContextBuilder @Inject constructor(
    private val promptBuilder: PromptBuilder,
    private val encyclopediaSearcher: EncyclopediaSearcher,
    private val loreSearcher: LoreSearcher,
    private val characterBookSearcher: CharacterBookSearcher,
    private val encyclopediaDao: com.mojing.app.data.local.dao.EncyclopediaDao,
) {
    suspend fun buildFullContext(
        character: CharacterEntity,
        world: SessionWorldEntity?,
        personaName: String,
        userDescription: String,
        userMessage: String,
        /** 用于 Lore / 百科 / 角色世界书召回；默认与 userMessage 相同，可传入多轮摘要以提高命中率 */
        recallQueryText: String = userMessage,
        memorySummary: String,
        memoryCorrections: List<SessionMemoryCorrectionEntity> = emptyList(),
        universalContextMemoryText: String = "",
        activeCharacterNames: List<String>,
        sessionId: Long,
        effectiveModelName: String,
    ): ChatContext {
        val sharedWorldContext = searchWorldContext(world, recallQueryText)

        val characterBookHits = characterBookSearcher.search(character.id, recallQueryText)

        val systemPrompt = promptBuilder.buildForCharacter(
            PromptBuilder.PromptContext(
                character = character,
                world = world,
                personaName = personaName,
                userDescription = userDescription,
                activeCharacterNames = activeCharacterNames,
                recentMemorySegments = emptyList(),
                memoryCorrections = memoryCorrections,
                encyclopediaHits = sharedWorldContext.encyclopediaHits,
                loreHits = sharedWorldContext.loreHits,
                characterBookHits = characterBookHits,
                universalContextMemoryText = universalContextMemoryText,
                sessionId = sessionId,
            ),
            effectiveModelName = effectiveModelName,
        )

        return ChatContext(
            systemPrompt = systemPrompt,
            encyclopediaHits = sharedWorldContext.encyclopediaHits,
            loreHits = sharedWorldContext.loreHits,
            characterBookHits = characterBookHits,
            memorySummary = memorySummary,
            universalContextMemoryText = universalContextMemoryText,
            activeCharacters = activeCharacterNames,
            personaName = personaName
        )
    }

    suspend fun encyclopediaFoundation(world: SessionWorldEntity?): String {
        val id = world?.encyclopediaId?.takeIf { it > 0L } ?: return ""
        val encyclopedia = encyclopediaDao.getById(id) ?: return ""
        return listOf(encyclopedia.description.trim(), encyclopedia.worldPrompt.trim())
            .filter { it.isNotBlank() && !world.worldPrompt.contains(it) }
            .distinct().joinToString("\n\n")
    }

    suspend fun searchWorldContext(
        world: SessionWorldEntity?,
        recallQueryText: String,
    ): SharedWorldContext {
        val encyclopediaHits = if (world?.encyclopediaId != null) {
            val foundation = encyclopediaFoundation(world)
            buildList {
                if (foundation.isNotBlank()) add("[百科基础背景] $foundation")
                addAll(encyclopediaSearcher.search(world.encyclopediaId, recallQueryText)
                    .map { "[${it.title}] ${it.content.take(200)}" })
            }
        } else {
            emptyList()
        }
        val loreHits = loreSearcher.search(world?.templateId, recallQueryText)
            .map { "[${it.title}] ${it.content.take(200)}" }
        return SharedWorldContext(
            encyclopediaHits = encyclopediaHits,
            loreHits = loreHits,
        )
    }
}
