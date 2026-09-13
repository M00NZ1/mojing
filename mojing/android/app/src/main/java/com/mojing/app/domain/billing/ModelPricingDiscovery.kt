package com.mojing.app.domain.billing

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojing.app.domain.config.OpenAiCompatibleRouting
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.TimeUnit

class PriceUnavailableException(message: String = "平台接口未提供价格，请填写单价") : Exception(message)

/** Reads explicit pricing metadata from the provider's existing `/models` endpoint. */
class ModelPricingDiscovery(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) {
    suspend fun discover(baseUrl: String, apiKey: String, model: String): Result<ModelPricing> = try {
        Result.success(withTimeout(35_000) {
          withContext(Dispatchers.IO) {
            val base = OpenAiCompatibleRouting.normalizeBase(baseUrl)
            val url = (OpenAiCompatibleRouting.buildChatCompletionsUrl(base)
                .removeSuffix("/chat/completions") + "/models").toHttpUrl()
            val request = Request.Builder().url(url).apply {
                if (url.host == "api.anthropic.com") {
                    header("x-api-key", apiKey.trim())
                    header("anthropic-version", OpenAiCompatibleRouting.ANTHROPIC_VERSION)
                } else header("Authorization", OpenAiCompatibleRouting.bearerAuth(apiKey.trim()))
            }.get().build()
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                val root = JsonParser.parseString(response.body?.string().orEmpty()).asJsonObject
                val item = root.getAsJsonArray("data")?.firstOrNull { value ->
                    value.isJsonObject && value.asJsonObject.get("id")?.asString == model
                }?.asJsonObject ?: throw PriceUnavailableException()
                parsePricing(item, url.host)
            }
          }
        })
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { Result.failure(error) }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(error)
            }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, result, _ -> result.close() }
            }
        })
    }

    companion object {
        fun parsePricing(model: JsonObject, host: String): ModelPricing {
            val pricing = model.getAsJsonObject("pricing") ?: throw PriceUnavailableException()
            val explicitCurrency = pricing.get("currency")?.takeIf { it.isJsonPrimitive }?.asString?.uppercase()
                ?: model.get("currency")?.takeIf { it.isJsonPrimitive }?.asString?.uppercase()
            val unit = pricing.get("unit")?.takeIf { it.isJsonPrimitive }?.asString?.lowercase()
                ?: model.get("unit")?.takeIf { it.isJsonPrimitive }?.asString?.lowercase()

            // Custom providers can expose an explicit per-million schema.
            val inputMillion = number(pricing, "input_per_million")
                ?: number(pricing, "inputPerMillion")
            val outputMillion = number(pricing, "output_per_million")
                ?: number(pricing, "outputPerMillion")
            if (inputMillion != null && outputMillion != null && explicitCurrency != null &&
                explicitCurrency in setOf("USD", "CNY") &&
                (unit == null || unit in setOf("per_million", "per_million_tokens", "million_tokens"))) {
                return ModelPricing(explicitCurrency, inputMillion, outputMillion,
                    number(pricing, "cached_input_per_million") ?: number(pricing, "cachedInputPerMillion"),
                    source = "provider")
            }

            // OpenRouter documents prompt/completion as USD per token. A generic
            // endpoint must state both the currency and unit before this is accepted.
            val prompt = number(pricing, "prompt")
            val completion = number(pricing, "completion")
            val openRouter = host.equals("openrouter.ai", ignoreCase = true) || host.endsWith(".openrouter.ai", ignoreCase = true)
            if (prompt != null && completion != null &&
                (explicitCurrency in setOf("USD", "CNY") && unit in setOf("token", "per_token") || openRouter && explicitCurrency == null && unit == null)) {
                return ModelPricing(explicitCurrency ?: "USD", prompt * 1_000_000, completion * 1_000_000,
                    source = "provider")
            }
            throw PriceUnavailableException()
        }

        private fun number(root: JsonObject, key: String): Double? = root.get(key)
            ?.takeIf { it.isJsonPrimitive }
            ?.let { runCatching { it.asString.toDouble() }.getOrNull() }
            ?.takeIf { it.isFinite() && it >= 0.0 }
    }
}
