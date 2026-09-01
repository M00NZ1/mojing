package com.mojing.app.engine

import com.mojing.app.domain.engine.AiCompleter
import com.mojing.app.domain.engine.LlmRetry
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCompleterFieldKeysTest {

    private val completer = AiCompleter(mockk<LlmRetry>())

    @Test
    fun characterEntryFields() {
        val keys = completer.fieldKeysFor("encyclopedia_entry", "character")
        assertTrue(keys.contains("alias"))
        assertTrue(keys.contains("race"))
        assertTrue(keys.contains("gender"))
        assertTrue(keys.contains("age"))
        assertTrue(keys.contains("occupation"))
        assertTrue(keys.contains("abilities"))
    }

    @Test
    fun locationEntryFields() {
        val keys = completer.fieldKeysFor("encyclopedia_entry", "location")
        assertTrue(keys.contains("region"))
        assertTrue(keys.contains("climate"))
        assertTrue(keys.contains("landmarks"))
        assertTrue(keys.contains("population"))
    }

    @Test
    fun itemEntryFields() {
        val keys = completer.fieldKeysFor("encyclopedia_entry", "item")
        assertTrue(keys.contains("type"))
        assertTrue(keys.contains("origin"))
        assertTrue(keys.contains("abilities"))
        assertTrue(keys.contains("rarity"))
    }

    @Test
    fun genericEntryFields() {
        val keys = completer.fieldKeysFor("encyclopedia_entry", "concept")
        assertTrue(keys.contains("background"))
        assertTrue(keys.contains("significance"))
        assertTrue(keys.contains("relationships"))
    }

    @Test
    fun characterTypeFields() {
        val keys = completer.fieldKeysFor("character", null)
        assertTrue(keys.contains("name"))
        assertTrue(keys.contains("persona_prompt"))
        assertTrue(keys.size >= 4)
    }

    @Test
    fun worldTemplateFields() {
        val keys = completer.fieldKeysFor("world_template", null)
        assertTrue(keys.contains("summary"))
        assertTrue(keys.contains("category"))
        assertTrue(keys.contains("worldPrompt"))
        assertTrue(keys.size >= 4)
    }

    @Test
    fun unknownTypeReturnsEmpty() {
        val keys = completer.fieldKeysFor("unknown_type", null)
        assertTrue(keys.isEmpty())
    }

    @Test
    fun allReturnedKeysAreNonBlankStrings() {
        val keys = completer.fieldKeysFor("encyclopedia_entry", "character")
        assertFalse(keys.any { it.isBlank() })
    }
}
