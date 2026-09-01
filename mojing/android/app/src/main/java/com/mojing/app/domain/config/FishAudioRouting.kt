package com.mojing.app.domain.config

/**
 * Fish Audio 官方 REST TTS（非 OpenAI `/v1/audio/speech`）。
 * 文档：https://docs.fish.audio/api-reference/endpoint/openapi-v1/text-to-speech
 */
object FishAudioRouting {

    const val OFFICIAL_API_ROOT = "https://api.fish.audio"
    const val OFFICIAL_TTS_URL = "https://api.fish.audio/v1/tts"
    const val DEFAULT_MODEL = "s2-pro"

    private val FISH_MODEL_IDS = setOf("s1", "s2-pro")
    private val OPENAI_VOICES = setOf(
        "alloy", "echo", "fable", "onyx", "nova", "shimmer",
        "alloy-hd", "echo-hd",
    )

    /** 根地址为 fish.audio，或模型名为 s1 / s2-pro（避免误走 OpenAI speech 或 DMX）。 */
    fun shouldUseFishDirectApi(baseUrl: String, model: String): Boolean =
        OpenAiCompatibleRouting.isFishAudioHost(baseUrl) || isFishTtsModel(model)

    fun isFishTtsModel(model: String): Boolean {
        val m = model.trim().lowercase()
        if (m.isEmpty()) return false
        if (m in FISH_MODEL_IDS) return true
        if (m.startsWith("s2")) return true
        if (m == "s1" || m.contains("fish-speech") || m.contains("fishaudio")) return true
        return false
    }

    /** OpenAPI 要求 Header `model` 为 s1 或 s2-pro。 */
    fun resolveTtsModelHeader(model: String): String {
        val m = model.trim().lowercase()
        return when {
            m == "s1" -> "s1"
            m == "s2-pro" || m == "s2" || m == "s2pro" -> "s2-pro"
            m.startsWith("s2") -> "s2-pro"
            else -> DEFAULT_MODEL
        }
    }

    /**
     * 音色模型 ID（reference_id），来自 fish.audio 控制台或 voices API。
     * 勿把 OpenAI 的 alloy/echo 或硅基 CosyVoice 的 `model:voice` 格式填入。
     */
    fun resolveReferenceId(rawVoice: String): String? {
        val v = rawVoice.trim()
        if (v.isEmpty()) return null
        if (v.lowercase() in OPENAI_VOICES) return null
        if (v.contains(":") || v.contains("/")) return null
        if (v.matches(Regex("^[a-f0-9]{32}$", RegexOption.IGNORE_CASE))) return v.lowercase()
        if (v.length in 20..64 && v.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return v
        return null
    }

    fun resolveTtsUrl(baseUrl: String): String {
        return OFFICIAL_TTS_URL
    }
}
