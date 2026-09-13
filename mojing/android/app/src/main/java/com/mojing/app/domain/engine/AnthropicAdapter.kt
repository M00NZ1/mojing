package com.mojing.app.domain.engine

import com.mojing.app.data.remote.ChatMessage
import com.mojing.app.data.remote.ChatCompletionResult
import com.mojing.app.data.remote.LlmHttpException
import com.mojing.app.data.remote.LlmProtocolException
import com.mojing.app.data.remote.TokenUsage
import com.mojing.app.data.remote.parseRetryAfterMs
import kotlinx.coroutines.channels.trySendBlocking
import com.mojing.app.data.remote.executeCancellable
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.mojing.app.domain.config.OpenAiCompatibleRouting
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnthropicAdapter @Inject constructor(
    private val client: OkHttpClient
) {
    companion object {
        const val API_VERSION = "2023-06-01"
    }

    fun streamChat(
        apiKey: String,
        baseUrl: String,
        model: String,
        systemPrompt: String,
        messages: List<ChatMessage>,
        temperature: Float,
        maxTokens: Int,
        strictErrors: Boolean = false,
    ): Flow<String> = streamChatWithUsage(
        apiKey, baseUrl, model, systemPrompt, messages, temperature, maxTokens, strictErrors,
    ) { }

    fun streamChatWithUsage(
        apiKey: String,
        baseUrl: String,
        model: String,
        systemPrompt: String,
        messages: List<ChatMessage>,
        temperature: Float,
        maxTokens: Int,
        strictErrors: Boolean = false,
        onUsage: (TokenUsage?) -> Unit,
    ): Flow<String> = callbackFlow {
        val url = buildMessagesUrl(baseUrl)
        val key = apiKey.trim().removePrefix("Bearer ").trim()

        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            put("temperature", temperature.toDouble())
            if (systemPrompt.isNotBlank()) put("system", systemPrompt)
            put("stream", true)
            put("messages", JSONArray().apply {
                messages.filter { it.role != "system" }.forEach { msg ->
                    put(JSONObject().apply {
                        put("role", if (msg.role == "assistant") "assistant" else "user")
                        put("content", msg.content)
                    })
                }
            })
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", key)
            .addHeader("anthropic-version", API_VERSION)
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val listener = object : EventSourceListener() {
            private var completed = false
            private var inputTokens: Int? = null
            private var cachedInputTokens = 0
            private var outputTokens: Int? = null
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                when (type) {
                    "message_start" -> runCatching {
                        val usage = JSONObject(data).optJSONObject("message")?.optJSONObject("usage")
                        if (usage != null) {
                            inputTokens = usage.optInt("input_tokens", -1).takeIf { it >= 0 }
                            cachedInputTokens = usage.optInt("cache_read_input_tokens", 0).coerceAtLeast(0)
                            inputTokens = inputTokens?.let { (it.toLong() + cachedInputTokens + usage.optInt("cache_creation_input_tokens", 0).coerceAtLeast(0)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
                        }
                    }
                    "content_block_delta" -> {
                        try {
                            val json = JSONObject(data)
                            val delta = json.optJSONObject("delta")
                            if (delta != null && delta.optString("type") == "text_delta") {
                                val text = delta.optString("text", "")
                                if (text.isNotEmpty()) trySendBlocking(text).getOrThrow()
                            }
                        } catch (_: Exception) { if (strictErrors) close(LlmProtocolException("invalid_stream")) }
                    }
                    "message_delta" -> {
                        runCatching {
                            val json = JSONObject(data)
                            val usage = json.optJSONObject("usage")
                            if (usage != null) outputTokens = usage.optInt("output_tokens", -1).takeIf { it >= 0 }
                            val reason = json.optJSONObject("delta")?.optString("stop_reason")
                            if (strictErrors && !reason.isNullOrEmpty() && reason !in setOf("end_turn", "stop_sequence", "null")) {
                                close(LlmProtocolException(if (reason == "max_tokens") "output_limit" else "incomplete_output"))
                            }
                        }.onFailure { if (strictErrors) close(LlmProtocolException("invalid_stream")) }
                    }
                    "error" -> if (strictErrors) close(LlmProtocolException("provider_stream_error"))
                    "message_stop" -> {
                        completed = true
                        onUsage(inputTokens?.let { input -> outputTokens?.let { output -> TokenUsage(input, output, cachedInputTokens) } })
                        close()
                    }
                }
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: okhttp3.Response?
            ) {
                val code = response?.code ?: 0
                val requestId = response?.header("request-id") ?: response?.header("x-request-id")
                val failure = if (response != null && code > 0) LlmHttpException(
                    status = code,
                    requestId = requestId,
                    retryAfterMs = parseRetryAfterMs(response.header("Retry-After")),
                ) else t ?: java.io.IOException("Streaming request failed")
                if (strictErrors) close(failure) else {
                    trySend("__ERROR__${if (code > 0) "HTTP $code" else "请求失败"}")
                    close()
                }
            }

            override fun onClosed(eventSource: EventSource) {
                if (completed) close() else if (strictErrors) {
                    close(java.io.IOException("Streaming response ended before completion"))
                } else close()
            }
        }

        val eventSource = EventSources.createFactory(client)
            .newEventSource(request, listener)

        awaitClose { eventSource.cancel() }
    }

    suspend fun nonStreamingCall(
        apiKey: String, baseUrl: String, model: String, systemPrompt: String,
        messages: List<ChatMessage>, temperature: Float, maxTokens: Int,
    ): String = complete(apiKey, baseUrl, model, systemPrompt, messages, temperature, maxTokens).content

    suspend fun complete(
        apiKey: String, baseUrl: String, model: String, systemPrompt: String,
        messages: List<ChatMessage>, temperature: Float, maxTokens: Int,
    ): ChatCompletionResult {
        val gson = Gson()
        val body = linkedMapOf<String, Any>("model" to model, "max_tokens" to maxTokens,
            "temperature" to temperature, "messages" to messages.filter { it.role != "system" }.map {
                mapOf("role" to if (it.role == "assistant") "assistant" else "user", "content" to it.content)
            })
        if (systemPrompt.isNotBlank()) body["system"] = systemPrompt
        val request = Request.Builder().url(buildMessagesUrl(baseUrl))
            .header("x-api-key", apiKey.trim().removePrefix("Bearer ").trim())
            .header("anthropic-version", API_VERSION)
            .post(gson.toJson(body).toRequestBody("application/json".toMediaType())).build()
        return client.executeCancellable(request) { response ->
            if (!response.isSuccessful) throw LlmHttpException(
                status = response.code,
                requestId = response.header("request-id") ?: response.header("x-request-id"),
                retryAfterMs = parseRetryAfterMs(response.header("Retry-After")),
            )
            val json = gson.fromJson(response.body?.string().orEmpty(), JsonObject::class.java)
            val content = json.getAsJsonArray("content")?.joinToString("") {
                val block = it.asJsonObject
                if (block.get("type")?.asString == "text") block.get("text")?.asString.orEmpty() else ""
            }.orEmpty()
            val usage = json.getAsJsonObject("usage")
            val input = usage?.get("input_tokens")?.asInt ?: 0
            val output = usage?.get("output_tokens")?.asInt ?: 0
            val usageProvided = usage?.has("input_tokens") == true && usage.has("output_tokens")
            val cached = usage?.get("cache_read_input_tokens")?.asInt ?: 0
            val totalInput = (input.toLong() + cached + (usage?.get("cache_creation_input_tokens")?.asInt ?: 0)).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
            ChatCompletionResult(content, totalInput, output, (totalInput.toLong() + output).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                finishReason = if (json.get("stop_reason")?.asString == "max_tokens") "length" else null)
                .copy(cachedPromptTokens = cached, usageProvided = usageProvided)
        }
    }

    private fun buildMessagesUrl(baseUrl: String): String {
        val b = baseUrl.trimEnd('/')
        return if (b.endsWith("/v1/messages") || b.endsWith("/messages")) b
        else if (b.endsWith("/v1")) "$b/messages" else "$b/v1/messages"
    }

}
