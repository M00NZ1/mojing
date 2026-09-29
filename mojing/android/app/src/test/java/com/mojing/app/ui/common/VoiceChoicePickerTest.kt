package com.mojing.app.ui.common

import com.mojing.app.media.VoiceOption
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceChoicePickerTest {
    @Test fun onlyChineseLocalesAppearInTheDefaultSystemList() {
        assertTrue(VoiceOption("system:zh", "中文", "zh-CN").isChineseVoice())
        assertTrue(VoiceOption("system:zh-TW", "繁體中文", "zh-TW").isChineseVoice())
        assertFalse(VoiceOption("system:en", "英语", "en-US").isChineseVoice())
        assertFalse(VoiceOption("system:ar", "阿拉伯语", "ar-SA").isChineseVoice())
    }

    @Test fun previewUsesTheSelectedLanguageInsteadOfAlwaysChinese() {
        assertTrue(voicePreviewText("zh-CN").contains("你好"))
        assertTrue(voicePreviewText("en-US").contains("Hello"))
        assertTrue(voicePreviewText("ar-SA").contains("مرحبًا"))
    }
}
