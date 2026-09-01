package com.mojing.app.data.remote

import com.mojing.app.domain.config.DmxApiRouting
import com.mojing.app.domain.config.OpenAiCompatibleRouting
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** DMX MiMo `mimo-v2-tts`：`/v1/chat/completions` + `audio` 字段。 */
object DmxChatTtsApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.MINUTES)
        .build()

    suspend fun synthesizeMimoWav(
        apiKey: String,
        baseUrl: String,
        voice: String,
        text: String,
    ): ByteArray? {
        val url = OpenAiCompatibleRouting.buildChatCompletionsUrl(
            DmxApiRouting.dmxOpenAiBase(baseUrl),
        )
        val v = voice.trim().ifBlank { DmxApiRouting.DEFAULT_MIMO_VOICE }
        val body = buildJsonObject {
            put("model", "mimo-v2-tts")
            put("messages", kotlinx.serialization.json.JsonArray(listOf(
                buildJsonObject {
                    put("role", "user")
                    put("content", "朗读以下内容")
                },
                buildJsonObject {
                    put("role", "assistant")
                    put("content", text)
                },
            )))
            put("audio", buildJsonObject {
                put("format", "wav")
                put("voice", v)
            })
        }
        val req = Request.Builder()
            .url(url)
            .header("Authorization", DmxApiRouting.formatBearerAuth(apiKey))
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.executeCancellable(req) { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) null else parseWavBytes(raw)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseWavBytes(raw: String): ByteArray? {
        val root = runCatching {
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                .parseToJsonElement(raw).jsonObject
        }.getOrNull() ?: return null
        val b64 = root["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")?.jsonObject
            ?.get("audio")?.jsonObject?.get("data")?.jsonPrimitive?.content?.trim()
            ?: return null
        return runCatching { java.util.Base64.getDecoder().decode(b64) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }
}
