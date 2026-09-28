package com.mojing.app.remote

import com.mojing.app.data.remote.ChatMessage
import com.mojing.app.data.remote.ChatRequest
import com.mojing.app.data.remote.LlmApiService
import com.mojing.app.data.remote.LlmHttpException
import com.mojing.app.data.remote.LlmProtocolException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LlmApiServiceTest {

    private val api = LlmApiService()

    @Test
    fun structuredOutputUsesProviderSpecificModesWithoutChangingNormalChat() {
        val request = testRequest().copy(model = "deepseek-flash", jsonOutput = true)
        val deepseek = api.chatPayload("https://api.deepseek.com", request)
        assertEquals("disabled", deepseek.getAsJsonObject("thinking").get("type").asString)
        assertEquals("json_object", deepseek.getAsJsonObject("response_format").get("type").asString)
        assertTrue(!deepseek.has("jsonOutput"))
        val siliconflow = api.chatPayload("https://api.siliconflow.cn/v1", request.copy(model = "Pro/deepseek-ai/DeepSeek-V3.2"))
        assertEquals(false, siliconflow.get("enable_thinking").asBoolean)
        assertEquals("json_object", siliconflow.getAsJsonObject("response_format").get("type").asString)
        for ((url, req) in listOf(
            "https://api.deepseek.com" to request.copy(jsonOutput = false),
            "https://custom.test" to request,
            "https://api.deepseek.com" to request.copy(model = "deepseek-reasoner"),
        )) {
            val payload = api.chatPayload(url, req)
            assertTrue(!payload.has("thinking") && !payload.has("enable_thinking") && !payload.has("response_format"))
        }
    }

    @Test
    fun httpFailuresExposeStatusAndBoundedSafeMetadata() = runBlocking {
        listOf(400, 401, 429).forEach { status ->
            val server = RawHttpServer { socket ->
                readRequest(socket)
                socket.getOutputStream().use { output ->
                    val body = "{\"error\":{\"code\":\"secret-provider-body\"}}"
                    output.write("HTTP/1.1 $status Error\r\nContent-Type: application/json\r\nX-Request-Id: safe-id\r\nContent-Length: ${body.length}\r\n\r\n$body".toByteArray())
                }
            }
            server.start()
            try {
                val error = runCatching { api.chatCompletion("key-not-for-logs", server.baseUrl, testRequest()) }.exceptionOrNull()
                assertTrue(error is LlmHttpException)
                assertEquals(status, (error as LlmHttpException).status)
                assertEquals("safe-id", error.requestId)
                assertTrue(error.message.orEmpty().contains("secret-provider-body").not())
            } finally { server.close() }
        }
    }

    @Test
    fun normalizeOpenAiCompatibleBaseKeepsV1Path() {
        val normalized = api.normalizeOpenAiCompatibleBase("https://api.openai.com/v1")
        assertEquals("https://api.openai.com/v1", normalized)
    }

    @Test
    fun normalizeOpenAiCompatibleBaseStripsExtraSegments() {
        val normalized = api.normalizeOpenAiCompatibleBase("https://api.openai.com/v1/chat")
        assertEquals("https://api.openai.com/v1", normalized)
    }

    @Test
    fun normalizeImageBaseKeepsBasePath() {
        val normalized = api.normalizeImageBase("https://api.openai.com/v1")
        assertEquals("https://api.openai.com/v1", normalized)
    }

    @Test
    fun streamChatCompletionEmitsIncrementalSseChunksFromChunkedResponse() = runBlocking {
        var requestBody = ""
        val server = RawHttpServer { socket ->
            requestBody = readRequest(socket)
            val output = socket.getOutputStream()
            output.write(
                "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                    .toByteArray(StandardCharsets.US_ASCII),
            )
            output.flush()
            writeChunk(output, "data: {\"choices\":[{\"delta\":{\"content\":\"Hel")
            writeChunk(output, "lo\"}}]}\n\n")
            writeChunk(output, "data: {\"choices\":[{\"delta\":{\"content\":\" world\"}}]}\n\n")
            writeChunk(output, "data: [DONE]\n\n")
            writeChunk(output, "")
        }
        server.start()
        try {
            val chunks = api.streamChatCompletion(
                apiKey = "test-key",
                baseUrl = server.baseUrl,
                request = testRequest(),
            ).toList()

            assertEquals(listOf("Hello", " world"), chunks)
            assertTrue(requestBody.contains("\"stream\":true"))
            server.awaitFinished()
        } finally {
            server.close()
        }
    }

    @Test
    fun streamUsageReadsOpenAiFinalUsageOnlyEventAndRequestsItExplicitly() = runBlocking {
        var requestBody = ""
        val server = RawHttpServer { socket ->
            requestBody = readRequest(socket)
            val output = socket.getOutputStream()
            output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
            output.write("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n".toByteArray())
            output.write("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7,\"prompt_tokens_details\":{\"cached_tokens\":3}}}\n\n".toByteArray())
            output.write("data: [DONE]\n\n".toByteArray())
            output.flush()
        }
        server.start()
        try {
            var usage: com.mojing.app.data.remote.TokenUsage? = null
            val chunks = api.streamChatCompletionWithUsage("test-key", server.baseUrl, testRequest()) {
                usage = it
            }.toList()
            assertEquals(listOf("ok"), chunks)
            assertEquals(com.mojing.app.data.remote.TokenUsage(11, 7, 3), usage)
            assertTrue(requestBody.contains("\"include_usage\":true"))
            server.awaitFinished()
        } finally { server.close() }
    }

    @Test
    fun streamUsageIsNullWhenProviderOmitsUsage() = runBlocking {
        val server = RawHttpServer { socket ->
            readRequest(socket)
            val output = socket.getOutputStream()
            output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
            output.write("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n".toByteArray())
            output.write("data: [DONE]\n\n".toByteArray())
            output.flush()
        }
        server.start()
        try {
            var usage: com.mojing.app.data.remote.TokenUsage? = com.mojing.app.data.remote.TokenUsage(1, 1)
            api.streamChatCompletionWithUsage("test-key", server.baseUrl, testRequest()) { usage = it }.collect { }
            assertNull(usage)
        } finally { server.close() }
    }

    @Test
    fun ordinaryChatKeepsReceivedChunksButRejectsEarlyEof() = runBlocking {
        val server = RawHttpServer { socket ->
            readRequest(socket)
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                write("data: {\"choices\":[{\"delta\":{\"content\":\"半截正文\"}}]}\n\n".toByteArray())
                flush()
            }
        }
        server.start()
        try {
            val chunks = mutableListOf<String>()
            val error = runCatching {
                api.streamChatCompletion("test-key", server.baseUrl, testRequest()).collect { chunks += it }
            }.exceptionOrNull()
            assertTrue(error is IOException)
            assertEquals(listOf("半截正文"), chunks)
        } finally { server.close() }
    }

    @Test
    fun ordinaryChatAcceptsFinishStopWithoutDoneMarker() = runBlocking {
        val server = RawHttpServer { socket ->
            readRequest(socket)
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                write("data: {\"choices\":[{\"delta\":{\"content\":\"完整正文\"},\"finish_reason\":\"stop\"}]}\n\n".toByteArray())
                flush()
            }
        }
        server.start()
        try {
            assertEquals(listOf("完整正文"), api.streamChatCompletion("test-key", server.baseUrl, testRequest()).toList())
        } finally { server.close() }
    }

    @Test
    fun ordinaryChatReportsOutputLimitInsteadOfSavingTruncatedReply() = runBlocking {
        val server = RawHttpServer { socket ->
            readRequest(socket)
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                write("data: {\"choices\":[{\"delta\":{\"content\":\"达到上限\"},\"finish_reason\":\"length\"}]}\n\n".toByteArray())
                flush()
            }
        }
        server.start()
        try {
            val chunks = mutableListOf<String>()
            val error = runCatching {
                api.streamChatCompletion("test-key", server.baseUrl, testRequest()).collect { chunks += it }
            }.exceptionOrNull()
            assertTrue(error is LlmProtocolException)
            assertEquals("output_limit", (error as LlmProtocolException).reason)
            assertEquals(listOf("达到上限"), chunks)
        } finally { server.close() }
    }

    @Test
    fun strictStreamConsumesUsageAfterStopBeforeDone() = runBlocking {
        val server = RawHttpServer { socket ->
            readRequest(socket)
            val output = socket.getOutputStream()
            output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
            output.write("data: {\"choices\":[{\"delta\":{\"content\":\"正文\"},\"finish_reason\":\"stop\"}]}\n\n".toByteArray())
            output.write("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2}}\n\n".toByteArray())
            output.write("data: [DONE]\n\n".toByteArray())
            output.flush()
        }
        server.start()
        try {
            var usage: com.mojing.app.data.remote.TokenUsage? = null
            val result = api.streamStoryCompletionWithUsage("test-key", server.baseUrl, testRequest()) {
                usage = it
            }.toList().joinToString("")
            assertEquals("正文", result)
            assertEquals(com.mojing.app.data.remote.TokenUsage(4, 2), usage)
        } finally { server.close() }
    }

    @Test
    fun cancellingStreamClosesUnderlyingHttpCall() = runBlocking {
        val firstChunkSent = CountDownLatch(1)
        val clientClosed = CountDownLatch(1)
        val server = RawHttpServer { socket ->
            readRequest(socket)
            val output = socket.getOutputStream()
            output.write(
                "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n"
                    .toByteArray(StandardCharsets.US_ASCII),
            )
            writeChunk(output, "data: {\"choices\":[{\"delta\":{\"content\":\"first\"}}]}\n\n")
            firstChunkSent.countDown()
            try {
                while (socket.getInputStream().read() != -1) {
                    // Wait until OkHttp observes coroutine cancellation and closes the call.
                }
            } catch (_: IOException) {
                // A reset connection is also evidence that the call was cancelled.
            } finally {
                clientClosed.countDown()
            }
        }
        server.start()
        try {
            val job = launch {
                api.streamChatCompletion(
                    apiKey = "test-key",
                    baseUrl = server.baseUrl,
                    request = testRequest(),
                ).first()
            }

            assertTrue(withContext(Dispatchers.IO) { firstChunkSent.await(5, TimeUnit.SECONDS) })
            job.join()
            assertTrue(withContext(Dispatchers.IO) { clientClosed.await(5, TimeUnit.SECONDS) })
        } finally {
            server.close()
        }
    }

    private fun testRequest() = ChatRequest(
        model = "test-model",
        messages = listOf(ChatMessage("user", "hello")),
        temperature = 0.2f,
        max_tokens = 32,
    )

    private fun readRequest(socket: Socket): String {
        val input = socket.getInputStream()
        val headers = ByteArrayOutputStream()
        var previous = 0
        var current: Int
        while (true) {
            current = input.read()
            if (current == -1) break
            headers.write(current)
            if (previous == '\r'.code && current == '\n'.code) {
                val bytes = headers.toByteArray()
                if (bytes.size >= 4 &&
                    bytes[bytes.size - 3] == '\n'.code.toByte() &&
                    bytes[bytes.size - 4] == '\r'.code.toByte()
                ) {
                    break
                }
            }
            previous = current
        }
        val headerText = headers.toString(StandardCharsets.US_ASCII.name())
        val contentLength = Regex("(?im)^Content-Length: (\\d+)").find(headerText)
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val body = ByteArray(contentLength)
        var offset = 0
        while (offset < body.size) {
            val read = input.read(body, offset, body.size - offset)
            if (read == -1) break
            offset += read
        }
        return String(body, 0, offset, StandardCharsets.UTF_8)
    }

    private fun writeChunk(output: java.io.OutputStream, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        output.write("${bytes.size.toString(16)}\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write(bytes)
        output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.flush()
    }

    private class RawHttpServer(
        private val handler: (Socket) -> Unit,
    ) : AutoCloseable {
        private val serverSocket = ServerSocket(0)
        private val executor = Executors.newSingleThreadExecutor()
        @Volatile
        private var activeSocket: Socket? = null
        @Volatile
        private var failure: Throwable? = null

        val baseUrl: String = "http://127.0.0.1:${serverSocket.localPort}"

        fun start() {
            executor.execute {
                try {
                    serverSocket.accept().use { socket ->
                        activeSocket = socket
                        handler(socket)
                    }
                } catch (t: Throwable) {
                    if (!serverSocket.isClosed) failure = t
                } finally {
                    activeSocket = null
                }
            }
        }

        fun awaitFinished() {
            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            failure?.let { throw AssertionError("Raw HTTP server failed", it) }
        }

        override fun close() {
            activeSocket?.close()
            serverSocket.close()
            executor.shutdownNow()
        }
    }
}
