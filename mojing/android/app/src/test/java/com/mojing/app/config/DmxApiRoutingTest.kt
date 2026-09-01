package com.mojing.app.config

import com.mojing.app.data.remote.DmxChatTtsApi
import com.mojing.app.data.remote.DmxGeminiApi
import com.mojing.app.data.remote.DmxResponsesApi
import com.mojing.app.domain.config.DmxApiRouting
import com.mojing.app.domain.config.OpenAiCompatibleRouting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DmxApiRoutingTest {

    @Test
    fun dmxResponsesUrl() {
        assertEquals(
            "https://www.dmxapi.cn/v1/responses",
            DmxApiRouting.buildResponsesUrl(DmxApiRouting.BASE),
        )
    }

    @Test
    fun classifyImageProtocols() {
        assertEquals(
            DmxApiRouting.ImageProtocol.RESPONSES_SEEDREAM,
            DmxApiRouting.classifyImageProtocol("doubao-seedream-5.0-lite"),
        )
        assertEquals(
            DmxApiRouting.ImageProtocol.RESPONSES_WAN,
            DmxApiRouting.classifyImageProtocol("wan2.6-t2i"),
        )
        assertEquals(
            DmxApiRouting.ImageProtocol.GEMINI_GENERATE,
            DmxApiRouting.classifyImageProtocol("gemini-3-pro-image-preview"),
        )
        assertEquals(
            DmxApiRouting.ImageProtocol.OPENAI_IMAGES,
            DmxApiRouting.classifyImageProtocol("gpt-image-2-ssvip"),
        )
    }

    @Test
    fun classifyTtsProtocols() {
        assertEquals(
            DmxApiRouting.TtsProtocol.RESPONSES_MINIMAX28,
            DmxApiRouting.classifyTtsProtocol("speech-2.8-hd"),
        )
        assertEquals(
            DmxApiRouting.TtsProtocol.CHAT_MIMO,
            DmxApiRouting.classifyTtsProtocol("mimo-v2-tts"),
        )
        assertEquals(
            DmxApiRouting.TtsProtocol.GEMINI_GENERATE,
            DmxApiRouting.classifyTtsProtocol("gemini-2.5-pro-preview-tts"),
        )
        assertEquals(
            DmxApiRouting.TtsProtocol.OPENAI_SPEECH,
            DmxApiRouting.classifyTtsProtocol("gpt-4o-mini-tts"),
        )
    }

    @Test
    fun seedreamOnDmxUsesResponses() {
        assertTrue(DmxApiRouting.shouldUseResponsesImage(DmxApiRouting.BASE, "doubao-seedream-5.0-lite"))
        assertTrue(DmxApiRouting.shouldUseResponsesImage(DmxApiRouting.BASE, "wan2.7-image-pro"))
        assertFalse(DmxApiRouting.shouldUseResponsesImage(DmxApiRouting.BASE, "gpt-image-2-ssvip"))
    }

    @Test
    fun geminiImageOnDmx() {
        assertTrue(DmxApiRouting.shouldUseGeminiImage(DmxApiRouting.BASE, "gemini-2.5-flash-image"))
    }

    @Test
    fun normalizeStripsResponsesPath() {
        val normalized = OpenAiCompatibleRouting.normalizeBase("https://www.dmxapi.cn/v1/responses")
        assertTrue(
            normalized.equals("https://www.dmxapi.cn", ignoreCase = true) ||
                normalized.equals(DmxApiRouting.BASE, ignoreCase = true),
        )
    }

    @Test
    fun parseWanImageUrlFromOutput() {
        val raw = """{"output":[{"content":[{"type":"image","text":"https://cdn.example/wan.png"}]}]}"""
        assertEquals("https://cdn.example/wan.png", DmxResponsesApi.parseFirstImageUrl(raw))
    }

    @Test
    fun parseGeminiInlineImage() {
        val raw = """{"candidates":[{"content":{"parts":[{"inlineData":{"data":"aGVsbG8="}}]}}]}"""
        assertEquals("aGVsbG8=", DmxGeminiApi.parseInlineImageBase64(raw))
    }

    @Test
    fun parseMimoWavFromChat() {
        val raw = """{"choices":[{"message":{"audio":{"data":"aGVsbG8="}}}]}"""
        assertEquals(5, DmxChatTtsApi.parseWavBytes(raw)?.size)
    }

    @Test
    fun parseHexAudio() {
        val raw = """{"data":{"audio":"0102ff"}}"""
        val bytes = DmxResponsesApi.parseHexAudio(raw)
        assertEquals(3, bytes?.size)
    }
}
