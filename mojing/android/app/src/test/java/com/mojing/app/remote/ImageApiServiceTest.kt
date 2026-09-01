package com.mojing.app.remote

import com.mojing.app.data.remote.DmxGeminiApi
import com.mojing.app.data.remote.DmxResponsesApi
import com.mojing.app.data.remote.ImageApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class ImageApiServiceTest {

    private val api = ImageApiService()

    @Test
    fun parseOpenAiStyleDataUrl() {
        val url = api.parseImageUrlFromResponse("""{"data":[{"url":"https://example.com/a.png"}]}""")
        assertEquals("https://example.com/a.png", url)
    }

    @Test
    fun parseOpenAiStyleB64Json() {
        val b64 = api.parseImageUrlFromResponse("""{"data":[{"b64_json":"iVBORw0KGgo..."}]}""")
        assertEquals("iVBORw0KGgo...", b64)
    }

    @Test
    fun parseSiliconFlowImagesUrl() {
        val url = api.parseImageUrlFromResponse("""{"images":[{"url":"https://sf.example/img.png"}]}""")
        assertEquals("https://sf.example/img.png", url)
    }

    @Test
    fun parseSiliconFlowImagesB64() {
        val b64 = api.parseImageUrlFromResponse("""{"images":[{"b64_json":"abc123"}]}""")
        assertEquals("abc123", b64)
    }

    @Test
    fun dataArrayTakesPriorityOverImages() {
        val url = api.parseImageUrlFromResponse(
            """{"data":[{"url":"https://data-url.png"}],"images":[{"url":"https://images-url.png"}]}"""
        )
        assertEquals("https://data-url.png", url)
    }

    @Test
    fun siliconFlowKolorsBodyUsesImageSize() {
        val body = api.buildImageRequestBody(
            baseUrl = "https://api.siliconflow.cn/v1",
            isSiliconFlow = true,
            prompt = "hello",
            model = "Kwai-Kolors/Kolors",
            size = "1024x1024",
            quality = "standard"
        )
        assertTrue(body.contains("\"image_size\":\"1024x1024\""))
        assertTrue(body.contains("\"model\":\"Kwai-Kolors/Kolors\""))
    }

    @Test
    fun siliconFlowFluxBodyUsesImageSize() {
        val body = api.buildImageRequestBody(
            baseUrl = "https://api.siliconflow.cn/v1",
            isSiliconFlow = true,
            prompt = "test",
            model = "black-forest-labs/FLUX.2-pro",
            size = "768x1024",
            quality = "standard"
        )
        assertTrue(body.contains("\"image_size\":\"768x1024\""))
    }

    @Test
    fun openAiBodyUsesB64Json() {
        val body = api.buildImageRequestBody(
            baseUrl = "https://api.openai.com/v1",
            isSiliconFlow = false,
            prompt = "x",
            model = "dall-e-3",
            size = "1024x1024",
            quality = "standard"
        )
        assertTrue(body.contains("\"size\":\"1024x1024\""))
        assertTrue(body.contains("\"response_format\":\"b64_json\""))
    }

    @Test
    fun openAiDalle2OmitsQuality() {
        val body = api.buildImageRequestBody(
            baseUrl = "https://api.openai.com/v1",
            isSiliconFlow = false,
            prompt = "x",
            model = "dall-e-2",
            size = "1024x1024",
            quality = "standard"
        )
        assertTrue(body.contains("\"model\":\"dall-e-2\""))
        assertTrue(!body.contains("\"quality\""))
    }

    @Test
    fun normalizeImageBaseStripsGenerationsSuffix() {
        val n = api.normalizeImageBase("https://api.siliconflow.cn/v1/images/generations")
        assertEquals("https://api.siliconflow.cn", n)
    }

    @Test
    fun isSiliconFlowHostDetected() {
        assertTrue(api.isSiliconFlowHost("https://api.siliconflow.cn/v1"))
        assertFalse(api.isSiliconFlowHost("https://api.openai.com/v1"))
    }

    @Test
    fun dmxResponsesImageModelsUseResponsesApi() {
        mockkObject(DmxResponsesApi)
        try {
            coEvery { DmxResponsesApi.generateImageUrl(any(), any(), any(), any(), any()) } returns "responses-image"

            val result = runBlocking {
                api.generateImage(
                    apiKey = "test",
                    baseUrl = "https://www.dmxapi.cn/v1",
                    prompt = "test",
                    model = "wan2.7-image-pro",
                    size = "1024x1024",
                    quality = "standard",
                )
            }

            assertEquals("responses-image", result)
            coVerify(exactly = 1) {
                DmxResponsesApi.generateImageUrl(
                    "test",
                    "https://www.dmxapi.cn/v1",
                    "test",
                    "wan2.7-image-pro",
                    "1024x1024",
                )
            }
        } finally {
            unmockkObject(DmxResponsesApi)
        }
    }

    @Test
    fun dmxGeminiImageModelsUseGeminiApi() {
        mockkObject(DmxGeminiApi)
        try {
            coEvery { DmxGeminiApi.generateImageBase64(any(), any(), any(), any()) } returns "gemini-image"

            val result = runBlocking {
                api.generateImage(
                    apiKey = "test",
                    baseUrl = "https://www.dmxapi.cn/v1",
                    prompt = "test",
                    model = "gemini-2.5-flash-image",
                    size = "1024x1024",
                    quality = "standard",
                )
            }

            assertEquals("gemini-image", result)
            coVerify(exactly = 1) {
                DmxGeminiApi.generateImageBase64(
                    "test",
                    "gemini-2.5-flash-image",
                    "test",
                    "1024x1024",
                )
            }
        } finally {
            unmockkObject(DmxGeminiApi)
        }
    }

    @Test
    fun cancellingAfterHeadersClosesBlockedResponseBody() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"data":[{"b64_json":"x"}]}""")
                .setBodyDelay(5, TimeUnit.SECONDS)
        )
        server.start()

        try {
            runBlocking {
                val job = launch(Dispatchers.Default) {
                    api.generateImage(
                        apiKey = "test",
                        baseUrl = server.url("/").toString(),
                        prompt = "test",
                        model = "test",
                        size = "1024x1024",
                        quality = "standard",
                    )
                }
                assertNotNull("server did not receive request", server.takeRequest(2, TimeUnit.SECONDS))
                delay(100)
                withTimeout(1_000L) {
                    job.cancelAndJoin()
                }
            }
        } finally {
            server.shutdown()
        }
    }
}
