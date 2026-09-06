package com.mojing.app.data.remote

import com.mojing.app.data.SecureStorage
import com.mojing.app.domain.config.OpenAiCompatibleRouting
import com.mojing.app.util.ApiRootLines
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

@Singleton
class BackendSystemProbeApi @Inject constructor(
    private val client: OkHttpClient,
    private val secureStorage: SecureStorage,
    private val imageApiService: ImageApiService,
    private val llmApiService: LlmApiService,
) {
    suspend fun listModels(baseUrl: String, apiKey: String): List<String> =
        llmApiService.listModels(baseUrl, apiKey)

    private fun relayRoot(): String = resolveBackendApiRoot(secureStorage).trimEnd('/')

    /**
     * 先本机直连多候选根地址探测（不依赖墨境）；失败且配置了墨境后端时再走
     * `POST /api/system/probe-public-api/stream` 流式中继。
     */
    suspend fun probePublicApiStream(
        channel: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        onLine: (JsonObject) -> Unit,
    ): Result<JsonObject> = withContext(Dispatchers.IO) {
        runCatching {
            val directDone = probeDirectClientSide(channel, baseUrl, apiKey, model, onLine)
            if (directDone.get("ok")?.asBoolean == true) return@runCatching directDone
            val root = relayRoot()
            if (root.isBlank()) return@runCatching directDone
            probeRelayStream(root, channel, baseUrl, apiKey, model, onLine)
        }
    }

    private suspend fun probeDirectClientSide(
        channel: String,
        userBase: String,
        apiKey: String,
        model: String,
        onLine: (JsonObject) -> Unit,
    ): JsonObject {
        val segments = ApiRootLines.split(userBase).ifEmpty { listOf(userBase.trim()) }.filter { it.isNotEmpty() }
        val bases = LinkedHashSet<String>()
        for (seg in segments) {
            for (c in OpenAiCompatibleBaseCandidates.collect(seg, imageApiService::normalizeImageBase)) {
                bases.add(c)
            }
        }
        val baseList = bases.toList()
        if (baseList.isEmpty()) error("地址为空")
        onLine(
            JsonObject().apply {
                addProperty("type", "start")
                addProperty("total", baseList.size)
            },
        )
        var lastErr = "未找到可用根地址或密钥无效"
        baseList.forEachIndexed { i, base ->
            onLine(
                JsonObject().apply {
                    addProperty("type", "attempt")
                    addProperty("index", i + 1)
                    addProperty("total", baseList.size)
                    addProperty("base_url", base)
                    addProperty("display_base", base)
                },
            )
            val attempt = runCatching {
                when (channel) {
                    "text" -> {
                        val m = model.trim().ifBlank { "deepseek-chat" }
                        llmApiService.chatCompletion(
                            apiKey = apiKey,
                            baseUrl = base,
                            request = ChatRequest(
                                model = m,
                                messages = listOf(ChatMessage("user", "ping")),
                                temperature = 0f,
                                max_tokens = 2,
                            ),
                        ).content
                    }
                    "image" -> {
                        val m = model.trim().ifBlank { "dall-e-3" }
                        imageApiService.generateImage(
                            apiKey = apiKey,
                            baseUrl = base,
                            prompt = "ok",
                            model = m,
                            size = "256x256",
                            quality = "standard",
                        )
                    }
                    else -> error("未知 channel")
                }
            }
            val ok = attempt.isSuccess
            val err = attempt.exceptionOrNull()?.message?.trim()?.take(200)
            if (!ok && err != null) lastErr = err
            onLine(
                JsonObject().apply {
                    addProperty("type", "attempt_result")
                    addProperty("base_url", base)
                    addProperty("ok", ok)
                    if (!ok && err != null) addProperty("error", err)
                },
            )
            if (ok) {
                return JsonObject().apply {
                    addProperty("type", "done")
                    addProperty("ok", true)
                    addProperty("base_url", base)
                }
            }
        }
        return JsonObject().apply {
            addProperty("type", "done")
            addProperty("ok", false)
            addProperty("error", lastErr)
        }
    }

    private suspend fun probeRelayStream(
        root: String,
        channel: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        onLine: (JsonObject) -> Unit,
    ): JsonObject {
        val url = "$root/system/probe-public-api/stream"
        val body = JsonObject().apply {
            addProperty("channel", channel)
            addProperty("base_url", baseUrl)
            addProperty("api_key", apiKey)
            addProperty("model", model)
        }
        val req = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { resp ->
            val b = resp.body ?: error("empty body")
            if (!resp.isSuccessful) {
                val t = b.string()
                error(t.ifBlank { "HTTP ${resp.code}" })
            }
            val src = b.source()
            var lastDone: JsonObject? = null
            while (true) {
                val line = src.readUtf8Line() ?: break
                if (line.isBlank()) continue
                val obj = JsonParser.parseString(line).asJsonObject
                onLine(obj)
                if (obj.get("type")?.asString == "done") {
                    lastDone = obj
                }
            }
            return lastDone ?: error("流式响应未返回完成事件")
        }
    }
}
