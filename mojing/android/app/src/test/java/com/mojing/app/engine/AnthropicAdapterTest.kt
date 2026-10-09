package com.mojing.app.engine

import com.mojing.app.domain.engine.AnthropicAdapter
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.concurrent.TimeUnit

class AnthropicAdapterTest {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val adapter = AnthropicAdapter(client)

    @Test fun nativeCompletionOmitsUnsupportedSamplingAndKeepsLegacyTemperature() = kotlinx.coroutines.runBlocking {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            listOf("claude-opus-4-7" to false, "claude-mythos-preview" to false,
                "claude-opus-4-6" to true, "custom-alias" to true).forEach { (model, accepts) ->
                server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"content":[{"type":"text","text":"done"}],"stop_reason":"end_turn","usage":{"input_tokens":3,"output_tokens":2}}"""))
                val result = adapter.complete("fixture-key", server.url("/v1").toString(), model, "system",
                    listOf(com.mojing.app.data.remote.ChatMessage("user", "fixture")), 0.6f, 4097)
                assertEquals("done", result.content)
                assertEquals(5, result.totalTokens)
                val request = server.takeRequest()
                val body = com.google.gson.JsonParser.parseString(request.body.readUtf8()).asJsonObject
                assertEquals(model, body.get("model").asString)
                assertEquals(4097, body.get("max_tokens").asInt)
                assertEquals(accepts, body.has("temperature"))
                if (accepts) assertEquals(0.6, body.get("temperature").asDouble, 0.0001)
                listOf("top_p", "top_k", "frequency_penalty", "presence_penalty").forEach { assertFalse(body.has(it)) }
            }
        }
    }

    @Test
    fun messagesUrlWithBaseOnly() {
        val url = invokeBuildMessagesUrl("https://api.anthropic.com")
        assertEquals("https://api.anthropic.com/v1/messages", url)
    }

    @Test
    fun messagesUrlWithV1MessagesAlready() {
        val url = invokeBuildMessagesUrl("https://api.anthropic.com/v1/messages")
        assertEquals("https://api.anthropic.com/v1/messages", url)
    }

    @Test
    fun messagesUrlWithTrailingSlash() {
        val url = invokeBuildMessagesUrl("https://api.anthropic.com/")
        assertEquals("https://api.anthropic.com/v1/messages", url)
    }

    @Test
    fun apiVersionConstant() {
        assertEquals("2023-06-01", AnthropicAdapter.API_VERSION)
    }

    @Test
    fun adapterConstructsWithClient() {
        val a = AnthropicAdapter(client)
        assertTrue(a is AnthropicAdapter)
    }

    private fun invokeBuildMessagesUrl(baseUrl: String): String {
        val method = AnthropicAdapter::class.java.getDeclaredMethod(
            "buildMessagesUrl", String::class.java
        )
        method.isAccessible = true
        return method.invoke(adapter, baseUrl) as String
    }
}
