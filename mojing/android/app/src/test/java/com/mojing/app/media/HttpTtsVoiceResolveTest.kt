package com.mojing.app.media

import org.junit.Assert.assertEquals
import org.junit.Test

class HttpTtsVoiceResolveTest {

    @Test
    fun dmxDefaultMinimaxVoice() {
        assertEquals(
            "male-qn-qingse",
            HttpTts.resolveSpeechVoice(
                "https://www.dmxapi.cn/v1",
                "speech-2.6-hd",
                "",
            ),
        )
    }

    @Test
    fun dmxDefaultMimoVoice() {
        assertEquals(
            "mimo_default",
            HttpTts.resolveSpeechVoice("https://www.dmxapi.cn/v1", "mimo-v2-tts", ""),
        )
    }

    @Test
    fun dmxDefaultGeminiTtsVoice() {
        assertEquals(
            "Kore",
            HttpTts.resolveSpeechVoice(
                "https://www.dmxapi.cn/v1",
                "gemini-2.5-pro-preview-tts",
                "",
            ),
        )
    }

    @Test
    fun openAiDefaultAlloy() {
        assertEquals(
            "alloy",
            HttpTts.resolveSpeechVoice("https://api.openai.com/v1", "tts-1", "")
        )
    }

    @Test
    fun siliconFlowShortVoiceGetsModelPrefix() {
        assertEquals(
            "FunAudioLLM/CosyVoice2-0.5B:claire",
            HttpTts.resolveSpeechVoice(
                "https://api.siliconflow.cn/v1",
                "FunAudioLLM/CosyVoice2-0.5B",
                "claire"
            )
        )
    }

    @Test
    fun siliconFlowEmptyVoiceUsesClaire() {
        assertEquals(
            "FunAudioLLM/CosyVoice2-0.5B:claire",
            HttpTts.resolveSpeechVoice(
                "https://api.siliconflow.cn/v1",
                "FunAudioLLM/CosyVoice2-0.5B",
                ""
            )
        )
    }

    @Test
    fun siliconFlowFullVoiceUnchanged() {
        assertEquals(
            "FunAudioLLM/CosyVoice2-0.5B:alex",
            HttpTts.resolveSpeechVoice(
                "https://api.siliconflow.cn/v1",
                "FunAudioLLM/CosyVoice2-0.5B",
                "FunAudioLLM/CosyVoice2-0.5B:alex"
            )
        )
    }

    @Test
    fun siliconFlowPrefixOverrideUsesPresetPrefixModel() {
        assertEquals(
            "fishaudio/fish-speech-1.5:bella",
            HttpTts.resolveSpeechVoice(
                "https://api.siliconflow.cn/v1",
                "FunAudioLLM/CosyVoice2-0.5B",
                "bella",
                "fishaudio/fish-speech-1.5"
            )
        )
    }

    @Test
    fun siliconFlowEmptyVoiceUsesPrefixModelForDefaultClaire() {
        assertEquals(
            "fishaudio/fish-speech-1.5:claire",
            HttpTts.resolveSpeechVoice(
                "https://api.siliconflow.cn/v1",
                "FunAudioLLM/CosyVoice2-0.5B",
                "",
                "fishaudio/fish-speech-1.5"
            )
        )
    }
}
