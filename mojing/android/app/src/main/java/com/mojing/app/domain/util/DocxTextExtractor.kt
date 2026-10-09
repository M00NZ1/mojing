package com.mojing.app.domain.util

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

object DocxTextExtractor {
    /** 从 .docx（ZIP）中提取 `word/document.xml` 内可见文本；非 docx 返回 null。 */
    fun tryExtractPlainText(bytes: ByteArray): String? {
        if (bytes.size < 4 || bytes[0] != 0x50.toByte() || bytes[1] != 0x4B.toByte()) return null
        return try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == "word/document.xml") {
                        val xml = zis.readBytes()
                        val factory = SAXParserFactory.newInstance().apply {
                            isNamespaceAware = true
                            setFeature("http://xml.org/sax/features/external-general-entities", false)
                            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                        }
                        // OOXML text has no DTD; reject declarations before parsing on all runtimes.
                        if (String(xml, Charsets.UTF_8).contains("<!DOCTYPE", ignoreCase = true)) return null
                        val text = StringBuilder()
                        val handler = object : DefaultHandler() {
                            private val wordNamespace = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                            private var inText = false
                            override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
                                if (uri != wordNamespace) return
                                when (localName) {
                                    "t" -> inText = true
                                    "tab" -> text.append('\t')
                                    "br", "cr" -> text.append('\n')
                                }
                            }
                            override fun characters(chars: CharArray, start: Int, length: Int) {
                                if (inText) text.append(chars, start, length)
                            }
                            override fun endElement(uri: String, localName: String, qName: String) {
                                if (uri != wordNamespace) return
                                when (localName) {
                                    "t" -> inText = false
                                    "p" -> text.append('\n')
                                }
                            }
                        }
                        factory.newSAXParser().parse(ByteArrayInputStream(xml), handler)
                        return text.toString().replace('\u000b', ' ').trim().ifEmpty { null }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
