package com.mojing.app.domain.util

import com.mojing.app.data.local.entity.CharacterEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocxTextExtractorTest {
    @Test
    fun simpleDocxRoundTripsPortableTxtAndAllSamplingFields() {
        val entity = CharacterEntity(
            name = "实体 & <引号> \"角色\"",
            personaPrompt = "第一行 <&>\n第二行\"引号\"",
            temperature = 0.0f,
            maxTokens = 1,
            topP = 0.000001f,
            frequencyPenalty = -2.5f,
            presencePenalty = 0.0f,
        )
        val portableTxt = CharacterPortableCodec.toTxt(entity, null)
        val docx = SimpleDocxWriter.writePlainDocx(portableTxt)
        val extracted = DocxTextExtractor.tryExtractPlainText(docx)

        val parsed = CharacterPortableCodec.parsePortableJson(
            CharacterPortableCodec.parseTxt(extracted!!),
        )
        assertEquals(entity.name, parsed.name)
        assertEquals(entity.personaPrompt, parsed.personaPrompt)
        assertEquals(entity.temperature, parsed.temperature)
        assertEquals(entity.maxTokens, parsed.maxTokens)
        assertEquals(entity.topP, parsed.topP)
        assertEquals(entity.frequencyPenalty, parsed.frequencyPenalty)
        assertEquals(entity.presencePenalty, parsed.presencePenalty)
    }

    @Test
    fun saxExtractionPreservesRunsTabsBreaksParagraphsAndAlternateNamespacePrefix() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <x:document xmlns:x="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <x:body>
                <x:p><x:r><x:t>A &amp; B &lt; C &gt; D &quot;Q&quot;</x:t><x:tab/><x:t>E</x:t><x:br/><x:t>F</x:t><x:cr/><x:t>G</x:t></x:r></x:p>
                <x:p/>
                <x:p><x:r><x:t>H</x:t></x:r></x:p>
              </x:body>
            </x:document>""".trimIndent()

        assertEquals(
            "A & B < C > D \"Q\"\tE\nF\nG\n\nH",
            DocxTextExtractor.tryExtractPlainText(zipOf("word/document.xml" to xml.toByteArray())),
        )
    }

    @Test
    fun unrelatedZipEntriesAreIgnored() {
        val xml = """<alt:document xmlns:alt="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><alt:body><alt:p><alt:r><alt:t>正文</alt:t></alt:r></alt:p></alt:body></alt:document>"""
        val bytes = zipOf(
            "word/other.xml" to "<broken".toByteArray(),
            "word/document.xml" to xml.toByteArray(),
        )

        assertEquals("正文", DocxTextExtractor.tryExtractPlainText(bytes))
    }

    @Test
    fun malformedXmlAndExternalDoctypeAreRejected() {
        val malformed = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>".toByteArray()
        assertNull(DocxTextExtractor.tryExtractPlainText(zipOf("word/document.xml" to malformed)))

        val externalDoctype = """<?xml version="1.0"?>
            <!DOCTYPE w:document [<!ENTITY external SYSTEM "file:///tmp/secret">]>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>&external;</w:t></w:r></w:p></w:body></w:document>""".trimIndent().toByteArray()
        assertNull(DocxTextExtractor.tryExtractPlainText(zipOf("word/document.xml" to externalDoctype)))
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
