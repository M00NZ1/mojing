package com.mojing.app.ui.common

import com.mojing.app.domain.config.DmxApiRouting
import com.mojing.app.domain.config.OpenAiCompatibleRouting

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
        ApiProviderLine(
            "deepseek",
            "DeepSeek",
            "https://api.deepseek.com",
            listOf("deepseek-chat", "deepseek-reasoner"),
        ),
        ApiProviderLine(
            "zhipu",
            "\u667a\u8c31 GLM",
            "https://open.bigmodel.cn/api/paas/v4/",
            listOf("glm-4-flash", "glm-4-plus"),
        ),
        ApiProviderLine(
            "alibaba",
            "\u963f\u91cc\u4e91 DashScope\uff08\u901a\u4e49\uff09",
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            listOf("qwen-turbo", "qwen-plus"),
        ),
        ApiProviderLine(
            "moonshot",
            "\u6708\u4e4b\u6697\u9762 Kimi",
            "https://api.moonshot.cn/v1",
            listOf("moonshot-v1-8k", "moonshot-v1-32k"),
        ),
        ApiProviderLine(
            "tencent",
            "\u817e\u8baf\u6df7\u5143",
            "https://api.hunyuan.cloud.tencent.com/v1",
            listOf("hunyuan-turbos-latest"),
        ),
        ApiProviderLine(
            "siliconflow",
            "\u7845\u57fa\u6d41\u52a8 (SiliconFlow)",
            "https://api.siliconflow.cn/v1",
            listOf("deepseek-ai/DeepSeek-V3", "Qwen/Qwen2.5-7B-Instruct"),
            imageSuggestedModels = listOf(
                "black-forest-labs/FLUX.2-pro",
                "Kwai-Kolors/Kolors",
                "Qwen/Qwen-Image",
            ),
            voiceSuggestedModels = listOf("FunAudioLLM/CosyVoice2-0.5B"),
            supportsImage = true,
            supportsVoice = true,
        ),
        ApiProviderLine(
            "volcengine",
            "\u706b\u5c71\u5f15\u64ce (Doubao)",
            OpenAiCompatibleRouting.VOLC_ARK_BASE,
            OpenAiCompatibleRouting.VOLC_CHAT_SUGGESTED_MODELS,
            imageSuggestedModels = OpenAiCompatibleRouting.VOLC_IMAGE_SUGGESTED_MODELS,
            supportsImage = true,
        ),
        ApiProviderLine(
            "dmxapi",
            "DMXAPI\uff08\u805a\u5408\uff09",
            DmxApiRouting.BASE,
            DmxApiRouting.CHAT_SUGGESTED_MODELS,
            imageSuggestedModels = DmxApiRouting.IMAGE_SUGGESTED_MODELS,
            voiceSuggestedModels = DmxApiRouting.VOICE_SUGGESTED_MODELS,
            supportsImage = true,
            supportsVoice = true,
        ),
        ApiProviderLine(
            "openai",
            "OpenAI",
            "https://api.openai.com/v1",
            listOf("gpt-4o-mini", "gpt-4o"),
            imageSuggestedModels = listOf("dall-e-3"),
            voiceSuggestedModels = listOf("tts-1"),
            supportsImage = true,
            supportsVoice = true,
        ),
        ApiProviderLine(
            "anthropic",
            "Anthropic Claude",
            "https://api.anthropic.com",
            listOf("claude-sonnet-4-5", "claude-opus-4-7", "claude-haiku-4-5"),
        ),
        ApiProviderLine(
            "ollama",
            "Ollama (\u672c\u5730)",
            "http://localhost:11434/v1",
            listOf("llama3", "qwen2"),
        ),
        ApiProviderLine(
            "gemini",
            "Google Gemini (OpenAI \u517c\u5bb9)",
            "https://generativelanguage.googleapis.com/v1beta/openai/",
            listOf("gemini-2.0-flash"),
        ),
        ApiProviderLine(
            "local_tts",
            "\u672c\u673a\u6717\u8bfb\uff08\u514d\u8d39\uff09",
            OpenAiCompatibleRouting.LOCAL_TTS_BASE,
            supportsChat = false,
            voiceSuggestedModels = listOf("system"),
            supportsVoice = true,
        ),
        ApiProviderLine(
            "fish_audio",
            "Fish Audio",
            OpenAiCompatibleRouting.FISH_AUDIO_BASE,
            supportsChat = false,
            voiceSuggestedModels = listOf("s2-pro"),
            supportsVoice = true,
        ),
        ApiProviderLine(
            "custom",
            "\u81ea\u5b9a\u4e49\uff08\u4ec5\u624b\u586b\uff09",
            "",
            supportsImage = true,
            supportsVoice = true,
        ),
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
