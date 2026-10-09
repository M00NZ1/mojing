package com.mojing.app.domain.util

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Inflater
/**
 * SillyTavern / Character Card V2 PNG：`tEXt` / `zTXt` 关键字 **`chara`**（Base64 UTF-8 JSON），
 * 与后端 `character_card_service.py` 对齐。
 */
object CharacterCardPngCodec {

    private val gson = GsonBuilder().serializeNulls().create()
    private val pngMagic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(pngMagic)

    fun readCharaCardJsonRoot(pngBytes: ByteArray): JsonObject? {
        if (!isPng(pngBytes)) return null
        var offset = 8
        while (offset + 12 <= pngBytes.size) {
            val length = readUInt32BE(pngBytes, offset)
            val type = String(pngBytes, offset + 4, 4, Charsets.ISO_8859_1)
            val dataStart = offset + 8
            val dataEnd = dataStart + length
            if (dataEnd > pngBytes.size) return null
            val chunkData = pngBytes.copyOfRange(dataStart, dataEnd)
            when (type) {
                "tEXt" -> {
                    val nullIdx = chunkData.indexOf(0)
                    if (nullIdx > 0) {
                        val keyword = chunkData.copyOfRange(0, nullIdx).toString(Charsets.ISO_8859_1)
                        if (keyword == "chara") {
                            val b64 = chunkData.copyOfRange(nullIdx + 1, chunkData.size).toString(Charsets.ISO_8859_1)
                            decodeCharaB64Json(b64)?.let { return it }
                        }
                    }
                }
                "zTXt" -> {
                    val nullIdx = chunkData.indexOf(0)
                    if (nullIdx > 0 && nullIdx + 2 <= chunkData.size) {
                        val keyword = chunkData.copyOfRange(0, nullIdx).toString(Charsets.ISO_8859_1)
                        if (keyword == "chara" && chunkData[nullIdx + 1].toInt() == 0) {
                            val compressed = chunkData.copyOfRange(nullIdx + 2, chunkData.size)
                            val inflated = inflateZlib(compressed) ?: continue
                            val jsonText = inflated.toString(Charsets.UTF_8)
                            runCatching { JsonParser.parseString(jsonText).asJsonObject }.getOrNull()?.let { return it }
                        }
                    }
                }
            }
            offset += 12 + length
        }
        return null
    }

    private fun decodeCharaB64Json(b64: String): JsonObject? {
        val jsonText = runCatching {
            String(java.util.Base64.getDecoder().decode(b64.trim()), Charsets.UTF_8)
        }.getOrNull() ?: return null
        return runCatching { JsonParser.parseString(jsonText).asJsonObject }.getOrNull()
    }

    fun embedCharaJson(pngBytes: ByteArray, cardRoot: JsonObject): ByteArray {
        val jsonText = gson.toJson(cardRoot)
        val b64 = java.util.Base64.getEncoder().encodeToString(jsonText.toByteArray(Charsets.UTF_8))
        val newChunk = buildTextChunk("chara", b64)
        val out = ByteArrayOutputStream()
        out.write(pngBytes, 0, 8)
        var offset = 8
        var inserted = false
        while (offset + 12 <= pngBytes.size) {
            val length = readUInt32BE(pngBytes, offset)
            val type = String(pngBytes, offset + 4, 4, Charsets.ISO_8859_1)
            val chunkTotal = 12 + length
            if (offset + chunkTotal > pngBytes.size) break
            val dataStart = offset + 8
            val chunkData = pngBytes.copyOfRange(dataStart, dataStart + length)
            val skip = (type == "tEXt" || type == "zTXt") && isCharaKeyword(chunkData)
            if (!skip) {
                if (type == "IEND" && !inserted) {
                    out.write(newChunk)
                    inserted = true
                }
                out.write(pngBytes, offset, chunkTotal)
            }
            offset += chunkTotal
        }
        if (!inserted) {
            out.write(newChunk)
        }
        return out.toByteArray()
    }

    private fun isCharaKeyword(chunkData: ByteArray): Boolean {
        val nullIdx = chunkData.indexOf(0)
        if (nullIdx <= 0) return false
        val kw = chunkData.copyOfRange(0, nullIdx).toString(Charsets.ISO_8859_1)
        return kw == "chara"
    }

    private fun buildTextChunk(keyword: String, b64Latin: String): ByteArray {
        val kw = keyword.toByteArray(Charsets.ISO_8859_1)
        val txt = b64Latin.toByteArray(Charsets.ISO_8859_1)
        val payload = ByteArrayOutputStream().apply {
            write(kw)
            write(0)
            write(txt)
        }.toByteArray()
        val type = "tEXt".toByteArray(Charsets.US_ASCII)
        val lenBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(payload.size).array()
        val crc = CRC32()
        crc.update(type)
        crc.update(payload)
        val crcVal = crc.value
        val crcBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt((crcVal and 0xffffffffL).toInt()).array()
        return ByteArrayOutputStream().apply {
            write(lenBuf)
            write(type)
            write(payload)
            write(crcBuf)
        }.toByteArray()
    }

    private fun readUInt32BE(buf: ByteArray, off: Int): Int {
        return ((buf[off].toInt() and 0xFF) shl 24) or
            ((buf[off + 1].toInt() and 0xFF) shl 16) or
            ((buf[off + 2].toInt() and 0xFF) shl 8) or
            (buf[off + 3].toInt() and 0xFF)
    }

    private fun inflateZlib(compressed: ByteArray): ByteArray? = runCatching {
        val inflater = Inflater(true)
        inflater.setInput(compressed)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && inflater.needsInput()) break
            out.write(buf, 0, n)
        }
        inflater.end()
        out.toByteArray()
    }.getOrNull()
}
