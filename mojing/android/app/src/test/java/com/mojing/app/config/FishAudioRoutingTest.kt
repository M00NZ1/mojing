package com.mojing.app.config

import com.mojing.app.domain.config.FishAudioRouting
import com.mojing.app.domain.config.OpenAiCompatibleRouting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FishAudioRoutingTest {

    @Test
    fun shouldUseFishWhenHostOrModel() {
        assertTrue(
            FishAudioRouting.shouldUseFishDirectApi(
                OpenAiCompatibleRouting.FISH_AUDIO_BASE,
                "system",
            ),
        )
        assertTrue(FishAudioRouting.shouldUseFishDirectApi("https://dmxapi.cn/v1", "s2-pro"))
        assertFalse(FishAudioRouting.shouldUseFishDirectApi("https://api.deepseek.com", "deepseek-chat"))
    }

    @Test
    fun resolveReferenceId() {
        assertEquals(
            "bf322df2096a46f18c579d0baa36f41d",
            FishAudioRouting.resolveReferenceId("bf322df2096a46f18c579d0baa36f41d"),
        )
        assertNull(FishAudioRouting.resolveReferenceId("alloy"))
        assertNull(FishAudioRouting.resolveReferenceId("fishaudio/fish-speech-1.5:bella"))
    }

    @Test
    fun resolveTtsModelHeader() {
        assertEquals("s2-pro", FishAudioRouting.resolveTtsModelHeader("s2"))
        assertEquals("s1", FishAudioRouting.resolveTtsModelHeader("s1"))
    }

    @Test
    fun resolveTtsUrlUsesOfficialWhenModelFishButBaseWrong() {
        assertEquals(
            FishAudioRouting.OFFICIAL_TTS_URL,
            FishAudioRouting.resolveTtsUrl("https://api.siliconflow.cn/v1"),
        )
    }
}
