package com.mojing.app.media

import org.junit.Assert.*
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory
import java.io.ByteArrayInputStream

class AzureSpeechTest {
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
