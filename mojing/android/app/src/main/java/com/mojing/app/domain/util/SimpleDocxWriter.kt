package com.mojing.app.domain.util

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 纯文本段落 .docx（OOXML zip），与后端 `docx_simple` 一致思路。
 */
object SimpleDocxWriter {
    fun writePlainDocx(text: String): ByteArray {
        val lines = text.replace("\r\n", "\n").split("\n")
        val body = StringBuilder()
        for (line in lines) {
            val esc = xmlEscape(line)
            body.append("<w:p><w:r><w:t xml:space=\"preserve\">").append(esc).append("</w:t></w:r></w:p>")
        }
        val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:body>${body}<w:sectPr><w:pgSz w:w="11906" w:h="16838"/>
<w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body></w:document>"""
        val contentTypes = """<?xml version="1.0" encoding="UTF-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>"""
        val rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""
        val wordRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"/>
"""
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("[Content_Types].xml"))
            z.write(contentTypes.toByteArray(Charsets.UTF_8))
            z.closeEntry()
            z.putNextEntry(ZipEntry("_rels/.rels"))
            z.write(rels.toByteArray(Charsets.UTF_8))
            z.closeEntry()
            z.putNextEntry(ZipEntry("word/_rels/document.xml.rels"))
            z.write(wordRels.toByteArray(Charsets.UTF_8))
            z.closeEntry()
            z.putNextEntry(ZipEntry("word/document.xml"))
            z.write(document.toByteArray(Charsets.UTF_8))
            z.closeEntry()
        }
        return bos.toByteArray()
    }

    private fun xmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
