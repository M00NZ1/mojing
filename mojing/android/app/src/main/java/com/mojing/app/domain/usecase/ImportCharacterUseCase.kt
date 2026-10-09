package com.mojing.app.domain.usecase

import androidx.room.withTransaction
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.CharacterProfileEntity
import com.mojing.app.domain.util.CharacterPortableCodec
import com.mojing.app.ui.character.CharacterExportCodec
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.TreeMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Commits one character import as one unit, including its profile and encyclopedia mirror.
 * Parsing and any provider/AI work happen before this use case is called.
 */
@Singleton
class ImportCharacterUseCase @Inject constructor(
    private val database: AppDatabase,
    private val saveCharacterBinding: SaveCharacterBindingUseCase,
) {
    data class StandardImportResult(val importedIds: List<Long>, val unboundCount: Int)

    companion object {
        private const val ENCYCLOPEDIA_NAME_PAGE_SIZE = 200
    }

    internal suspend fun importStandard(
        exported: List<CharacterExportCodec.ExportedCharacter>,
    ): StandardImportResult = importStandardInternal(exported)

    internal suspend fun importStandardForTest(
        exported: List<CharacterExportCodec.ExportedCharacter>,
        beforeSave: suspend (Int) -> Unit,
    ): StandardImportResult = importStandardInternal(exported, beforeSave)

    private suspend fun importStandardInternal(
        exported: List<CharacterExportCodec.ExportedCharacter>,
        beforeSave: suspend (Int) -> Unit = {},
    ): StandardImportResult = database.withTransaction {
        val bindings = resolveBindings(exported.map { it.boundEncyclopediaName })
        val existingNames = mutableSetOf<String>()
        val importedIds = ArrayList<Long>(exported.size)
        exported.forEachIndexed { index, item ->
            require(item.maxTokens > 0) { "角色「${item.name.take(120)}」的最大 Token 必须是正整数" }
            beforeSave(index)
            currentCoroutineContext().ensureActive()
            val base = CharacterPortableCodec.normalizedNameBase(item.name)
            existingNames += database.characterDao().getNamesStartingWith(base)
            val uniqueName = CharacterPortableCodec.allocateUniqueName(existingNames, base)
            existingNames += uniqueName
            val boundId = bindingForName(bindings, item.boundEncyclopediaName)
            importedIds += saveCharacterBinding(item.toEntity(boundId).copy(name = uniqueName))
        }
        StandardImportResult(
            importedIds = importedIds,
            unboundCount = exported.count { bindingForName(bindings, it.boundEncyclopediaName) <= 0L },
        )
    }

    suspend fun importPortable(
        parsed: CharacterPortableCodec.ParsedPortable,
    ): Long = database.withTransaction {
        require(parsed.maxTokens == null || parsed.maxTokens > 0) { "角色最大 Token 必须是正整数" }
        listOf(parsed.temperature, parsed.topP, parsed.frequencyPenalty, parsed.presencePenalty).forEach {
            require(it == null || it.isFinite()) { "角色采样参数必须是有效数字" }
        }
        val base = CharacterPortableCodec.normalizedNameBase(parsed.name)
        val existingNames = database.characterDao().getNamesStartingWith(base).toHashSet()
        val name = CharacterPortableCodec.allocateUniqueName(existingNames, base)
        val character = CharacterEntity(
            name = name,
            personaPrompt = parsed.personaPrompt,
            apiBaseUrl = parsed.apiBaseUrl?.takeIf { it.isNotBlank() } ?: "",
            modelName = parsed.modelName?.takeIf { it.isNotBlank() } ?: "deepseek-chat",
            temperature = parsed.temperature ?: 0.9f,
            maxTokens = parsed.maxTokens ?: 1200,
            topP = parsed.topP ?: 1.0f,
            frequencyPenalty = parsed.frequencyPenalty ?: 0.0f,
            presencePenalty = parsed.presencePenalty ?: 0.0f,
            avatarColor = parsed.avatarColor?.takeIf { it.isNotBlank() } ?: "#F97316",
            avatarImagePath = parsed.avatarImagePath?.takeIf { it.isNotBlank() }.orEmpty(),
            cardImagePath = parsed.cardImagePath?.takeIf { it.isNotBlank() }.orEmpty(),
        )
        val characterId = saveCharacterBinding(character)
        parsed.profile?.let { profile ->
            database.characterProfileDao().upsert(
                CharacterProfileEntity(
                    characterId = characterId,
                    sourceFilename = profile.sourceFilename.take(255),
                    rawPersonaText = profile.rawPersonaText,
                    characterCardMarkdown = profile.characterCardMarkdown,
                    characterCardJson = profile.characterCardJson,
                ),
            )
        }
        characterId
    }

    private suspend fun resolveBindings(
        requestedNames: List<String>,
    ): Map<String, Long> {
        if (requestedNames.isEmpty()) return emptyMap()
        // 0 means not found; -1 means ambiguous and must never become unique again.
        // This comparator has the same character case-folding rules as JVM ignoreCase.
        val resolved = TreeMap<String, Long>(String.CASE_INSENSITIVE_ORDER)
        requestedNames.forEach { name -> name.trim().takeIf { it.isNotEmpty() }?.let { resolved[it] = 0L } }
        if (resolved.isEmpty()) return resolved
        var afterId = 0L
        do {
            val page = database.encyclopediaDao().getNameOptionsPage(afterId, ENCYCLOPEDIA_NAME_PAGE_SIZE)
            currentCoroutineContext().ensureActive()
            page.forEach { option ->
                val name = option.name.trim()
                val previous = resolved[name] ?: return@forEach
                resolved[name] = if (previous == 0L) option.id else -1L
            }
            afterId = page.lastOrNull()?.id ?: afterId
        } while (page.size == ENCYCLOPEDIA_NAME_PAGE_SIZE)
        return resolved
    }

    private fun bindingForName(bindings: Map<String, Long>, name: String): Long =
        (bindings[name.trim()] ?: 0L).coerceAtLeast(0L)
}
