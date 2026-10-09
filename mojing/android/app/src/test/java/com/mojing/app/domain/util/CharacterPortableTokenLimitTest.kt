package com.mojing.app.domain.util

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class CharacterPortableTokenLimitTest {
    private fun parse(value: String) = CharacterPortableCodec.parsePortableJson(JsonParser.parseString(
        """{"kind":"mojing_character_portable","version":1,"name":"合成角色","persona_prompt":"人设","max_tokens":$value}"""
    ).asJsonObject)

    @Test fun rejectsNonPositiveFractionalAndOverflowValues() {
        listOf("0", "-1", "-2147483648", "1.5", "2147483648", "\"not-a-number\"").forEach { value ->
            try { parse(value); fail("invalid output limit accepted: $value") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun preservesPositiveValuesWithoutGuessingModelCapacity() {
        listOf(1, 1200, 32768, 200_000, Int.MAX_VALUE).forEach { value -> assertEquals(value, parse(value.toString()).maxTokens) }
        assertEquals(1200, parse("\"1200\"").maxTokens)
        assertEquals(null, parse("null").maxTokens)
        assertEquals(null, CharacterPortableCodec.parsePortableJson(JsonParser.parseString(
            """{"kind":"mojing_character_portable","version":1}"""
        ).asJsonObject).maxTokens)
    }
}
