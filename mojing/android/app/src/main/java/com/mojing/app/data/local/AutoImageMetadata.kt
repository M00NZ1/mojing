package com.mojing.app.data.local

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Bounded, non-secret state kept on an automatically generated image message. */
data class AutoImageMetadataSnapshot(
    val state: String,
    val prompt: String?,
    val attemptToken: String?,
) {
    val retryable: Boolean get() = !prompt.isNullOrBlank() && !attemptToken.isNullOrBlank() &&
        (state == AutoImageMetadata.STATE_FAILED || state == AutoImageMetadata.STATE_INTERRUPTED)
}

object AutoImageMetadata {
    const val VERSION = 1
    const val KIND = "image"
    const val STATE_RUNNING = "running"
    const val STATE_FAILED = "failed"
    const val STATE_INTERRUPTED = "interrupted"
    const val STATE_COMPLETE = "complete"

    fun create(prompt: String, state: String, attemptToken: String): String = JsonObject().apply {
        addProperty("derived_media_version", VERSION)
        addProperty("derived_media_kind", KIND)
        addProperty("auto_media_state", state)
        addProperty("auto_media_prompt", prompt)
        addProperty("auto_media_attempt_token", attemptToken)
    }.toString()

    fun parse(raw: String): AutoImageMetadataSnapshot? {
        return runCatching {
            val root = JsonParser.parseString(raw).asJsonObject
            if (root.get("derived_media_version")?.asInt != VERSION ||
                root.get("derived_media_kind")?.asString != KIND
            ) return@runCatching null
            AutoImageMetadataSnapshot(
                state = root.get("auto_media_state")?.asString.orEmpty(),
                prompt = root.get("auto_media_prompt")?.asString?.takeIf(String::isNotBlank),
                attemptToken = root.get("auto_media_attempt_token")?.asString?.takeIf(String::isNotBlank),
            )
        }.getOrNull()
    }

    fun update(raw: String, state: String, attemptToken: String? = null): String {
        val root = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrElse { JsonObject() }
        root.addProperty("derived_media_version", VERSION)
        root.addProperty("derived_media_kind", KIND)
        root.addProperty("auto_media_state", state)
        attemptToken?.let { root.addProperty("auto_media_attempt_token", it) }
        return root.toString()
    }
}
