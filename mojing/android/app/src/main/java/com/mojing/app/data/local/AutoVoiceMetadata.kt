package com.mojing.app.data.local

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Non-secret state for an automatically generated voice-message row. */
data class AutoVoiceMetadataSnapshot(
    val state: String,
    val text: String?,
    val attemptToken: String?,
) {
    val retryable: Boolean
        get() = !text.isNullOrBlank() && !attemptToken.isNullOrBlank() &&
            (state == AutoVoiceMetadata.STATE_FAILED || state == AutoVoiceMetadata.STATE_INTERRUPTED)
}

object AutoVoiceMetadata {
    const val VERSION = 1
    const val KIND = "voice"
    const val STATE_RUNNING = "running"
    const val STATE_FAILED = "failed"
    const val STATE_INTERRUPTED = "interrupted"
    const val STATE_COMPLETE = "complete"

    fun isLegacyRunningContent(content: String): Boolean =
        content.trim() == "配音生成中…" || content.trim() == "🔊 配音生成中…"

    fun create(text: String, state: String, attemptToken: String): String = JsonObject().apply {
        addProperty("derived_media_version", VERSION)
        addProperty("derived_media_kind", KIND)
        addProperty("auto_media_state", state)
        addProperty("auto_media_text", text)
        addProperty("auto_media_attempt_token", attemptToken)
    }.toString()

    fun parse(raw: String): AutoVoiceMetadataSnapshot? = runCatching {
        val root = JsonParser.parseString(raw).asJsonObject
        if (root.get("derived_media_version")?.asInt != VERSION ||
            root.get("derived_media_kind")?.asString != KIND
        ) return@runCatching null
        AutoVoiceMetadataSnapshot(
            state = root.get("auto_media_state")?.asString.orEmpty(),
            text = root.get("auto_media_text")?.asString?.takeIf(String::isNotBlank),
            attemptToken = root.get("auto_media_attempt_token")?.asString?.takeIf(String::isNotBlank),
        )
    }.getOrNull()

    fun update(raw: String, state: String, attemptToken: String? = null): String {
        val root = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrElse { JsonObject() }
        root.addProperty("derived_media_version", VERSION)
        root.addProperty("derived_media_kind", KIND)
        root.addProperty("auto_media_state", state)
        attemptToken?.let { root.addProperty("auto_media_attempt_token", it) }
        return root.toString()
    }
}
