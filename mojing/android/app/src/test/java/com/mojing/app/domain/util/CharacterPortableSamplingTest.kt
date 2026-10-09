package com.mojing.app.domain.util

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojing.app.data.local.entity.CharacterEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class CharacterPortableSamplingTest {
    private val entity = CharacterEntity(
        name = "采样角色",
        personaPrompt = "保持克制而连续的叙事。",
        modelName = "model-x",
        apiBaseUrl = "https://example.test/v1",
        temperature = 0.0f,
        maxTokens = 1,
        topP = 0.000001f,
        frequencyPenalty = -2.5f,
        presencePenalty = -0.0f,
    )

    @Test
    fun jsonAndTxtRoundTripPreservesZeroNegativeAndBoundarySamplingValues() {
        val json = CharacterPortableCodec.toJsonString(entity, null)
        val parsedJson = CharacterPortableCodec.parsePortableJson(JsonParser.parseString(json).asJsonObject)
        assertSampling(parsedJson)

        val txt = CharacterPortableCodec.toTxt(entity, null)
        val parsedTxt = CharacterPortableCodec.parsePortableJson(CharacterPortableCodec.parseTxt(txt))
        assertSampling(parsedTxt)
    }

    @Test
    fun oldPayloadAndExplicitNullSamplingFieldsRemainOptional() {
        listOf(basePayload(), basePayload().apply {
            listOf("temperature", "max_tokens", "top_p", "frequency_penalty", "presence_penalty").forEach { addProperty(it, "") }
        }).forEach { legacy ->
            val parsed = CharacterPortableCodec.parsePortableJson(legacy)
            assertNull(parsed.temperature)
            assertNull(parsed.maxTokens)
            assertNull(parsed.topP)
            assertNull(parsed.frequencyPenalty)
            assertNull(parsed.presencePenalty)
        }
        val old = JsonParser.parseString(
            """{"kind":"${CharacterPortableCodec.KIND}","version":1,"name":"旧包","persona_prompt":"旧正文","temperature":null,"max_tokens":null,"top_p":null,"frequency_penalty":null,"presence_penalty":null}""",
        ).asJsonObject

        val parsed = CharacterPortableCodec.parsePortableJson(old)
        assertNull(parsed.temperature)
        assertNull(parsed.maxTokens)
        assertNull(parsed.topP)
        assertNull(parsed.frequencyPenalty)
        assertNull(parsed.presencePenalty)
    }

    @Test
    fun numericStringsAndJsonNumbersAreBothAccepted() {
        val root = basePayload().apply {
            addProperty("temperature", "0.25")
            addProperty("max_tokens", "2048")
            addProperty("top_p", 0.75)
            addProperty("frequency_penalty", "-1.5")
            addProperty("presence_penalty", 0)
        }
        val parsed = CharacterPortableCodec.parsePortableJson(root)
        assertEquals(0.25f, parsed.temperature)
        assertEquals(2048, parsed.maxTokens)
        assertEquals(0.75f, parsed.topP)
        assertEquals(-1.5f, parsed.frequencyPenalty)
        assertEquals(0.0f, parsed.presencePenalty)
    }

    @Test
    fun malformedNonFiniteOverflowAndWrongFloatTypesAreRejected() {
        val fields = listOf("temperature", "top_p", "frequency_penalty", "presence_penalty")
        val invalidValues = listOf(
            "true",
            "[]",
            "{}",
            "\"not-a-number\"",
            "\"1e\"",
            "\"NaN\"",
            "\"Infinity\"",
            "\"-Infinity\"",
            "\"3.4028236e38\"",
        )
        fields.forEach { field ->
            invalidValues.forEach { value ->
                val root = basePayload().apply { add(field, JsonParser.parseString(value)) }
                assertRejected("$field=$value") { CharacterPortableCodec.parsePortableJson(root) }
            }
        }
        listOf("true", "[]", "{}", "\"1.5\"", "0", "-1", "2147483648", "\"NaN\"").forEach { value ->
            val root = basePayload().apply { add("max_tokens", JsonParser.parseString(value)) }
            assertRejected("max_tokens=$value") { CharacterPortableCodec.parsePortableJson(root) }
        }
    }

    @Test
    fun mergeSummaryUsesFrozenEntitySamplingAndIgnoresModelSamplingWithoutEntity() {
        val hallucinated = JsonParser.parseString(
            """{"name":"摘要角色","persona_prompt":"摘要正文","temperature":1.9,"max_tokens":9999,"top_p":0.2,"frequency_penalty":4.0,"presence_penalty":3.0}""",
        ).asJsonObject
        val frozen = CharacterPortableCodec.mergeSummaryIntoPortable(hallucinated, entity)
        assertEquals(0.0f, frozen.get("temperature").asFloat)
        assertEquals(1, frozen.get("max_tokens").asInt)
        assertEquals(entity.topP, frozen.get("top_p").asFloat)
        assertEquals(entity.frequencyPenalty, frozen.get("frequency_penalty").asFloat)
        assertEquals(entity.presencePenalty, frozen.get("presence_penalty").asFloat)

        val noEntity = CharacterPortableCodec.mergeSummaryIntoPortable(hallucinated)
        listOf("temperature", "max_tokens", "top_p", "frequency_penalty", "presence_penalty").forEach {
            assertNull(noEntity.get(it))
        }
    }

    private fun basePayload(): JsonObject = JsonObject().apply {
        addProperty("kind", CharacterPortableCodec.KIND)
        addProperty("version", CharacterPortableCodec.VERSION)
        addProperty("name", "角色")
        addProperty("persona_prompt", "正文")
    }

    private fun assertSampling(parsed: CharacterPortableCodec.ParsedPortable) {
        assertEquals(entity.temperature, parsed.temperature)
        assertEquals(entity.maxTokens, parsed.maxTokens)
        assertEquals(entity.topP, parsed.topP)
        assertEquals(entity.frequencyPenalty, parsed.frequencyPenalty)
        assertEquals(entity.presencePenalty, parsed.presencePenalty)
    }

    private fun assertRejected(label: String, block: () -> Unit) {
        try {
            block()
            fail("Invalid sampling value accepted: $label")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
