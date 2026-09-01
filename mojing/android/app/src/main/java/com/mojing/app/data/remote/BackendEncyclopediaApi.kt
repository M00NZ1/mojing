package com.mojing.app.data.remote

import com.mojing.app.data.SecureStorage
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

private val JSON = "application/json; charset=utf-8".toMediaType()

/**
 * 百科条目相关后端接口（与 Web 同源 [SecureStorage.publicBaseUrl] → FastAPI `/api`）。
 * 封面预览不落库，便于本地 Room 条目 id 与服务器不一致时生图。
 */
@Singleton
class BackendEncyclopediaApi @Inject constructor(
    private val client: OkHttpClient,
    private val secureStorage: SecureStorage,
) {
    private fun apiRoot(): String = resolveBackendApiRoot(secureStorage)

    suspend fun previewEntryCoverImage(
        title: String,
        entryType: String,
        summary: String,
        promptHint: String = "",
        size: String = "1024x1792",
    ): Result<JsonObject> = try {
        val body = JsonObject().apply {
            addProperty("title", title)
            addProperty("entry_type", entryType)
            addProperty("summary", summary)
            addProperty("prompt_hint", promptHint)
            addProperty("size", size)
        }
        val req = Request.Builder()
            .url("${apiRoot()}/encyclopedia/entries/preview-cover-image")
            .post(body.toString().toRequestBody(JSON))
            .build()
        Result.success(
            client.executeCancellable(req) { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error(text.ifBlank { "HTTP ${resp.code}" })
                JsonParser.parseString(text).asJsonObject
            },
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}
