package com.mojing.app.domain.engine

import com.mojing.app.data.remote.ChatMessage
import com.mojing.app.data.remote.ChatCompletionResult
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
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                when (type) {
                    "content_block_delta" -> {
                        try {
                            val json = JSONObject(data)
                            val delta = json.optJSONObject("delta")
                            if (delta != null && delta.optString("type") == "text_delta") {
                                val text = delta.optString("text", "")
                                if (text.isNotEmpty()) trySend(text)
                            }
                        } catch (_: Exception) {}
                    }
                    "message_stop" -> close()
                }
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: okhttp3.Response?
            ) {
                val code = response?.code ?: 0
                val errorMsg = when (code) {
                    401 -> "Anthropic API Key \u65e0\u6548"
                    429 -> "\u8bf7\u6c42\u8fc7\u4e8e\u9891\u7e41\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5"
                    404 -> "\u6a21\u578b\u540d\u79f0\u9519\u8bef\u6216\u63a5\u53e3\u8def\u5f84\u4e0d\u5bf9"
                    in 500..599 -> "\u670d\u52a1\u7aef\u6682\u65f6\u4e0d\u53ef\u7528($code)"
                    else -> t?.message ?: "\u8bf7\u6c42\u5931\u8d25"
                }
                trySend("__ERROR__$errorMsg")
                close()
            }

            override fun onClosed(eventSource: EventSource) { close() }
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
            check(response.isSuccessful) { "Anthropic 请求失败（HTTP ${response.code}）" }
            val json = gson.fromJson(response.body?.string().orEmpty(), JsonObject::class.java)
            val content = json.getAsJsonArray("content")?.joinToString("") {
                val block = it.asJsonObject
                if (block.get("type")?.asString == "text") block.get("text")?.asString.orEmpty() else ""
            }.orEmpty()
            val usage = json.getAsJsonObject("usage")
            val input = usage?.get("input_tokens")?.asInt ?: 0
            val output = usage?.get("output_tokens")?.asInt ?: 0
            ChatCompletionResult(content, input, output, input + output)
        }
    }

    private fun buildMessagesUrl(baseUrl: String): String {
        val b = baseUrl.trimEnd('/')
        return if (b.endsWith("/v1/messages") || b.endsWith("/messages")) b
        else if (b.endsWith("/v1")) "$b/messages" else "$b/v1/messages"
    }
}
