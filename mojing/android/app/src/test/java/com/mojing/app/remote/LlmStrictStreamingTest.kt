package com.mojing.app.remote

import com.mojing.app.data.remote.ChatMessage
import com.mojing.app.data.remote.ChatRequest
import com.mojing.app.data.remote.LlmApiService
import com.mojing.app.data.remote.LlmProtocolException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class LlmStrictStreamingTest {
    private val request = ChatRequest(
        model = "mock-model",
        messages = listOf(ChatMessage("user", "hello")),
        temperature = 0.2f,
        max_tokens = 64,
    )

    @Test
    fun strictStreamAcceptsStopAndDone() = runBlocking {
        withServer(
            "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                "data: [DONE]\n\n",
        ) { server ->
            assertEquals(listOf("hello"), LlmApiService().streamStoryCompletion("key", server.url("/").toString(), request).toList())
        }
    }

    @Test
    fun strictStreamRejectsMissingDoneLengthAndProviderError() = runBlocking {
        val cases = listOf(
            "data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\n" to "",
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\n" to "output_limit",
            "data: {\"error\":{\"message\":\"provider failed\"}}\n\n" to "provider_stream_error",
        )
        for ((body, reason) in cases) {
            withServer(body) { server ->
                val error = runCatching {
                    LlmApiService().streamStoryCompletion("key", server.url("/").toString(), request).toList()
                }.exceptionOrNull()
                assertTrue(error is LlmProtocolException || error is java.io.IOException)
                if (reason.isNotEmpty()) assertEquals(reason, (error as LlmProtocolException).reason)
            }
        }
    }

    @Test
    fun strictStreamRejectsNonSseContentType() = runBlocking {
        withServer("{\"choices\":[]}", contentType = "application/json") { server ->
            val error = runCatching {
                LlmApiService().streamStoryCompletion("key", server.url("/").toString(), request).toList()
            }.exceptionOrNull()
            assertEquals("unsupported_stream", (error as LlmProtocolException).reason)
        }
    }

    @Test
    fun slowConsumerReceivesAllChunksWithBackpressure() = runBlocking {
        val body = (1..80).joinToString("") { "data: {\"choices\":[{\"delta\":{\"content\":\"$it,\"}}]}\n\n" } + "data: [DONE]\n\n"
        withServer(body) { server ->
            val result = StringBuilder()
            LlmApiService().streamStoryCompletion("key", server.url("/").toString(), request)
                .collect { delay(1); result.append(it) }
            assertEquals((1..80).joinToString("") { "$it," }, result.toString())
        }
    }

    @Test
    fun cancellingStrictStreamClosesRequest() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        server.start()
        try {
            val api = LlmApiService()
            val actualCall = java.util.concurrent.atomic.AtomicReference<okhttp3.Call>()
            val field = LlmApiService::class.java.getDeclaredField("client").apply { isAccessible = true }
            val client = (field.get(api) as okhttp3.OkHttpClient).newBuilder()
                .eventListener(object : okhttp3.EventListener() {
                    override fun callStart(call: okhttp3.Call) { actualCall.set(call) }
                }).build()
            field.set(api, client)
            val job = launch(Dispatchers.Default) {
                api.streamStoryCompletion("key", server.url("/").toString(), request).collect {}
            }
            withTimeout(2_000) { while (server.requestCount == 0) delay(10) }
            job.cancelAndJoin()
            assertTrue(actualCall.get().isCanceled())
        } finally {
            server.shutdown()
        }
    }

    private suspend fun withServer(body: String, contentType: String = "text/event-stream", block: suspend (MockWebServer) -> Unit) {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", contentType).setBody(body))
        server.start()
        try { block(server) } finally { server.shutdown() }
    }
}
