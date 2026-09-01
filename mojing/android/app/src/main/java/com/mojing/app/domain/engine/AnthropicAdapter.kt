package com.mojing.app.domain.engine

import com.mojing.app.data.remote.ChatMessage
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
        apiKey: String,
        baseUrl: String,
        model: String,
        systemPrompt: String,
        messages: List<ChatMessage>,
        temperature: Float,
        maxTokens: Int,
    ): String {
        val url = buildMessagesUrl(baseUrl)
        val key = apiKey.trim().removePrefix("Bearer ").trim()

        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            put("temperature", temperature.toDouble())
            if (systemPrompt.isNotBlank()) put("system", systemPrompt)
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

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Anthropic \u8bf7\u6c42\u5931\u8d25: ${response.code}")
        }
        val bodyStr = response.body?.string() ?: ""
        val json = JSONObject(bodyStr)
        val content = json.optJSONArray("content")
        return content?.let { arr ->
            (0 until arr.length()).joinToString("") { i ->
                val block = arr.optJSONObject(i)
                if (block?.optString("type") == "text") block.optString("text", "") else ""
            }
        } ?: ""
    }

    private fun buildMessagesUrl(baseUrl: String): String {
        val b = baseUrl.trimEnd('/')
        return if (b.endsWith("/v1/messages") || b.endsWith("/messages")) b
        else "$b/v1/messages"
    }
}
