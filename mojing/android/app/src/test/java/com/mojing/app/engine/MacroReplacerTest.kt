package com.mojing.app.engine

import com.mojing.app.domain.engine.MacroReplacer
import org.junit.Assert.assertEquals
import org.junit.Test

class MacroReplacerTest {

    @Test
    fun replacesUserMacro() {
        val result = MacroReplacer.replace("{{user}}你好", mapOf("user" to "张三"))
        assertEquals("张三你好", result)
    }

    @Test
    fun replacesCharMacro() {
        val result = MacroReplacer.replace("{{char}}向你微笑", mapOf("char" to "李白"))
        assertEquals("李白向你微笑", result)
    }

    @Test
    fun macroNamesCaseInsensitive() {
        val result = MacroReplacer.replace("{{USER}}来了", mapOf("user" to "张三"))
        assertEquals("张三来了", result)
    }

    @Test
    fun whitespaceInBracesIgnored() {
        val result = MacroReplacer.replace("{{ user }}你好", mapOf("user" to "张三"))
        assertEquals("张三你好", result)
    }

    @Test
    fun unknownMacroPreserved() {
        val result = MacroReplacer.replace("{{unknown}}保持不变", emptyMap<String, String>())
        assertEquals("{{unknown}}保持不变", result)
    }

    @Test
    fun replacesCharDescriptionMacroWithUnderscore() {
        val result = MacroReplacer.replace(
            "设定：{{char_description}}",
            mapOf("char_description" to "原始人设"),
        )
        assertEquals("设定：原始人设", result)
    }

    @Test
    fun multipleMacrosInOneString() {
        val result = MacroReplacer.replace(
            "{{user}}对{{char}}说你好",
            mapOf("user" to "张三", "char" to "李白")
        )
        assertEquals("张三对李白说你好", result)
    }
}