package com.mojing.app.data.remote

import com.mojing.app.data.SecureStorage
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

private val JSON = "application/json; charset=utf-8".toMediaType()

/**
 * 与 Web 同源的人物相关后端接口（需 [SecureStorage.publicBaseUrl] 指向 FastAPI `/api` 根）。
 * 本地 Room 角色 id 与服务器不一致时，生图走 [previewCardImage]（仅 public_image_*）。
 */
@Singleton
class BackendCharactersApi @Inject constructor(
    private val client: OkHttpClient,
    private val secureStorage: SecureStorage,
) {
    private fun apiRoot(): String = resolveBackendApiRoot(secureStorage)

    suspend fun previewCardImage(
        name: String,
        personaPrompt: String,
        promptHint: String = "",
        size: String = "1024x1792",
    ): Result<JsonObject> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JsonObject().apply {
                addProperty("name", name)
                addProperty("persona_prompt", personaPrompt)
                addProperty("prompt_hint", promptHint)
                addProperty("size", size)
            }
            val req = Request.Builder()
                .url("${apiRoot()}/characters/preview-card-image")
                .post(body.toString().toRequestBody(JSON))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error(text.ifBlank { "HTTP ${resp.code}" })
                JsonParser.parseString(text).asJsonObject
            }
        }
    }
}
