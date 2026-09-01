package com.mojing.app.config

import com.mojing.app.domain.config.OpenAiCompatibleRouting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleRoutingTest {

    @Test
    fun volcChatUrlNoDoubleV1() {
        assertEquals(
            "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
            OpenAiCompatibleRouting.buildChatCompletionsUrl(OpenAiCompatibleRouting.VOLC_ARK_BASE),
        )
    }

    @Test
    fun volcImageUrlForSeedream() {
        assertEquals(
            "https://ark.cn-beijing.volces.com/api/v3/images/generations",
            OpenAiCompatibleRouting.buildImagesGenerationsUrl(OpenAiCompatibleRouting.VOLC_ARK_BASE),
        )
    }

    @Test
    fun zhipuV4ChatUrl() {
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            OpenAiCompatibleRouting.buildChatCompletionsUrl("https://open.bigmodel.cn/api/paas/v4"),
        )
    }

    @Test
    fun baiduV2ChatUrl() {
        assertEquals(
            "https://qianfan.baidubce.com/v2/chat/completions",
            OpenAiCompatibleRouting.buildChatCompletionsUrl("https://qianfan.baidubce.com/v2"),
        )
    }

    @Test
    fun deepseekBareHostInsertsV1() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            OpenAiCompatibleRouting.buildChatCompletionsUrl("https://api.deepseek.com"),
        )
    }

    @Test
    fun seedreamModelsDetected() {
        assertTrue(OpenAiCompatibleRouting.isSeedreamModel("doubao-seedream-5.0-lite"))
        assertTrue(OpenAiCompatibleRouting.isSeedreamModel("doubao-seedream-4.5"))
        assertTrue(OpenAiCompatibleRouting.isSeedreamModel("Doubao-Seedream-4.5"))
    }

    @Test
    fun seedreamSizeMaps3KForLite() {
        assertEquals("3K", OpenAiCompatibleRouting.mapSeedreamSize("3072x3072"))
        assertEquals("2K", OpenAiCompatibleRouting.mapSeedreamSize("1024x1024"))
    }

    @Test
    fun probeBasesAvoidVolcDoubleV1() {
        val bases = OpenAiCompatibleRouting.collectProbeBases(OpenAiCompatibleRouting.VOLC_ARK_BASE) { it }
        assertFalse(bases.any { it.contains("/api/v3/v1") })
    }

    @Test
    fun fishAudioTtsUrl() {
        assertEquals(
            "https://api.fish.audio/v1/tts",
            OpenAiCompatibleRouting.buildFishTtsUrl(OpenAiCompatibleRouting.FISH_AUDIO_BASE),
        )
    }

    @Test
    fun normalizeStripsMisplacedChatPath() {
        assertEquals(
            OpenAiCompatibleRouting.VOLC_ARK_BASE,
            OpenAiCompatibleRouting.normalizeBase(
                "https://ark.cn-beijing.volces.com/api/v3/v1/chat/completions",
            ),
        )
    }

    @Test
    fun anthropicHostDetected() {
        assertTrue(OpenAiCompatibleRouting.isAnthropicHost("https://api.anthropic.com"))
        assertTrue(OpenAiCompatibleRouting.isAnthropicHost("https://api.anthropic.com/v1/messages"))
        assertFalse(OpenAiCompatibleRouting.isAnthropicHost("https://api.openai.com/v1"))
        assertFalse(OpenAiCompatibleRouting.isAnthropicHost("https://portx.asia/v1"))
    }

    @Test
    fun anthropicConstantsDefined() {
        assertEquals("https://api.anthropic.com", OpenAiCompatibleRouting.ANTHROPIC_BASE)
        assertEquals("2023-06-01", OpenAiCompatibleRouting.ANTHROPIC_VERSION)
    }
}
