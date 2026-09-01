package com.mojing.app.domain.config

/**
 * OpenAI 兼容网关统一路由：归一化根地址、拼 chat/images/speech 真实 path、识别生图 body 方言。
 *
 * 解决「根地址已含 /api/v3、/v2、/v4 等时误拼 /v1/...」及 Seedream 等非 OpenAI 生图参数。
 */
object OpenAiCompatibleRouting {

    const val VOLC_ARK_BASE = "https://ark.cn-beijing.volces.com/api/v3"

    /** 本机朗读占位根（非 HTTP）。 */
    const val LOCAL_TTS_BASE = "local://mojing-tts"

    const val FISH_AUDIO_BASE = "https://api.fish.audio/v1"

    const val ANTHROPIC_BASE = "https://api.anthropic.com"
    const val ANTHROPIC_VERSION = "2023-06-01"

    /** 火山方舟对话建议模型（用户仍可在 UI 手填 ep- 接入点） */
    val VOLC_CHAT_SUGGESTED_MODELS = listOf(
        "doubao-seed-1-6-flash-250715",
        "doubao-1-5-lite-32k-250115",
    )

    /** 火山 Seedream 生图建议（含 4.5 / 5.0-lite） */
    val VOLC_IMAGE_SUGGESTED_MODELS = listOf(
        "doubao-seedream-5.0-lite",
        "doubao-seedream-4.5",
        "doubao-seedream-4-5-251128",
        "doubao-seedream-4-0-250828",
    )

    enum class ImageBodyKind {
        OPENAI_STANDARD,
        SILICONFLOW,
        VOLC_SEEDREAM,
    }

    private val STRIP_RESOURCE_SUFFIXES = listOf(
        "/v1/chat/completions", "/chat/completions",
        "/v1/images/generations", "/images/generations",
        "/v1/audio/speech", "/audio/speech",
        "/v1/models", "/models",
        "/v1/responses", "/responses",
    )

    /** 根地址已带版本段，资源 path 直接跟在后面，不再插入 `/v1`。 */
    private val VERSIONED_ROOT_SUFFIXES = listOf(
        "/compatible-mode/v1",
        "/v1beta/openai",
        "/api/paas/v4",
        "/api/v3",
        "/v1", "/v2", "/v3", "/v4",
    )

    private val PRESET_CANONICAL_BY_HOST: List<Pair<String, String>> = listOf(
        "volces.com" to VOLC_ARK_BASE,
        "ark.cn-beijing" to VOLC_ARK_BASE,
    )

    fun isVolcArkHost(url: String): Boolean {
        val low = url.lowercase()
        return "volces.com" in low || "volcengineapi.com" in low
    }

    fun isSeedreamModel(model: String): Boolean {
        val m = model.trim().lowercase()
        return "seedream" in m || m.startsWith("doubao-seedream")
    }

    fun normalizeBase(baseUrl: String): String {
        var b = baseUrl.trim().trimEnd('/')
        if (b.isEmpty()) return b
        while (true) {
            val low = b.lowercase()
            val hit = STRIP_RESOURCE_SUFFIXES.find { low.endsWith(it) } ?: break
            b = b.dropLast(hit.length).trimEnd('/')
        }
        if (isVolcArkHost(b) && !b.lowercase().endsWith("/api/v3")) {
            if (b.lowercase().contains("ark.")) return VOLC_ARK_BASE
        }
        return b
    }

    /** 根地址是否已含 API 版本段（含 /v1、/api/v3、/v2 等）。 */
    fun usesVersionedRoot(baseUrl: String): Boolean {
        val low = normalizeBase(baseUrl).lowercase()
        return VERSIONED_ROOT_SUFFIXES.any { low.endsWith(it) }
    }

    fun buildChatCompletionsUrl(baseUrl: String): String =
        joinResource(baseUrl, "chat/completions")

    fun buildImagesGenerationsUrl(baseUrl: String): String =
        joinResource(baseUrl, "images/generations")

    fun buildAudioSpeechUrl(baseUrl: String): String =
        joinResource(baseUrl, "audio/speech")

    fun isFishAudioHost(url: String): Boolean =
        "fish.audio" in url.lowercase()

    fun isAnthropicHost(url: String): Boolean =
        "anthropic.com" in url.lowercase()

    /** 统一 Bearer 鉴权：不假定 Key 必须以 sk- 开头（火山/Fish 等常为 UUID 或数字字母混排）。 */
    fun bearerAuth(apiKey: String): String {
        val k = apiKey.trim().removePrefix("Bearer ").trim()
        return if (k.isEmpty()) "" else "Bearer $k"
    }

    fun isLocalBuiltinTts(baseUrl: String): Boolean {
        val b = baseUrl.trim().lowercase()
        return b == LOCAL_TTS_BASE.lowercase() || b.startsWith("local://")
    }

    fun buildFishTtsUrl(baseUrl: String): String {
        val base = normalizeBase(baseUrl).trimEnd('/')
        val low = base.lowercase()
        return if (low.endsWith("/tts")) base else "$base/tts"
    }

    private fun joinResource(baseUrl: String, resourcePath: String): String {
        val base = normalizeBase(baseUrl).trimEnd('/')
        return if (usesVersionedRoot(base)) "$base/$resourcePath" else "$base/v1/$resourcePath"
    }

    fun classifyImageBody(baseUrl: String, model: String, isSiliconFlow: Boolean): ImageBodyKind =
        when {
            DmxApiRouting.shouldUseDmxSpecialImage(baseUrl, model) -> ImageBodyKind.OPENAI_STANDARD
            isVolcArkHost(baseUrl) || isSeedreamModel(model) -> ImageBodyKind.VOLC_SEEDREAM
            isSiliconFlow -> ImageBodyKind.SILICONFLOW
            else -> ImageBodyKind.OPENAI_STANDARD
        }

    fun mapSeedreamSize(size: String): String {
        val s = size.trim()
        val low = s.lowercase()
        if (low in setOf("1k", "2k", "3k", "4k")) return s.uppercase()
        return when {
            "4096" in low -> "4K"
            "3072" in low || "3k" in low -> "3K"
            "2048" in low || s == "1024x1024" || s == "1280x1280" || s == "256x256" -> "2K"
            "512" in low -> "1K"
            else -> "2K"
        }
    }

    /**
     * 探测候选根：用户输入优先；已版本化根（如 /api/v3）不再盲目追加 `/v1`。
     */
    fun collectProbeBases(userInput: String, normalize: (String) -> String): List<String> {
        val trimmed = userInput.trim().trimEnd('/')
        if (trimmed.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        fun add(raw: String) {
            val n = normalizeBase(raw).ifBlank { normalize(raw).trim().trimEnd('/') }
            if (n.isNotEmpty()) out.add(n)
        }
        add(trimmed)
        for ((hostFrag, canonical) in PRESET_CANONICAL_BY_HOST) {
            if (hostFrag in trimmed.lowercase()) add(canonical)
        }
        val once = normalizeBase(trimmed).ifBlank { normalize(trimmed).trim().trimEnd('/') }
        if (once.isNotEmpty()) {
            when {
                usesVersionedRoot(once) && !once.lowercase().endsWith("/v1") -> { /* 如 /api/v3：勿拼 /v1 */ }
                once.lowercase().endsWith("/v1") -> out.add(once.dropLast(3).trimEnd('/'))
                else -> out.add("$once/v1")
            }
        }
        return out.toList()
    }
}
