package com.mojing.app.data.remote

import com.mojing.app.domain.config.OpenAiCompatibleRouting
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import java.io.IOException
import java.util.concurrent.TimeUnit
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
        val httpRequest = buildChatCompletionRequest(apiKey, baseUrl, request)
        return client.executeCancellable(httpRequest) { response ->
            if (!response.isSuccessful) {
                val code = response.code
                val body = response.body?.string().orEmpty()
                throw IllegalStateException(if (body.isBlank()) "HTTP $code" else body)
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
            )
        }
    }

    /**
     * Reads an OpenAI-compatible `text/event-stream` response incrementally.
     * OkHttp performs the blocking read on its dispatcher thread, and closing
     * the collecting flow explicitly cancels the underlying Call.
     */
    fun streamChatCompletion(
        apiKey: String,
        baseUrl: String,
        request: ChatRequest,
    ): Flow<String> = callbackFlow {
        val httpRequest = buildChatCompletionRequest(apiKey, baseUrl, request.copy(stream = true))
        val call = client.newCall(httpRequest)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) close() else close(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!response.isSuccessful) {
                            val code = response.code
                            val body = response.body?.string().orEmpty()
                            throw IllegalStateException(if (body.isBlank()) "HTTP $code" else body)
                        }

                        val source = response.body?.source()
                            ?: throw IllegalStateException("Empty streaming response body")
                        readSseResponse(source) { chunk -> trySend(chunk) }
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
            .post(gson.toJson(request).toRequestBody(JSON))
            .build()
    }

    private fun readSseResponse(
        source: BufferedSource,
        emitChunk: (String) -> Unit,
    ) {
        val dataLines = mutableListOf<String>()
        var done = false
        while (!done && !source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            when {
                line.isEmpty() -> done = emitSseEvent(dataLines, emitChunk)
                line.startsWith(":") -> Unit
                line.startsWith("data:") -> dataLines += line.removePrefix("data:").removePrefix(" ")
            }
        }
        if (!done && dataLines.isNotEmpty()) emitSseEvent(dataLines, emitChunk)
    }

    private fun emitSseEvent(
        dataLines: MutableList<String>,
        emitChunk: (String) -> Unit,
    ): Boolean {
        if (dataLines.isEmpty()) return false
        val data = dataLines.joinToString("\n")
        dataLines.clear()
        if (data.trim() == "[DONE]") return true

        val chunk = runCatching {
            val root = gson.fromJson(data, JsonObject::class.java)
            val choices = root.getAsJsonArray("choices") ?: return@runCatching ""
            val choice = choices.firstOrNull()?.asJsonObject ?: return@runCatching ""
            val delta = choice.getAsJsonObject("delta")
                ?: choice.getAsJsonObject("message")
                ?: return@runCatching ""
            val content = delta.get("content") ?: return@runCatching ""
            when {
                content.isJsonPrimitive -> content.asString
                content.isJsonArray -> content.asJsonArray.joinToString("") { part ->
                    if (part.isJsonObject) {
                        part.asJsonObject.get("text")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                    } else {
                        ""
                    }
                }
                else -> ""
            }
        }.getOrElse { "" }
        if (chunk.isNotEmpty()) emitChunk(chunk)
        return false
    }
}
