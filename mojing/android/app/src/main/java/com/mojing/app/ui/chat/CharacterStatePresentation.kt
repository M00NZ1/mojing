package com.mojing.app.ui.chat

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.mojing.app.data.local.entity.SessionCharacterStateEntity

private const val MAX_STATE_JSON_LENGTH = 32_768
private const val MAX_FIELD_LENGTH = 240
private const val MAX_LIST_ITEMS = 6

data class CharacterStateDisplay(
    val mood: String,
    val attitudeToUser: String,
    val currentGoal: String,
    val recentKeyActions: List<String>,
    val knownFacts: List<String>,
    val relationshipChanges: List<String>,
)

data class CharacterStatePanel(
    val sessionId: Long,
    val characterId: Long,
    val branchId: String,
    val branchLabel: String,
    val updatedAt: Long? = null,
    val loading: Boolean = true,
    val state: CharacterStateDisplay? = null,
    /** The persisted extractor result was explicitly marked unusable. */
    val snapshotIsValid: Boolean = true,
    /** The persisted JSON cannot be displayed; this does not make a runtime-use claim. */
    val displayError: String? = null,
    val hasOriginalText: Boolean = false,
    val originalPreview: String? = null,
    val error: String? = null,
    val clearing: Boolean = false,
)

internal fun parseCharacterStatePanel(
    entity: SessionCharacterStateEntity?,
    sessionId: Long,
    characterId: Long,
    branchId: String,
    branchLabel: String,
): CharacterStatePanel {
    if (entity == null) {
        return CharacterStatePanel(sessionId, characterId, branchId, branchLabel, loading = false)
    }
    val raw = entity.dynamicStateJson.trim()
    val hasOriginal = raw.isNotEmpty() && raw != "{}"
    val preview = raw.take(MAX_FIELD_LENGTH).takeIf { hasOriginal }
    if (!entity.snapshotIsValid) {
        return CharacterStatePanel(sessionId, characterId, branchId, branchLabel,
            updatedAt = entity.updatedAt, loading = false, snapshotIsValid = false,
            hasOriginalText = hasOriginal, originalPreview = preview)
    }
    if (!hasOriginal || raw.length > MAX_STATE_JSON_LENGTH) {
        return CharacterStatePanel(sessionId, characterId, branchId, branchLabel,
            updatedAt = entity.updatedAt, loading = false,
            displayError = if (hasOriginal) "当前状态内容过长，暂时无法展示" else null,
            hasOriginalText = hasOriginal, originalPreview = preview)
    }
    return runCatching {
        val root = Gson().fromJson(raw, JsonObject::class.java)
            ?: error("empty")
        CharacterStatePanel(sessionId, characterId, branchId, branchLabel,
            updatedAt = entity.updatedAt, loading = false,
            state = CharacterStateDisplay(
                root.text("mood"), root.text("attitudeToUser"), root.text("currentGoal"),
                root.list("recentKeyActions"), root.list("knownFacts"), root.list("relationshipChanges"),
            ), hasOriginalText = true)
    }.getOrElse {
        CharacterStatePanel(sessionId, characterId, branchId, branchLabel,
            updatedAt = entity.updatedAt, loading = false,
            displayError = "当前状态内容无法展示", hasOriginalText = hasOriginal, originalPreview = preview)
    }
}

private fun JsonObject.text(name: String): String =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString?.trim()?.take(MAX_FIELD_LENGTH).orEmpty()

private fun JsonObject.list(name: String): List<String> =
    get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.asSequence()
        ?.filter { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.map { it.asString.trim().take(MAX_FIELD_LENGTH) }
        ?.filter { it.isNotBlank() }?.take(MAX_LIST_ITEMS)?.toList().orEmpty()
