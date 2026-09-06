package com.mojing.app.ui.common


data class ApiProviderLine(
    val id: String,
    val label: String,
    val baseUrl: String,
    val suggestedModels: List<String> = emptyList(),
    val imageSuggestedModels: List<String> = emptyList(),
    val voiceSuggestedModels: List<String> = emptyList(),
    val supportsChat: Boolean = true,
    val supportsImage: Boolean = false,
    val supportsVoice: Boolean = false,
)

object ApiProviderPresets {

    val LINES: List<ApiProviderLine> = listOf(
        ApiProviderLine("deepseek", "DeepSeek", "https://api.deepseek.com",
            listOf("deepseek-chat", "deepseek-reasoner")),
        ApiProviderLine("openai", "OpenAI", "https://api.openai.com/v1",
            imageSuggestedModels = listOf("dall-e-3"), voiceSuggestedModels = listOf("tts-1"),
            supportsImage = true, supportsVoice = true),
        ApiProviderLine("siliconflow", "硅基流动", "https://api.siliconflow.cn/v1",
            supportsImage = true, supportsVoice = true),
        ApiProviderLine("anthropic", "Anthropic", "https://api.anthropic.com"),
        ApiProviderLine("custom", "自定义", "", supportsImage = true, supportsVoice = true),
    )

    fun linesForChat(): List<ApiProviderLine> =
        LINES.filter { it.supportsChat || it.id == "custom" }

    fun linesForImage(): List<ApiProviderLine> =
        LINES.filter { (it.supportsImage && it.id != "custom") || it.id == "custom" }

    fun linesForVoice(): List<ApiProviderLine> =
        LINES.filter { (it.supportsVoice && it.id != "custom") || it.id == "custom" }

    fun linesFor(hint: ApiVendorModelHint): List<ApiProviderLine> = when (hint) {
        ApiVendorModelHint.CHAT -> linesForChat()
        ApiVendorModelHint.IMAGE -> linesForImage()
        ApiVendorModelHint.VOICE_TTS -> linesForVoice()
    }

    fun labelForBaseUrl(url: String): String {
        val u = url.trim()
        if (u.isEmpty()) return "\u81ea\u5b9a\u4e49"
        return LINES.firstOrNull { it.baseUrl.equals(u, ignoreCase = true) }?.label ?: "\u81ea\u5b9a\u4e49"
    }

    fun isExactSinglePresetBaseUrl(url: String): Boolean {
        val raw = url.trim()
        if (raw.isEmpty() || '\n' in raw || ';' in raw) return false
        return LINES.any { it.baseUrl.equals(raw, ignoreCase = true) }
    }
}
