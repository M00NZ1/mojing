package com.mojing.app.domain.util

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterCardPngCodecTest {

    /** 1×1 白像素 PNG（无 `chara` 块），用于嵌入测试。 */
    private val minimalWhitePng: ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
        0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (0xC4).toByte(), (0x89).toByte(),
        0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54,
        0x78, (0x9C).toByte(), 0x63, 0x00, 0x01, 0x00, 0x00, 0x05, 0x00, 0x01,
        0x0D, 0x0A, 0x2D, (0xB4).toByte(),
        0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
        (0xAE).toByte(), 0x42, 0x60, (0x82).toByte(),
    )

    @Test
    fun isPngDetectsSignature() {
        assertTrue(CharacterCardPngCodec.isPng(minimalWhitePng))
        assertFalse(CharacterCardPngCodec.isPng(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun embedCharaThenReadRoundTrip() {
        val root = JsonObject()
        root.addProperty("spec", "chara_card_v2")
        root.addProperty("spec_version", "2.0")
        val data = JsonObject()
        data.addProperty("name", "RoundTrip")
        root.add("data", data)
        val out = CharacterCardPngCodec.embedCharaJson(minimalWhitePng, root)
        assertTrue(CharacterCardPngCodec.isPng(out))
        val read = CharacterCardPngCodec.readCharaCardJsonRoot(out)
        assertNotNull(read)
        assertEquals("RoundTrip", read!!.getAsJsonObject("data").get("name").asString)
    }
}
