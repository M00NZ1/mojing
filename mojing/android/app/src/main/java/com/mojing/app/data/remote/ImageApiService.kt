package com.mojing.app.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.mojing.app.domain.config.DmxApiRouting
import com.mojing.app.domain.config.OpenAiCompatibleRouting
import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImageApiService @Inject constructor() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.MINUTES)
        .build()

    private val gson = Gson()

    internal fun normalizeImageBase(baseUrl: String): String =
        OpenAiCompatibleRouting.normalizeBase(baseUrl)

    internal fun isSiliconFlowHost(base: String): Boolean =
        base.contains("siliconflow.cn", ignoreCase = true)

    internal fun buildImageRequestBody(
        baseUrl: String,
        isSiliconFlow: Boolean,
        prompt: String,
        model: String,
        size: String,
        quality: String
    ): String {
        val compact = Json { encodeDefaults = true }
        when (OpenAiCompatibleRouting.classifyImageBody(baseUrl, model, isSiliconFlow)) {
            OpenAiCompatibleRouting.ImageBodyKind.VOLC_SEEDREAM -> {
                val o = buildJsonObject {
                    put("model", model)
                    put("prompt", prompt)
                    put("size", OpenAiCompatibleRouting.mapSeedreamSize(size))
                    put("response_format", "url")
                    put("watermark", false)
                    put("sequential_image_generation", "disabled")
                }
                return compact.encodeToString(JsonElement.serializer(), o)
            }
            OpenAiCompatibleRouting.ImageBodyKind.SILICONFLOW -> {
                val o = buildJsonObject {
                    put("model", model)
                    put("prompt", prompt)
                    put("image_size", size)
                }
                return compact.encodeToString(JsonElement.serializer(), o)
            }
            OpenAiCompatibleRouting.ImageBodyKind.OPENAI_STANDARD -> { /* below */ }
        }
        val isDmx = DmxApiRouting.isDmxHost(baseUrl)
        val isCustomGptImage = model.contains("gpt-image", ignoreCase = true) && !isDmx
        val isDalle3Family =
            model.contains("dall-e-3", ignoreCase = true) || isCustomGptImage
        val isQwen = DmxApiRouting.isQwenImageModel(model)
        val o = buildJsonObject {
            put("model", model)
            put("prompt", prompt)
            put("n", 1)
            put("size", if (isQwen) DmxApiRouting.mapQwenImageSize(size) else size)
            if (isDalle3Family) {
                put("quality", if (isDmx && DmxApiRouting.isGptImageModel(model)) "medium" else quality)
                if (isDmx && DmxApiRouting.isGptImageModel(model)) {
                    put("moderation", "low")
                    put("output_format", "png")
                }
            }
            put("response_format", "b64_json")
        }
        return compact.encodeToString(JsonElement.serializer(), o)
    }

    internal fun parseImageUrlFromResponse(body: String): String? {
        val parsed = try {
            gson.fromJson(body, Map::class.java)
        } catch (_: Exception) {
            return null
        }
        val dataList = (parsed["data"] as? List<*>)
            ?: (parsed["images"] as? List<*>)
            ?: return null
        val first = dataList.firstOrNull() as? Map<*, *> ?: return null
        return (first["url"] as? String) ?: (first["b64_json"] as? String)
    }

    suspend fun generateImage(
        apiKey: String,
        baseUrl: String,
        prompt: String,
        model: String,
        size: String,
        quality: String
    ): String? {
        if (DmxApiRouting.shouldUseResponsesImage(baseUrl, model)) {
            return DmxResponsesApi.generateImageUrl(apiKey, baseUrl, prompt, model, size)
        }
        if (DmxApiRouting.shouldUseGeminiImage(baseUrl, model)) {
            return DmxGeminiApi.generateImageBase64(apiKey, model, prompt, size)
        }
        val url = OpenAiCompatibleRouting.buildImagesGenerationsUrl(baseUrl)
        val isSilicon = isSiliconFlowHost(baseUrl)
        val body = buildImageRequestBody(baseUrl, isSilicon, prompt, model, size, quality)

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", OpenAiCompatibleRouting.bearerAuth(apiKey))
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        return client.executeCancellable(request) { response ->
            if (!response.isSuccessful) throw Exception("\u56fe\u7247\u751f\u6210\u5931\u8d25: ${response.code}")
            val bodyStr = response.body?.string() ?: ""
            parseImageUrlFromResponse(bodyStr)
        }
    }

    suspend fun downloadImage(imageUrl: String): String? {
        return try {
            val request = Request.Builder().url(imageUrl).build()
            client.executeCancellable(
                request = request,
                onCancellation = { path: String? -> path?.let { runCatching { File(it).delete() } } },
            ) { response ->
                if (!response.isSuccessful) return@executeCancellable null
                val bytes = response.body?.bytes() ?: return@executeCancellable null
                val file = File.createTempFile("gen_img_", ".png")
                FileOutputStream(file).use { it.write(bytes) }
                file.absolutePath
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
