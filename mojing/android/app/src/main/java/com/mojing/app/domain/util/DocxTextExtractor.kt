package com.mojing.app.domain.util

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

object DocxTextExtractor {
    /** 从 .docx（ZIP）中提取 `word/document.xml` 内可见文本；非 docx 返回 null。 */
    fun tryExtractPlainText(bytes: ByteArray): String? {
        if (bytes.size < 4 || bytes[0] != 0x50.toByte() || bytes[1] != 0x4B.toByte()) return null
        return try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                val sb = StringBuilder()
                while (true) {
                    val entry = zis.nextEntry ?: break
                    if (entry.name == "word/document.xml") {
                        val xml = zis.readBytes().toString(Charsets.UTF_8)
                        Regex("<w:t[^>]*>([^<]*)</w:t>")
                            .findAll(xml)
                            .forEach { m -> sb.append(m.groupValues[1]) }
                    } else {
                        zis.readBytes()
                    }
                    zis.closeEntry()
                }
                sb.toString().replace('\u000b', ' ').trim().ifEmpty { null }
            }
        } catch (_: Exception) {
            null
        }
    }
}
