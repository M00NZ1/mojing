package com.mojing.app.data.remote

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ModelDiscoveryTest {
    @Test fun nativeAnthropicCompletionUsesMessagesEndpointAndPreservesUsage() = kotlinx.coroutines.runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"hello"}],"usage":{"input_tokens":3,"output_tokens":2}}"""))
            val adapter = com.mojing.app.domain.engine.AnthropicAdapter(okhttp3.OkHttpClient())
            val result = adapter.complete("test-anthropic", server.url("/v1").toString(), "model", "system prompt",
                listOf(ChatMessage("system", "system prompt"), ChatMessage("user", "hi")), 0.5f, 12)
            assertEquals("hello", result.content)
            assertEquals(5, result.totalTokens)
            val request = server.takeRequest()
            assertEquals("/v1/messages", request.path)
            assertEquals("test-anthropic", request.getHeader("x-api-key"))
            val body = com.google.gson.JsonParser.parseString(request.body.readUtf8()).asJsonObject
            assertEquals("system prompt", body.get("system").asString)
            assertEquals(1, body.getAsJsonArray("messages").size())
        }
    }

    @Test fun anthropicUsesNativeHeadersAndReadsEveryPage() = kotlinx.coroutines.runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"first"}],"has_more":true,"last_id":"first"}"""))
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"second"}],"has_more":false}"""))
            val result = LlmApiService().listModels(server.url("/v1").toString(), "test-anthropic", anthropic = true)
            assertEquals(listOf("first", "second"), result)
            val first = server.takeRequest()
            assertEquals("test-anthropic", first.getHeader("x-api-key"))
            assertEquals("2023-06-01", first.getHeader("anthropic-version"))
            assertNull(first.getHeader("Authorization"))
            assertTrue(server.takeRequest().path.orEmpty().contains("after_id=first"))
        }
    }

    @Test fun discoversEveryReturnedModelWithoutVendorFiltering() = kotlinx.coroutines.runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"chat-a"},{"id":"org/image"},{"id":"chat-a"},{"id":"reasoner"}]}"""))
            val result = LlmApiService().listModels(server.url("/v1").toString(), "test-platform-key")
            assertEquals(listOf("chat-a", "org/image", "reasoner"), result)
            val request = server.takeRequest()
            assertEquals("/v1/models", request.path)
            assertEquals("Bearer test-platform-key", request.getHeader("Authorization"))
        }
    }

    @Test fun unsupportedEndpointReturnsAnErrorWithoutEchoingResponseSecrets() = kotlinx.coroutines.runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("private response"))
            val error = runCatching { LlmApiService().listModels(server.url("/v1").toString(), "test-key") }.exceptionOrNull()
            assertNotNull(error)
            assertFalse(error!!.message.orEmpty().contains("private response"))
        }
    }
}
