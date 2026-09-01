package com.mojing.app.engine

import com.mojing.app.domain.engine.StructuredParser
import org.junit.Assert.*
import org.junit.Test

class StructuredParserTest {

    @Test
    fun `parses NARRATION tags`() {
        val reply = StructuredParser.parse("<NARRATION>夕阳西下，山峰染上了金色的光辉。</NARRATION>\n你好啊")
        assertEquals(1, reply.narrations.size)
        assertEquals("夕阳西下，山峰染上了金色的光辉。", reply.narrations[0])
    }

    @Test
    fun `parses THOUGHT tags`() {
        val reply = StructuredParser.parse("我想想...<THOUGHT>这个人似乎对我有敌意</THOUGHT>")
        assertEquals(1, reply.thoughts.size)
        assertEquals("这个人似乎对我有敌意", reply.thoughts[0])
    }

    @Test
    fun `parses SPEECH tags`() {
        val reply = StructuredParser.parse("<SPEECH name=\"李白\">少侠，今日可好？</SPEECH>")
        assertEquals(1, reply.speeches.size)
        assertEquals("李白", reply.speeches[0].characterName)
        assertEquals("少侠，今日可好？", reply.speeches[0].text)
    }

    @Test
    fun `parses OPTION tags`() {
        val reply = StructuredParser.parse("<OPTION>继续前行</OPTION><OPTION>回头看看</OPTION>")
        assertEquals(2, reply.choices.size)
        assertEquals("继续前行", reply.choices[0])
        assertEquals("回头看看", reply.choices[1])
    }

    @Test
    fun `parses wrapped OPTION tags case insensitively and removes duplicates`() {
        val reply = StructuredParser.parse(
            "<CHOICES><OPTION>继续前行</OPTION><option>回头看看</option><OPTION>继续前行</OPTION></CHOICES>",
        )
        assertEquals(listOf("继续前行", "回头看看"), reply.choices)
    }

    @Test
    fun `parses attributed choice tags without leaking them into plain text`() {
        val raw = "正文。<CHOICES type=\"actions\">" +
            "<OPTION id=\"enter\">推门进入</OPTION>" +
            "<OPTION id=\"wait\">先观察</OPTION></CHOICES>"

        val reply = StructuredParser.parse(raw)

        assertEquals(listOf("推门进入", "先观察"), reply.choices)
        assertEquals("正文。", reply.plainText)
        assertTrue(StructuredParser.isStructured(raw))
        assertEquals("正文。", StructuredParser.stripTags(raw))
    }

    @Test
    fun `detects structured content`() {
        assertTrue(StructuredParser.isStructured("<NARRATION>测试</NARRATION>"))
        assertTrue(StructuredParser.isStructured("<SPEECH>测试</SPEECH>"))
        assertFalse(StructuredParser.isStructured("普通文本没有标签"))
    }

    @Test
    fun `stripTags removes XML tags`() {
        val result = StructuredParser.stripTags("<NARRATION>旁白</NARRATION>你好<SPEECH>对话</SPEECH>")
        assertFalse(result.contains("<NARRATION>"))
        assertFalse(result.contains("<SPEECH>"))
        assertTrue(result.contains("旁白"))
        assertTrue(result.contains("你好"))
    }

    @Test
    fun `plain reply with trailing choices keeps body separate from clickable choices`() {
        val raw = """
            风雨越来越大，你必须马上决定。

            ### 可选行动
            1. 进入山洞
            2. 沿原路返回
        """.trimIndent()

        val reply = StructuredParser.parse(raw)

        assertEquals(listOf("进入山洞", "沿原路返回"), reply.choices)
        assertEquals("风雨越来越大，你必须马上决定。", reply.plainText)
        assertTrue(StructuredParser.isStructured(raw))
    }

    @Test
    fun `stripTags omits choices from model history`() {
        val raw = "<NARRATION>夜色降临。</NARRATION>" +
            "<CHOICES><OPTION>前往车站</OPTION><OPTION>留在原地</OPTION></CHOICES>"

        val result = StructuredParser.stripTags(raw)

        assertEquals("夜色降临。", result)
        assertFalse(result.contains("前往车站"))
        assertFalse(result.contains("[选项]"))
    }

    @Test
    fun `empty content returns empty reply`() {
        val reply = StructuredParser.parse("")
        assertEquals(0, reply.narrations.size)
        assertEquals(0, reply.thoughts.size)
        assertEquals(0, reply.speeches.size)
        assertEquals(0, reply.choices.size)
    }
}
