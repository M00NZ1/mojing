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
    val personaName: String = "玩家",
    val promptDocument: PromptDocument? = null,
)

data class SharedWorldContext(
    val encyclopediaFoundation: String = "",
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
    private val configDao: com.mojing.app.data.local.dao.ConfigDao? = null,
) {
    /** Recent list is already bounded by its DAO. An unknown or edited row protects the whole summary block. */
    suspend fun areAutomaticSummaries(segments: List<com.mojing.app.data.local.entity.SessionMemorySegmentEntity>): Boolean {
        val config = configDao ?: return false
        if (segments.isEmpty()) return false
        for (segment in segments) {
            if (segment.id <= 0L) return false
            val source = try { config.get(SummaryProvenance.key(segment))?.valueJson }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { return false }
            if (source != SummaryProvenance.fingerprint(segment)) return false
        }
        return true
    }

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
        automaticSummary: Boolean = false,
    ): ChatContext {
        val sharedWorldContext = searchWorldContext(world, recallQueryText)

        val characterBookHits = characterBookSearcher.search(character.id, recallQueryText)

        val promptDocument = promptBuilder.buildCharacterDocument(
            PromptBuilder.PromptContext(
                character = character,
                world = world,
                personaName = personaName,
                userDescription = userDescription,
                activeCharacterNames = activeCharacterNames,
                recentMemorySegments = emptyList(),
                memorySummary = memorySummary,
                memoryCorrections = memoryCorrections,
                encyclopediaFoundation = sharedWorldContext.encyclopediaFoundation,
                automaticSummary = automaticSummary,
                encyclopediaHits = sharedWorldContext.encyclopediaHits,
                loreHits = sharedWorldContext.loreHits,
                characterBookHits = characterBookHits,
                universalContextMemoryText = universalContextMemoryText,
                sessionId = sessionId,
            ),
            effectiveModelName = effectiveModelName,
        )

        return ChatContext(
            systemPrompt = promptDocument.render(),
            promptDocument = promptDocument,
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
        val foundation = encyclopediaFoundation(world)
        val encyclopediaHits = world?.encyclopediaId?.let { id ->
            encyclopediaSearcher.search(id, recallQueryText).map { "[${it.title}] ${it.content.take(200)}" }
        }.orEmpty()
        val loreHits = loreSearcher.search(world?.templateId, recallQueryText)
            .map { "[${it.title}] ${it.content.take(200)}" }
        return SharedWorldContext(
            encyclopediaFoundation = foundation,
            encyclopediaHits = encyclopediaHits,
            loreHits = loreHits,
        )
    }
}
