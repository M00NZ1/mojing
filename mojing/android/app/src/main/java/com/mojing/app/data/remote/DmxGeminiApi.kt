package com.mojing.app.data.remote

import com.mojing.app.domain.config.DmxApiRouting
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
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

/** DMX Gemini 原生 `/v1beta/models/{model}:generateContent`（文生图 / TTS）。 */
object DmxGeminiApi {

    private val jsonParser = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.MINUTES)
        .build()

    suspend fun generateImageBase64(
        apiKey: String,
        model: String,
        prompt: String,
        size: String,
    ): String? {
        val url = DmxApiRouting.buildGeminiGenerateContentUrl(model)
        val aspect = DmxApiRouting.mapGeminiAspectRatio(size)
        val imageSize = DmxApiRouting.mapGeminiImageSize(model, size)
        val imageConfig = buildJsonObject {
            put("aspectRatio", aspect)
            imageSize?.let { put("imageSize", it) }
        }
        val genConfig = buildJsonObject {
            if (imageSize != null || model.contains("pro-image", ignoreCase = true) ||
                model.contains("3.1-flash-image", ignoreCase = true)
            ) {
                put("responseModalities", kotlinx.serialization.json.JsonArray(listOf(
                    kotlinx.serialization.json.JsonPrimitive("IMAGE"),
                )))
            }
            put("imageConfig", imageConfig)
        }
        val body = buildJsonObject {
            put("contents", kotlinx.serialization.json.JsonArray(listOf(
                buildJsonObject {
                    put("parts", kotlinx.serialization.json.JsonArray(listOf(
                        buildJsonObject { put("text", prompt) },
                    )))
                },
            )))
            put("generationConfig", genConfig)
        }
        val raw = postJson(url, apiKey, body.toString()) ?: return null
        return parseInlineImageBase64(raw)
    }

    suspend fun synthesizeWavBytes(
        apiKey: String,
        model: String,
        voice: String,
        text: String,
    ): ByteArray? {
        val url = DmxApiRouting.buildGeminiGenerateContentUrl(model)
        val v = voice.trim().ifBlank { DmxApiRouting.DEFAULT_GEMINI_TTS_VOICE }
        val body = buildJsonObject {
            put("contents", kotlinx.serialization.json.JsonArray(listOf(
                buildJsonObject {
                    put("parts", kotlinx.serialization.json.JsonArray(listOf(
                        buildJsonObject { put("text", text) },
                    )))
                },
            )))
            put("generationConfig", buildJsonObject {
                put("responseModalities", kotlinx.serialization.json.JsonArray(listOf(
                    kotlinx.serialization.json.JsonPrimitive("AUDIO"),
                )))
                put("speechConfig", buildJsonObject {
                    put("voiceConfig", buildJsonObject {
                        put("prebuiltVoiceConfig", buildJsonObject {
                            put("voiceName", v)
                        })
                    })
                })
            })
        }
        val raw = postJson(url, apiKey, body.toString()) ?: return null
        return parseInlineAudioBytes(raw)
    }

    internal fun parseInlineImageBase64(raw: String): String? = extractInlineData(raw)

    internal fun parseInlineAudioBytes(raw: String): ByteArray? {
        val b64 = extractInlineData(raw) ?: return null
        return runCatching { java.util.Base64.getDecoder().decode(b64) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun extractInlineData(raw: String): String? {
        val root = runCatching {
            jsonParser.parseToJsonElement(raw).jsonObject
        }.getOrNull() ?: return null
        val parts = root["candidates"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray
            ?: return null
        for (part in parts) {
            val obj = part.jsonObject
            val inline = obj["inlineData"]?.jsonObject ?: obj["inline_data"]?.jsonObject ?: continue
            val data = inline["data"]?.jsonPrimitive?.content?.trim()
            if (!data.isNullOrEmpty()) return data
        }
        return null
    }

    private suspend fun postJson(url: String, apiKey: String, jsonBody: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("x-goog-api-key", DmxApiRouting.formatTokenAuth(apiKey))
            .header("Content-Type", "application/json")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.executeCancellable(req) { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) null else body
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
