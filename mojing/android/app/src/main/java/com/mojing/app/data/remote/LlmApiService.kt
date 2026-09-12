package com.mojing.app.data.remote

import com.mojing.app.domain.config.OpenAiCompatibleRouting
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.channels.trySendBlocking
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.min
import javax.inject.Inject
import javax.inject.Singleton

private val JSON = "application/json; charset=utf-8".toMediaType()

@Singleton
class LlmApiService @Inject constructor() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val anthropicAdapter by lazy { com.mojing.app.domain.engine.AnthropicAdapter(client) }

    suspend fun listModels(
        baseUrl: String,
        apiKey: String,
        anthropic: Boolean = baseUrl.toHttpUrl().host == "api.anthropic.com",
    ): List<String> = kotlinx.coroutines.withTimeout(60_000) {
        val base = OpenAiCompatibleRouting.normalizeBase(baseUrl)
        val url = (OpenAiCompatibleRouting.buildChatCompletionsUrl(base)
            .removeSuffix("/chat/completions") + "/models").toHttpUrl()
        val discoveryClient = client.newBuilder().followRedirects(false).followSslRedirects(false)
            .callTimeout(30, TimeUnit.SECONDS).build()
        val names = linkedSetOf<String>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val pageUrl = url.newBuilder().apply {
                if (anthropic) addQueryParameter("limit", "1000")
                cursor?.let { addQueryParameter("after_id", it) }
            }.build()
            val request = Request.Builder().url(pageUrl).apply {
                if (anthropic) {
                    header("x-api-key", apiKey.trim())
                    header("anthropic-version", OpenAiCompatibleRouting.ANTHROPIC_VERSION)
                } else header("Authorization", OpenAiCompatibleRouting.bearerAuth(apiKey))
            }.get().build()
            val page = discoveryClient.executeCancellable(request) { response ->
                if (!response.isSuccessful) throw response.toLlmHttpException()
                gson.fromJson(response.body?.string().orEmpty(), JsonObject::class.java)
            }
            page.getAsJsonArray("data")?.forEach { item ->
                item.asJsonObject.get("id")?.asString?.trim()?.takeIf(String::isNotEmpty)?.let(names::add)
            }
            cursor = if (page.get("has_more")?.asBoolean == true) {
                val next = page.get("last_id")?.takeUnless { it.isJsonNull }?.asString
                check(!next.isNullOrBlank() && cursors.add(next) && cursors.size <= 100) { "平台分页异常，请手动填写模型名" }
                next
            } else null
        } while (cursor != null)
        check(names.isNotEmpty()) { "平台未返回模型列表，请手动填写模型名" }
        names.toList()
    }

    fun normalizeOpenAiCompatibleBase(baseUrl: String): String {
        val trimmed = baseUrl.trim()
        if (trimmed.isBlank()) return ""
        val normalized = trimmed.trimEnd('/')
        return when {
            normalized.endsWith("/v1", ignoreCase = true) -> normalized
            normalized.endsWith("/v2", ignoreCase = true) -> normalized
            normalized.endsWith("/v3", ignoreCase = true) -> normalized
            normalized.contains("/v1/", ignoreCase = true) -> normalized.substringBefore("/v1/") + "/v1"
            normalized.contains("/v2/", ignoreCase = true) -> normalized.substringBefore("/v2/") + "/v2"
            normalized.contains("/v3/", ignoreCase = true) -> normalized.substringBefore("/v3/") + "/v3"
            else -> normalized
        }
    }

    suspend fun chatCompletion(
        apiKey: String,
        baseUrl: String,
        request: ChatRequest,
    ): ChatCompletionResult {
        if (baseUrl.toHttpUrl().host == "api.anthropic.com") {
            return anthropicAdapter.complete(apiKey, baseUrl, request.model,
                request.messages.filter { it.role == "system" }.joinToString("\n\n") { it.content },
                request.messages, request.temperature, request.max_tokens)
        }
        val httpRequest = buildChatCompletionRequest(apiKey, baseUrl, request)
        return client.executeCancellable(httpRequest) { response ->
            if (!response.isSuccessful) {
                throw response.toLlmHttpException()
            }
            val body = response.body?.string().orEmpty()
            val parsed = gson.fromJson(body, Map::class.java)
            val choices = parsed["choices"] as? List<*>
            val message = (choices?.getOrNull(0) as? Map<*, *>)?.get("message") as? Map<*, *>
            val content = message?.get("content") as? String ?: ""
            val usage = parsed["usage"] as? Map<*, *>
            val promptTokens = (usage?.get("prompt_tokens") as? Number)?.toInt() ?: 0
            val completionTokens = (usage?.get("completion_tokens") as? Number)?.toInt() ?: 0
            val totalTokens = (usage?.get("total_tokens") as? Number)?.toInt()
                ?: (promptTokens + completionTokens)
            ChatCompletionResult(
                content = content,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                totalTokens = totalTokens,
                finishReason = (choices?.firstOrNull() as? Map<*, *>)?.get("finish_reason") as? String,
            )
        }
    }

    /**
     * Reads an OpenAI-compatible `text/event-stream` response incrementally.
     * OkHttp performs the blocking read on its dispatcher thread, and closing
     * the collecting flow explicitly cancels the underlying Call.
     */
    fun streamChatCompletion(apiKey: String, baseUrl: String, request: ChatRequest): Flow<String> =
        streamCompletion(apiKey, baseUrl, request, strict = false)

    /** 完整结构化生成要求明确的结束标记，并单独限制无数据等待时间。 */
    fun streamStoryCompletion(apiKey: String, baseUrl: String, request: ChatRequest): Flow<String> =
        streamCompletion(apiKey, baseUrl, request, strict = true)

    private fun streamCompletion(
        apiKey: String, baseUrl: String, request: ChatRequest, strict: Boolean,
    ): Flow<String> = flow {
        val requestClient = if (strict) client.newBuilder().readTimeout(90, TimeUnit.SECONDS).build() else client
        if (baseUrl.toHttpUrl().host == "api.anthropic.com") {
            emitAll(com.mojing.app.domain.engine.AnthropicAdapter(requestClient).streamChat(
                apiKey, baseUrl, request.model,
                request.messages.filter { it.role == "system" }.joinToString("\n\n") { it.content },
                request.messages, request.temperature, request.max_tokens, strictErrors = strict,
            ))
        } else {
            emitAll(streamOpenAiCompletion(requestClient, apiKey, baseUrl, request, strict))
        }
    }

    private fun streamOpenAiCompletion(
        requestClient: OkHttpClient, apiKey: String, baseUrl: String,
        request: ChatRequest, strict: Boolean,
    ): Flow<String> = callbackFlow {
        val httpRequest = buildChatCompletionRequest(apiKey, baseUrl, request.copy(stream = true))
        val call = requestClient.newCall(httpRequest)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) close() else close(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!response.isSuccessful) throw response.toLlmHttpException()
                        if (strict && response.header("Content-Type")?.contains("text/event-stream", ignoreCase = true) != true) {
                            throw LlmProtocolException("unsupported_stream")
                        }
                        val source = response.body?.source() ?: throw LlmProtocolException("empty_response")
                        val completed = readSseResponse(source, strict) { chunk -> trySendBlocking(chunk).getOrThrow() }
                        if (strict && !completed) throw IOException("Streaming response ended before completion")
                        close()
                    } catch (e: Throwable) {
                        if (call.isCanceled()) close() else close(e)
                    }
                }
            }
        })
        awaitClose { call.cancel() }
    }

    suspend fun generateImage(
        apiKey: String,
        baseUrl: String,
        prompt: String,
        model: String = "dall-e-3",
        size: String = "1024x1024",
        quality: String = "standard",
    ): String? {
        val root = baseUrl.trim().trimEnd('/')
        val url = when {
            root.endsWith("/v1", ignoreCase = true) -> "$root/images/generations"
            root.endsWith("/v2", ignoreCase = true) -> "$root/images/generations"
            root.endsWith("/v3", ignoreCase = true) -> "$root/images/generations"
            else -> "$root/v1/images/generations"
        }
        val payload = JsonObject().apply {
            addProperty("prompt", prompt)
            addProperty("model", model)
            addProperty("size", size)
            addProperty("quality", quality)
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", OpenAiCompatibleRouting.bearerAuth(apiKey))
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        return client.executeCancellable(request) { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException(response.body?.string().orEmpty().ifBlank { "HTTP ${response.code}" })
            }
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return@executeCancellable null
            val parsed = gson.fromJson(body, Map::class.java)
            val data = parsed["data"] as? List<*>
            val first = data?.firstOrNull() as? Map<*, *>
            (first?.get("url") as? String)?.takeIf { it.isNotBlank() }
                ?: (first?.get("b64_json") as? String)?.takeIf { it.isNotBlank() }
        }
    }

    fun normalizeImageBase(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isBlank()) return ""
        return when {
            trimmed.endsWith("/v1", ignoreCase = true) -> trimmed
            trimmed.endsWith("/v2", ignoreCase = true) -> trimmed
            trimmed.endsWith("/v3", ignoreCase = true) -> trimmed
            else -> trimmed
        }
    }

    suspend fun downloadImage(imageUrl: String): String? {
        val request = Request.Builder()
            .url(imageUrl)
            .get()
            .build()
        return client.executeCancellable(
            request = request,
            onCancellation = { path: String? -> path?.let { runCatching { java.io.File(it).delete() } } },
        ) { response ->
            if (!response.isSuccessful) return@executeCancellable null
            val file = kotlin.io.path.createTempFile(prefix = "mojing_image_", suffix = ".png").toFile()
            file.outputStream().use { out ->
                response.body?.byteStream()?.copyTo(out)
            }
            file.absolutePath
        }
    }

    private fun buildChatCompletionRequest(
        apiKey: String,
        baseUrl: String,
        request: ChatRequest,
    ): Request {
        val root = normalizeOpenAiCompatibleBase(baseUrl).trimEnd('/')
        val url = when {
            root.endsWith("/v1", ignoreCase = true) -> "$root/chat/completions"
            root.endsWith("/v2", ignoreCase = true) -> "$root/chat/completions"
            root.endsWith("/v3", ignoreCase = true) -> "$root/chat/completions"
            else -> "$root/v1/chat/completions"
        }
        return Request.Builder()
            .url(url)
            .header("Authorization", OpenAiCompatibleRouting.bearerAuth(apiKey))
            .header("Content-Type", "application/json")
            .post(chatPayload(baseUrl, request).toString().toRequestBody(JSON))
            .build()
    }

    internal fun chatPayload(baseUrl: String, request: ChatRequest): JsonObject =
        gson.toJsonTree(request).asJsonObject.apply {
            if (request.jsonOutput) {
                val host = baseUrl.toHttpUrl().host
                val model = request.model.lowercase()
                val deepseek = host == "api.deepseek.com" && model in setOf("deepseek-chat", "deepseek-flash", "deepseek-v4-flash", "deepseek-v4-pro")
                val siliconflow = host in setOf("api.siliconflow.cn", "api.siliconflow.com") &&
                    (model.startsWith("deepseek-ai/deepseek-v3") || model.startsWith("pro/deepseek-ai/deepseek-v3") ||
                        model.startsWith("deepseek-ai/deepseek-v4") || model.startsWith("pro/deepseek-ai/deepseek-v4"))
                if (deepseek || siliconflow) {
                    add("response_format", JsonObject().apply { addProperty("type", "json_object") })
                    // These hybrid models support non-thinking structured output. Ordinary chat keeps provider defaults.
                    if (deepseek) add("thinking", JsonObject().apply { addProperty("type", "disabled") })
                    else addProperty("enable_thinking", false)
                }
            }
        }

    private fun readSseResponse(
        source: BufferedSource,
        strict: Boolean,
        emitChunk: (String) -> Unit,
    ): Boolean {
        val dataLines = mutableListOf<String>()
        var done = false
        while (!done && !source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            when {
                line.isEmpty() -> done = emitSseEvent(dataLines, strict, emitChunk)
                line.startsWith(":") -> Unit
                line.startsWith("data:") -> dataLines += line.removePrefix("data:").removePrefix(" ")
            }
        }
        if (!done && dataLines.isNotEmpty()) done = emitSseEvent(dataLines, strict, emitChunk)
        return done
    }

    private fun emitSseEvent(
        dataLines: MutableList<String>, strict: Boolean, emitChunk: (String) -> Unit,
    ): Boolean {
        if (dataLines.isEmpty()) return false
        val data = dataLines.joinToString("\n")
        dataLines.clear()
        if (data.trim() == "[DONE]") return true
        val root = runCatching { gson.fromJson(data, JsonObject::class.java) }.getOrNull()
            ?: if (strict) throw LlmProtocolException("invalid_stream") else return false
        if (root.has("error")) throw LlmProtocolException("provider_stream_error")
        val choice = root.get("choices")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return false
        val finish = choice.get("finish_reason")?.takeIf { it.isJsonPrimitive }?.asString
        val delta = (choice.get("delta") ?: choice.get("message"))?.takeIf { it.isJsonObject }?.asJsonObject
        val content = delta?.get("content")
        val chunk = when {
            content?.isJsonPrimitive == true && content.asJsonPrimitive.isString -> content.asString
            content?.isJsonArray == true -> content.asJsonArray.joinToString("") { part ->
                part.takeIf { it.isJsonObject }?.asJsonObject?.get("text")
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
            }
            else -> ""
        }
        if (chunk.isNotEmpty()) emitChunk(chunk)
        if (strict && finish != null && finish != "stop") {
            throw LlmProtocolException(if (finish == "length") "output_limit" else "incomplete_output")
        }
        return strict && finish == "stop"
    }

    private fun Response.toLlmHttpException(): LlmHttpException {
        val payload = runCatching { peekBody(4096L).string() }.getOrDefault("")
        val root = runCatching { gson.fromJson(payload, JsonObject::class.java) }.getOrNull()
        val error = root?.get("error")?.takeIf { it.isJsonObject }?.asJsonObject
        val candidate = (error?.get("code") ?: error?.get("type"))
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        return LlmHttpException(
            status = code,
            requestId = header("x-request-id") ?: header("request-id") ?: header("x-siliconcloud-trace-id"),
            errorCode = candidate,
            retryAfterMs = parseRetryAfterMs(header("Retry-After")),
        )
    }
}
