package com.mojing.app.media

import org.junit.Assert.*
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory
import java.io.ByteArrayInputStream

class AzureSpeechTest {
    @Test fun speechFailuresKeepActionableCategoryWithoutRawProviderText() {
        val unauthorized = AzureSpeech.failureMessage(com.mojing.app.data.remote.LlmHttpException(401))
        assertTrue(unauthorized.contains("Speech Key"))
        assertTrue(unauthorized.contains("区域"))
        assertTrue(AzureSpeech.failureMessage(com.mojing.app.data.remote.LlmHttpException(429)).contains("配额"))
        assertTrue(AzureSpeech.failureMessage(java.net.SocketTimeoutException()).contains("超时"))
        assertFalse(AzureSpeech.failureMessage(java.io.IOException("private response body")).contains("private"))
        val preserved = AzureSpeech.SpeechException("请填写区域")
        assertEquals("请填写区域", AzureSpeech.failureMessage(preserved))
    }

    @Test fun ssmlPreservesTextAndEscapesMarkup() {
        val text = "你好 <tag> & \"hello\" '世界'"
        val xml = AzureSpeech.buildSsml("zh-CN-XiaoxiaoNeural", text)
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray()))
        val voice = document.getElementsByTagName("voice").item(0)
        assertEquals(text, voice.textContent)
        assertEquals("zh-CN-XiaoxiaoNeural", voice.attributes.getNamedItem("name").nodeValue)
    }
    @Test fun regionCannotReplaceHostOrInsertAPath() {
        assertEquals("eastasia", AzureSpeech.normalizeRegion(" EastAsia "))
        for (value in listOf("", "eastasia.evil.example", "eastasia/abc", "https://eastasia", "eastasia\nheader")) {
            assertNull(AzureSpeech.normalizeRegion(value))
        }
    }
}
