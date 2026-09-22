package com.mojing.app.media

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsVoicePolicyTest {
    @Test
    fun prefersOfflineChineseVoice() {
        val selected = TtsVoicePolicy.chooseChineseVoice(
            listOf(
                TtsVoiceInfo("zh-network", "zh-CN", requiresNetwork = true, quality = 500),
                TtsVoiceInfo("en-offline", "en-US", requiresNetwork = false, quality = 500),
                TtsVoiceInfo("zh-offline", "zh-CN", requiresNetwork = false, quality = 100),
            ),
        )

        assertEquals("zh-offline", selected?.name)
    }

    @Test
    fun choosesStableBestQualityAmongOfflineChineseVoices() {
        val selected = TtsVoicePolicy.chooseChineseVoice(
            listOf(
                TtsVoiceInfo("zh-slow", "zh-TW", requiresNetwork = false, quality = 100, latency = 500),
                TtsVoiceInfo("zh-fast", "zh-CN", requiresNetwork = false, quality = 300, latency = 500),
                TtsVoiceInfo("english", "en-US", requiresNetwork = false, quality = 500),
            ),
        )

        assertEquals("zh-fast", selected?.name)
    }

    @Test
    fun explainsSynthesisFailureWithActionableGuidance() {
        assertEquals(
            "语音引擎合成失败。请在系统文字转语音设置中下载中文语音数据，或更换朗读引擎后重试。",
            TtsErrorPolicy.message(-3),
        )
    }
    @Test
    fun ignoresUninstalledVoicesAndDoesNotSwitchToNetworkVoice() {
        val offline = TtsVoiceInfo("installed", "zh-CN", false, quality = 100)
        val missing = TtsVoiceInfo("missing", "zh-CN", false, quality = 500, installed = false)
        val network = TtsVoiceInfo("network", "zh-CN", true, quality = 500)
        assertEquals(offline, TtsVoicePolicy.chooseChineseVoice(listOf(missing, network, offline)))
        assertEquals(null, TtsVoicePolicy.chooseChineseVoice(listOf(missing, network)))
        assertEquals(null, TtsVoicePolicy.chooseChineseVoice(emptyList()))
    }
}
