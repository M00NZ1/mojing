package com.mojing.app.media

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceEngineCatalogTest {
    @Test
    fun duplicateAndBlankSystemVoiceIdsAreRemovedBeforeRendering() {
        val normalized = normalizeVoiceOptions(
            listOf(
                VoiceOption("system:zh", "中文"),
                VoiceOption("", "无效空音色"),
                VoiceOption("system:az", "阿塞拜疆语"),
                VoiceOption("system:az", "重复的阿塞拜疆语"),
            ),
        )

        assertEquals(
            listOf(VoiceOption("system:zh", "中文"), VoiceOption("system:az", "阿塞拜疆语")),
            normalized,
        )
    }

    @Test
    fun duplicateNameWithDifferentLanguagesAlwaysKeepsTheChineseRow() {
        val english = VoiceOption("same-id", "声音 · 英语", "en-US")
        val chinese = VoiceOption("same-id", "声音 · 中文", "zh-CN")

        assertEquals(listOf(chinese), normalizeVoiceOptions(listOf(english, chinese)))
        assertEquals(listOf(chinese), normalizeVoiceOptions(listOf(chinese, english)))
    }
}
