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
}
