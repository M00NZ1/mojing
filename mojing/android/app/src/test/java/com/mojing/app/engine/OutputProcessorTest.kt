package com.mojing.app.engine

import com.mojing.app.domain.engine.OutputProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OutputProcessorTest {

    @Test
    fun removesAISelfReference() {
        var result = OutputProcessor.process("As an AI, I think this is interesting.")
        assertFalse(result.contains("as an AI"))
    }

    @Test
    fun removesLanguageModelPhrase() {
        var result = OutputProcessor.process("as a language model I cannot help")
        assertFalse(result.contains("language model"))
    }

    @Test
    fun extractChoicesParsesOptionTags() {
        val choices = OutputProcessor.extractChoices("<OPTION>向东走</OPTION><OPTION>向西走</OPTION>")
        assertEquals(2, choices.size)
        assertEquals("向东走", choices[0])
        assertEquals("向西走", choices[1])
    }

    @Test
    fun extractChoicesEmptyForPlainText() {
        assertEquals(0, OutputProcessor.extractChoices("普通文本没有选项").size)
    }

    @Test
    fun processTrimsWhitespace() {
        assertEquals("你好", OutputProcessor.process("  你好  "))
    }
}
