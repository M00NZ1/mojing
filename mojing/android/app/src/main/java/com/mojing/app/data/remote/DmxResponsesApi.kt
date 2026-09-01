package com.mojing.app.data.remote

import com.mojing.app.domain.config.DmxApiRouting
import com.mojing.app.domain.config.OpenAiCompatibleRouting
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
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

/** DMX `/v1/responses`：Seedream / 万相 文生图、MiniMax speech-2.8 TTS。 */
object DmxResponsesApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.MINUTES)
        .build()

    private val jsonParser = Json { ignoreUnknownKeys = true }

    suspend fun generateImageUrl(
        apiKey: String,
        baseUrl: String,
        prompt: String,
        model: String,
        size: String,
    ): String? = when (DmxApiRouting.classifyImageProtocol(model)) {
        DmxApiRouting.ImageProtocol.RESPONSES_WAN ->
            generateWanImageUrl(apiKey, baseUrl, prompt, model, size)
        else -> generateSeedreamImageUrl(apiKey, baseUrl, prompt, model, size)
    }

    suspend fun generateSeedreamImageUrl(
        apiKey: String,
        baseUrl: String,
        prompt: String,
        model: String,
        size: String,
    ): String? {
        val url = DmxApiRouting.buildResponsesUrl(baseUrl)
        val body = buildJsonObject {
            put("model", model)
            put("input", prompt)
            put("size", OpenAiCompatibleRouting.mapSeedreamSize(size))
            put("sequential_image_generation", "disabled")
            put("response_format", "url")
            put("watermark", false)
            put("stream", false)
        }
        val resp = postJson(url, apiKey, body.toString()) ?: return null
        return parseFirstImageUrl(resp)
    }

    suspend fun generateWanImageUrl(
        apiKey: String,
        baseUrl: String,
        prompt: String,
        model: String,
        size: String,
    ): String? {
        val url = DmxApiRouting.buildResponsesUrl(baseUrl)
        val wanSize = DmxApiRouting.mapWanImageSize(model, size)
        val body = buildJsonObject {
            put("model", model)
            put("input", buildJsonObject {
                put("messages", JsonArray(listOf(
                    buildJsonObject {
                        put("role", "user")
                        put("content", JsonArray(listOf(
                            buildJsonObject { put("text", prompt) },
                        )))
                    },
                )))
            })
            put("parameters", buildJsonObject {
                put("watermark", false)
                put("n", 1)
                put("size", wanSize)
                if (model.lowercase().startsWith("wan2.6")) {
                    put("prompt_extend", true)
                }
                if (model.lowercase().startsWith("wan2.7")) {
                    put("enable_sequential", false)
                    put("thinking_mode", true)
                }
            })
        }
        val resp = postJson(url, apiKey, body.toString()) ?: return null
        return parseFirstImageUrl(resp)
    }

    suspend fun synthesizeMinimax28Mp3(
        apiKey: String,
        baseUrl: String,
        model: String,
        voiceId: String,
        text: String,
    ): ByteArray? {
        val url = DmxApiRouting.buildResponsesUrl(baseUrl)
        val m = model.trim().ifBlank { "speech-2.8-hd" }
        val vid = voiceId.trim().ifBlank { DmxApiRouting.DEFAULT_MINIMAX_VOICE }
        val body = buildJsonObject {
            put("model", m)
            put("input", text)
            put("stream", false)
            put("output_format", "hex")
            put("voice_setting", buildJsonObject {
                put("voice_id", vid)
                put("speed", 1.0)
                put("vol", 1.0)
                put("pitch", 0)
            })
            put("audio_setting", buildJsonObject {
                put("format", "mp3")
                put("sample_rate", 32000)
                put("bitrate", 128000)
                put("channel", 1)
            })
        }
        val resp = postJson(url, apiKey, body.toString()) ?: return null
        return parseHexAudio(resp)
    }

    internal fun parseFirstImageUrl(raw: String): String? =
        parseAllImageUrls(raw).firstOrNull()

    internal fun parseAllImageUrls(raw: String): List<String> {
        val fromMd = Regex("!\\[[^\\]]*]\\((https?://[^)]+)\\)").findAll(raw).map { it.groupValues[1] }.toList()
        if (fromMd.isNotEmpty()) return fromMd
        val root = runCatching { jsonParser.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return emptyList()
        val fromOutput = mutableListOf<String>()
        root["output"]?.jsonArray?.forEach { item ->
            item.jsonObject["content"]?.jsonArray?.forEach { c ->
                val text = c.jsonObject["text"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (text.startsWith("http")) fromOutput.add(text)
            }
        }
        if (fromOutput.isNotEmpty()) return fromOutput
        val texts = mutableListOf<String>()
        collectOutputTexts(root, texts)
        val urls = texts.flatMap { t ->
            Regex("!\\[[^\\]]*]\\((https?://[^)]+)\\)").findAll(t).map { it.groupValues[1] }
        }
        if (urls.isNotEmpty()) return urls
        root["data"]?.jsonObject?.get("url")?.jsonPrimitive?.content?.takeIf { it.startsWith("http") }?.let {
            return listOf(it)
        }
        return emptyList()
    }

    internal fun parseHexAudio(raw: String): ByteArray? {
        val root = runCatching { jsonParser.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val hex = root["data"]?.jsonObject?.get("audio")?.jsonPrimitive?.content?.trim()
            ?: return null
        if (hex.isEmpty()) return null
        return runCatching {
            hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun collectOutputTexts(node: kotlinx.serialization.json.JsonObject, out: MutableList<String>) {
        node["output"]?.jsonArray?.forEach { item ->
            item.jsonObject["content"]?.jsonArray?.forEach { c ->
                c.jsonObject["text"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            }
        }
        node["text"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { out.add(it) }
    }

    private suspend fun postJson(url: String, apiKey: String, jsonBody: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", DmxApiRouting.formatTokenAuth(apiKey))
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
