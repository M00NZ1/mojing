package com.mojing.app.domain.config

// DMXAPI 全量文生图 / TTS 路由：doc.dmxapi.cn
object DmxApiRouting {

    const val BASE = "https://www.dmxapi.cn/v1"
    const val GEMINI_HOST = "https://www.dmxapi.cn"

    enum class ImageProtocol {
        OPENAI_IMAGES,
        RESPONSES_SEEDREAM,
        RESPONSES_WAN,
        GEMINI_GENERATE,
    }

    enum class TtsProtocol {
        OPENAI_SPEECH,
        RESPONSES_MINIMAX28,
        CHAT_MIMO,
        GEMINI_GENERATE,
    }

    val CHAT_SUGGESTED_MODELS = listOf(
        "deepseek-chat",
        "gpt-4o-mini",
        "doubao-seed-1-6-flash-250715",
    )

    val IMAGE_OPENAI_SUGGESTED_MODELS = listOf(
        "gpt-image-2-ssvip",
        "gpt-image-1.5",
        "qwen-image",
        "qwen-image-2.0",
        "qwen-image-2.0-pro",
        "qwen-image-max",
        "flux-kontext-pro",
    )

    val IMAGE_RESPONSES_SUGGESTED_MODELS = listOf(
        "doubao-seedream-5.0-lite",
        "doubao-seedream-4.5",
        "doubao-seedream-4-5-251128",
        "doubao-seedream-4-0-250828",
        "wan2.6-t2i",
        "wan2.7-image",
        "wan2.7-image-pro",
    )

    val IMAGE_GEMINI_SUGGESTED_MODELS = listOf(
        "gemini-3.1-flash-image-preview",
        "gemini-3-pro-image-preview",
        "gemini-2.5-flash-image",
    )

    val IMAGE_SUGGESTED_MODELS: List<String> =
        IMAGE_RESPONSES_SUGGESTED_MODELS +
            IMAGE_GEMINI_SUGGESTED_MODELS +
            IMAGE_OPENAI_SUGGESTED_MODELS

    val VOICE_OPENAI_SUGGESTED_MODELS = listOf(
        "gpt-4o-mini-tts",
        "tts-1-hd",
        "tts-1",
        "speech-2.6-hd",
        "speech-2.6-turbo",
    )

    val VOICE_RESPONSES_SUGGESTED_MODELS = listOf(
        "speech-2.8-hd",
    )

    val VOICE_CHAT_SUGGESTED_MODELS = listOf(
        "mimo-v2-tts",
    )

    val VOICE_GEMINI_SUGGESTED_MODELS = listOf(
        "gemini-2.5-pro-preview-tts",
        "gemini-2.5-flash-preview-tts",
    )

    val VOICE_SUGGESTED_MODELS: List<String> =
        VOICE_OPENAI_SUGGESTED_MODELS +
            VOICE_RESPONSES_SUGGESTED_MODELS +
            VOICE_CHAT_SUGGESTED_MODELS +
            VOICE_GEMINI_SUGGESTED_MODELS

    const val DEFAULT_MINIMAX_VOICE = "male-qn-qingse"
    const val DEFAULT_OPENAI_TTS_VOICE = "alloy"
    const val DEFAULT_MIMO_VOICE = "mimo_default"
    const val DEFAULT_GEMINI_TTS_VOICE = "Kore"

    fun isDmxHost(url: String): Boolean =
        "dmxapi.cn" in url.lowercase()

    fun classifyImageProtocol(model: String): ImageProtocol {
        val m = model.trim().lowercase()
        if (isGeminiImageModel(m)) return ImageProtocol.GEMINI_GENERATE
        if (isWanImageModel(m)) return ImageProtocol.RESPONSES_WAN
        if (OpenAiCompatibleRouting.isSeedreamModel(m)) return ImageProtocol.RESPONSES_SEEDREAM
        return ImageProtocol.OPENAI_IMAGES
    }

    fun classifyTtsProtocol(model: String): TtsProtocol {
        val m = model.trim().lowercase()
        when {
            isMinimaxSpeech28(m) -> return TtsProtocol.RESPONSES_MINIMAX28
            isMimoTtsModel(m) -> return TtsProtocol.CHAT_MIMO
            isGeminiTtsModel(m) -> return TtsProtocol.GEMINI_GENERATE
        }
        return TtsProtocol.OPENAI_SPEECH
    }

    fun shouldUseDmxSpecialImage(baseUrl: String, model: String): Boolean =
        isDmxHost(baseUrl) && classifyImageProtocol(model) != ImageProtocol.OPENAI_IMAGES

    fun shouldUseResponsesImage(baseUrl: String, model: String): Boolean =
        isDmxHost(baseUrl) && classifyImageProtocol(model) in setOf(
            ImageProtocol.RESPONSES_SEEDREAM,
            ImageProtocol.RESPONSES_WAN,
        )

