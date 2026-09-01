package com.mojing.app.ui

import com.mojing.app.ui.common.ApiProviderPresets
import com.mojing.app.ui.common.ApiVendorModelHint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiProviderPresetsTest {

    @Test
    fun imageDropdownOnlyCapableVendors() {
        val ids = ApiProviderPresets.linesForImage().map { it.id }.toSet()
        assertTrue(ids.containsAll(setOf("siliconflow", "volcengine", "openai", "dmxapi", "custom")))
        assertFalse(ids.contains("deepseek"))
        assertFalse(ids.contains("zhipu"))
        assertFalse(ids.contains("moonshot"))
        assertFalse(ids.contains("gemini"))
        assertFalse(ids.contains("ollama"))
    }

    @Test
    fun voiceDropdownIncludesFishAndLocal() {
        val ids = ApiProviderPresets.linesForVoice().map { it.id }.toSet()
        assertTrue(ids.containsAll(setOf("local_tts", "fish_audio", "siliconflow", "openai", "dmxapi", "custom")))
        assertFalse(ids.contains("deepseek"))
        assertFalse(ids.contains("anthropic"))
    }

    @Test
    fun chatDropdownExcludesVoiceOnlyLines() {
        val ids = ApiProviderPresets.linesForChat().map { it.id }.toSet()
        assertTrue(ids.contains("deepseek"))
        assertTrue(ids.contains("anthropic"))
        assertFalse(ids.contains("fish_audio"))
        assertFalse(ids.contains("local_tts"))
    }

    @Test
    fun baiduRemovedFromPresets() {
        val ids = ApiProviderPresets.LINES.map { it.id }.toSet()
        assertFalse(ids.contains("baidu"))
    }

    @Test
    fun anthropicHasUpdatedModels() {
        val line = ApiProviderPresets.LINES.first { it.id == "anthropic" }
        assertTrue(line.suggestedModels.contains("claude-sonnet-4-5"))
        assertTrue(line.suggestedModels.contains("claude-opus-4-7"))
        assertFalse(line.suggestedModels.contains("claude-3-5-sonnet-20241022"))
    }

    @Test
    fun linesForHintMatchesDedicatedFilters() {
        assertEqualsLists(
            ApiProviderPresets.linesFor(ApiVendorModelHint.IMAGE),
            ApiProviderPresets.linesForImage(),
        )
        assertEqualsLists(
            ApiProviderPresets.linesFor(ApiVendorModelHint.VOICE_TTS),
            ApiProviderPresets.linesForVoice(),
        )
    }

    private fun assertEqualsLists(
        a: List<com.mojing.app.ui.common.ApiProviderLine>,
        b: List<com.mojing.app.ui.common.ApiProviderLine>,
    ) {
        assertTrue(a.map { it.id } == b.map { it.id })
    }
}
