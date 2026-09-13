package com.mojing.app.domain.billing

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlinx.coroutines.runBlocking

class ModelPricingDiscoveryTest {
    @Test
    fun requestSelectsExactModelAndUsesBearerAuth() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""
            {"data":[
              {"id":"demo-chat","pricing":{"currency":"USD","unit":"per_token","prompt":"0.000001","completion":"0.000002"}},
              {"id":"demo-chat-pro","pricing":{"prompt":"0.000010","completion":"0.000020"}}
            ]}
        """))
        server.start()
        try {
            val result = ModelPricingDiscovery(OkHttpClient()).discover(server.url("/v1").toString(), "secret", "demo-chat")
            assertEquals(1.0, result.getOrThrow().inputPerMillion, 0.000001)
            val request = server.takeRequest()
            assertEquals("Bearer secret", request.getHeader("Authorization"))
            assertEquals("/v1/models", request.path)
        } finally { server.shutdown() }
    }

    @Test
    fun parsesOpenRouterPerTokenUsdIntoPerMillionRates() {
        val model = JsonParser.parseString("""{"pricing":{"prompt":"0.000003","completion":"0.000015"}}""").asJsonObject
        val result = ModelPricingDiscovery.parsePricing(model, "openrouter.ai")
        assertEquals("USD", result.currency)
        assertEquals(3.0, result.inputPerMillion, 0.000001)
        assertEquals(15.0, result.outputPerMillion, 0.000001)
    }

    @Test
    fun parsesExplicitCustomPerMillionCurrency() {
        val model = JsonParser.parseString("""{"pricing":{"currency":"CNY","unit":"per_million_tokens","input_per_million":2.5,"output_per_million":8}}""").asJsonObject
        val result = ModelPricingDiscovery.parsePricing(model, "example.test")
        assertEquals("CNY", result.currency)
        assertEquals(2.5, result.inputPerMillion, 0.0)
    }

    @Test
    fun rejectsAmbiguousProviderPrice() {
        val model = JsonParser.parseString("""{"pricing":{"prompt":"0.000003","completion":"0.000015"}}""").asJsonObject
        assertThrows(PriceUnavailableException::class.java) {
            ModelPricingDiscovery.parsePricing(model, "example.test")
        }
    }

    @Test
    fun requestReportsUnavailablePriceWithoutLeakingResponseBody() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[{"id":"demo","pricing":{"prompt":"1"}}]}"""))
        server.start()
        try {
            val failure = ModelPricingDiscovery(OkHttpClient()).discover(server.url("/v1").toString(), "secret", "demo").exceptionOrNull()
            assertEquals(PriceUnavailableException::class.java, failure?.javaClass)
            check(!failure!!.message.orEmpty().contains("secret"))
        } finally { server.shutdown() }
    }
}