    fun shouldUseGeminiImage(baseUrl: String, model: String): Boolean =
        isDmxHost(baseUrl) && classifyImageProtocol(model) == ImageProtocol.GEMINI_GENERATE

    fun isMinimaxSpeech26(model: String): Boolean =
        model.trim().lowercase().startsWith("speech-2.6")

    fun isMinimaxSpeech28(model: String): Boolean =
        model.trim().lowercase().startsWith("speech-2.8")

    fun shouldUseResponsesTts(baseUrl: String, model: String): Boolean =
        isDmxHost(baseUrl) && classifyTtsProtocol(model) == TtsProtocol.RESPONSES_MINIMAX28

    fun shouldUseChatTts(baseUrl: String, model: String): Boolean =
        isDmxHost(baseUrl) && classifyTtsProtocol(model) == TtsProtocol.CHAT_MIMO

    fun shouldUseGeminiTts(baseUrl: String, model: String): Boolean =
        isDmxHost(baseUrl) && classifyTtsProtocol(model) == TtsProtocol.GEMINI_GENERATE

    fun isQwenImageModel(model: String): Boolean {
        val m = model.trim().lowercase()
        return m.startsWith("qwen-image") || m == "qwen-image"
    }

    fun isGptImageModel(model: String): Boolean {
        val m = model.trim().lowercase()
        return m.startsWith("gpt-image") || m.contains("dall-e")
    }

    fun buildResponsesUrl(baseUrl: String): String {
        val base = OpenAiCompatibleRouting.normalizeBase(baseUrl).trimEnd('/')
        val low = base.lowercase()
        return when {
            low.endsWith("/responses") -> base
            low.endsWith("/api/v3") -> "$base/responses"
            low.endsWith("/v1") -> "$base/responses"
            isDmxHost(base) -> BASE.trimEnd('/') + "/responses"
            else -> "$base/v1/responses"
        }
    }

    fun dmxOpenAiBase(baseUrl: String): String {
        val b = OpenAiCompatibleRouting.normalizeBase(baseUrl).trimEnd('/')
        return if (b.lowercase().endsWith("/v1")) b else BASE
    }

    fun buildGeminiGenerateContentUrl(model: String): String {
        val m = model.trim()
        return "$GEMINI_HOST/v1beta/models/$m:generateContent"
    }

    fun mapQwenImageSize(size: String): String =
        size.trim().replace('x', '*').replace('X', '*').ifBlank { "1328*1328" }

    fun mapWanImageSize(model: String, size: String): String {
        val m = model.trim().lowercase()
        val s = size.trim()
        if (m.startsWith("wan2.7")) {
            val low = s.lowercase()
            if (low in setOf("1k", "2k", "4k")) return s.uppercase()
            if (s.contains('*')) return s
            return when {
                "4096" in low -> "4K"
                "2048" in low || s == "1024x1024" -> "2K"
                else -> "2K"
            }
        }
        return s.replace('x', '*').replace('X', '*').ifBlank { "1280*1280" }
    }

    fun mapGeminiAspectRatio(size: String): String {
        val s = size.trim().lowercase().replace('*', 'x')
        return when {
            "1536x1024" in s || "16:9" in s -> "16:9"
            "1024x1536" in s || "9:16" in s -> "9:16"
            "1536x672" in s || "21:9" in s -> "21:9"
            "848x1264" in s || "2:3" in s -> "2:3"
            "1264x848" in s || "3:2" in s -> "3:2"
            "896x1200" in s || "3:4" in s -> "3:4"
            "1200x896" in s || "4:3" in s -> "4:3"
            else -> "1:1"
        }
    }

    fun mapGeminiImageSize(model: String, size: String): String? {
        val m = model.trim().lowercase()
        if (m.contains("2.5-flash-image")) return null
        val low = size.trim().lowercase()
        return when {
            "4096" in low || low == "4k" -> "4K"
            "2048" in low || low == "2k" -> "2K"
            else -> "1K"
        }
    }

    fun formatBearerAuth(apiKey: String): String {
        val k = apiKey.trim().removePrefix("Bearer ").trim()
        return "Bearer $k"
    }

    fun formatTokenAuth(apiKey: String): String =
        apiKey.trim().removePrefix("Bearer ").trim()

    /** @deprecated 按场景用 [formatBearerAuth] 或 [formatTokenAuth] */
    fun formatAuthHeader(apiKey: String, baseUrl: String = BASE): String =
        formatTokenAuth(apiKey)

    private fun isGeminiImageModel(m: String): Boolean =
        m.contains("gemini") && (m.contains("image") || m.contains("flash-image"))

    private fun isGeminiTtsModel(m: String): Boolean =
        m.contains("gemini") && m.contains("tts")

    private fun isMimoTtsModel(m: String): Boolean =
        m.startsWith("mimo-v2-tts") || m.startsWith("mimo-v2")

    private fun isWanImageModel(m: String): Boolean =
        m.startsWith("wan2.") && (m.contains("image") || m.contains("-t2i") || m.endsWith("-t2i"))
}
