package com.mojing.app.domain.config

import com.mojing.app.data.ModelPlatform
import com.mojing.app.util.ApiRootLines
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Resolve only a saved identity. The caller freezes platforms before asynchronous preparation. */
object ModelRequestSettingsResolver {
    fun contextWindow(platforms: List<ModelPlatform>, apiKey: String, baseUrl: String, model: String): Int? {
        val endpoint = endpoint(baseUrl) ?: return null
        val key = apiKey.trim().removePrefix("Bearer ").trim()
        val matches = platforms.filter { platform ->
            platform.apiKey.trim().removePrefix("Bearer ").trim() == key &&
                ApiRootLines.split(platform.baseUrl).any { endpoint(it) == endpoint }
        }
        val platform = matches.singleOrNull() ?: matches.filter { model in it.models }.singleOrNull()
        return platform?.modelContextWindows?.get(model)
    }

    private fun endpoint(raw: String): String? {
        val parsed = raw.trim().toHttpUrlOrNull() ?: return null
        // LlmApiService uses native messages only for this exact host.
        val url = (if (parsed.host == "api.anthropic.com")
            com.mojing.app.domain.engine.AnthropicAdapter.messagesUrl(raw.trim())
        else com.mojing.app.data.remote.LlmApiService.openAiChatCompletionUrl(raw))
            .toHttpUrlOrNull() ?: return null
        return "${url.scheme}://${url.host}:${url.port}${url.encodedPath.trimEnd('/')}"
    }
}
