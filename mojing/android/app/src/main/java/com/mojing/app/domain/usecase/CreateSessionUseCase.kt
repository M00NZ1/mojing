package com.mojing.app.domain.usecase

import com.mojing.app.data.SecureStorage
import com.mojing.app.data.local.dao.CharacterDao
import com.mojing.app.data.local.dao.WorldTemplateDao
import com.mojing.app.data.local.entity.SessionEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionParticipantEntity
import com.mojing.app.data.local.entity.SessionWorldEntity
import com.mojing.app.data.local.entity.WorldTemplateEntity
import javax.inject.Inject

class CreateSessionUseCase @Inject constructor(
    private val transaction: SessionCreationTransaction,
    private val characterDao: CharacterDao,
    private val worldTemplateDao: WorldTemplateDao,
    private val secureStorage: SecureStorage,
    private val worldMappingDao: com.mojing.app.data.local.dao.LegacyWorldMappingDao,
    private val encyclopediaDao: com.mojing.app.data.local.dao.EncyclopediaDao,
) {
    sealed interface Result {
        data class Created(val sessionId: Long) : Result
        data object EmptyParticipants : Result
        data object CharacterNotFound : Result
        data object UnboundCharacter : Result
        data object EncyclopediaMismatch : Result
    }

    suspend fun createBlank(
        title: String = "新对话",
        summary: String = "",
        gameplayMode: String = "自由剧情",
        template: WorldTemplateEntity? = null,
        encyclopediaId: Long? = null,
        narratorEnabled: Boolean = secureStorage.defaultNarratorEnabled,
        narratorName: String = "旁白",
        creationRequestId: String? = null,
    ): Result {
        val (effectiveTemplate, effectiveEncyclopediaId) = resolveWorld(template ?: if (encyclopediaId == null) loadDefaultTemplate() else null, encyclopediaId)
        val antiCheatEnabled = secureStorage.defaultAntiCheatEnabled
        val sessionId = transaction(
            SessionEntity(title = title, summary = summary, creationRequestId = creationRequestId),
            SessionWorldEntity(
                sessionId = 0L,
                encyclopediaId = effectiveEncyclopediaId,
                templateId = effectiveTemplate?.templateId?.trim().orEmpty().ifBlank { "custom" },
                gameplayMode = effectiveTemplate?.gameplayMode?.trim().orEmpty().ifBlank { gameplayMode },
                worldPrompt = effectiveTemplate?.worldPrompt.orEmpty(),
                narratorEnabled = narratorEnabled,
                narratorName = narratorName,
                choiceGenerationEnabled = secureStorage.defaultChoiceGenerationEnabled,
                suggestedChoicesJson = effectiveTemplate?.suggestedChoicesJson.orEmpty().ifBlank { "[]" },
                antiCheatEnabled = antiCheatEnabled,
                antiCheatPrompt = if (antiCheatEnabled) effectiveTemplate?.antiCheatPrompt.orEmpty() else "",
            ),
            emptyList(),
        )
        return Result.Created(sessionId)
    }

    suspend fun createForCharacter(characterId: Long): Result {
        val character = characterDao.getById(characterId) ?: return Result.CharacterNotFound
        return create(
            title = "${character.name.trim().ifBlank { "未命名角色" }} · 新故事",
            encyclopediaId = character.boundEncyclopediaId.takeIf { it > 0L },
            narratorEnabled = secureStorage.defaultNarratorEnabled,
            choiceEnabled = secureStorage.defaultChoiceGenerationEnabled,
            antiCheatEnabled = secureStorage.defaultAntiCheatEnabled,
            template = if (character.boundEncyclopediaId > 0L) null else loadDefaultTemplate(),
            characterIds = listOf(character.id),
        )
    }

    suspend fun create(
        title: String = "新对话",
        summary: String = "",
        gameplayMode: String? = null,
        template: WorldTemplateEntity? = null,
        encyclopediaId: Long? = null,
        narratorEnabled: Boolean = false,
        narratorName: String = "旁白",
        choiceEnabled: Boolean = true,
        maxChoices: Int = 3,
        antiCheatEnabled: Boolean = true,
        displayContextTokenLimit: Int = 1_000_000,
        characterIds: List<Long> = emptyList(),
        allowNoParticipants: Boolean = false,
        worldPromptOverride: String? = null,
        initialMessages: List<MessageEntity> = emptyList(),
        storyDraftId: String? = null,
        creationRequestId: String? = null,
    ): Result {
        val (effectiveTemplate, effectiveEncyclopediaId) = resolveWorld(template, encyclopediaId)
        val validIds = characterIds.distinct()
        if (validIds.isEmpty() && !allowNoParticipants) return Result.EmptyParticipants
        val encId = effectiveEncyclopediaId?.takeIf { it > 0L }
        val characters = validIds.map { characterDao.getById(it) ?: return Result.CharacterNotFound }
        if (encId != null && characters.any { it.boundEncyclopediaId > 0L && it.boundEncyclopediaId != encId }) {
            return Result.EncyclopediaMismatch
        }

        val session = SessionEntity(
            title = title.trim().ifBlank { "新对话" },
            summary = summary.trim(),
            displayContextTokenLimit = displayContextTokenLimit.coerceIn(1_000, 10_000_000),
            creationRequestId = creationRequestId,
        )
        val templateId = effectiveTemplate?.templateId?.trim().orEmpty().ifBlank { "custom" }
        val world = SessionWorldEntity(
            sessionId = 0L,
            encyclopediaId = effectiveEncyclopediaId,
            templateId = templateId,
            gameplayMode = gameplayMode?.trim()?.ifBlank { null }
                ?: effectiveTemplate?.gameplayMode?.ifBlank { "自由剧情" }
                ?: "自由剧情",
            worldPrompt = worldPromptOverride ?: effectiveTemplate?.worldPrompt.orEmpty(),
            narratorEnabled = narratorEnabled,
            narratorName = narratorName.trim().ifBlank { "旁白" },
            choiceGenerationEnabled = choiceEnabled,
            maxChoiceCount = maxChoices.coerceIn(1, 8),
            suggestedChoicesJson = effectiveTemplate?.suggestedChoicesJson?.ifBlank { "[]" } ?: "[]",
            antiCheatEnabled = antiCheatEnabled,
            antiCheatPrompt = if (antiCheatEnabled) (effectiveTemplate?.antiCheatPrompt ?: "") else "",
        )
        val participants = validIds.mapIndexed { index, characterId ->
            SessionParticipantEntity(
                sessionId = 0L,
                characterId = characterId,
                sortOrder = index,
            )
        }
        val sessionId = transaction(
            session,
            world,
            participants,
            initialMessages,
            storyDraftId,
        )
        return Result.Created(sessionId)
    }

    private suspend fun resolveWorld(template: WorldTemplateEntity?, encyclopediaId: Long?): Pair<WorldTemplateEntity?, Long?> {
        val mapping = template?.let { worldMappingDao.getByTemplateId(it.id) }
        val selectedId = encyclopediaId ?: mapping?.encyclopediaId
        val canonical = selectedId?.let { encyclopediaDao.getById(it) }
        if (canonical == null || (template != null && mapping?.encyclopediaId != selectedId)) return template to selectedId
        // New sessions snapshot the current world. Existing sessions and recovery overrides are unchanged.
        return (template ?: WorldTemplateEntity()).copy(
            templateId = "custom", label = canonical.name, summary = canonical.description,
            worldPrompt = canonical.worldPrompt, gameplayMode = canonical.gameplayMode,
            antiCheatPrompt = canonical.antiCheatPrompt, coverImagePath = canonical.coverImagePath,
        ) to selectedId
    }

    private suspend fun loadDefaultTemplate(): WorldTemplateEntity? {
        val templateId = secureStorage.defaultWorldTemplateId.trim()
        if (templateId.isEmpty() || templateId == "custom") return null
        return worldTemplateDao.getByTemplateId(templateId)
    }
}
