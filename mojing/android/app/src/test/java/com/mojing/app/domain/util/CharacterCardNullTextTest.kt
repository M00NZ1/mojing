package com.mojing.app.domain.util

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.CharacterProfileEntity
import org.junit.Assert.*
import org.junit.Test

class CharacterCardNullTextTest {
    private val fields = listOf("name", "description", "personality", "scenario", "first_mes", "mes_example", "system_prompt", "post_history_instructions")

    @Test fun eachKnownTextNullIsEmptyWhileOtherTextAndRawNullSurvive() {
        for (field in fields) {
            val data = JsonObject().apply { fields.forEach { addProperty(it, "正文-$it") }; add(field, JsonNull.INSTANCE) }
            val root = JsonObject().apply { addProperty("spec", "chara_card_v2"); addProperty("spec_version", "2.0"); add("data", data); add("unknown", JsonNull.INSTANCE) }
            val parsed = CharacterCardV2Converter.jsonRootToParsedPortable(root, "fixture.json")
            assertEquals(if (field == "name") "" else "正文-name", parsed.name)
            assertFalse(parsed.personaPrompt.contains("正文-$field"))
            assertFalse(parsed.personaPrompt.contains("null"))
            assertEquals(root, JsonParser.parseString(parsed.profile!!.characterCardJson))
        }
    }

    @Test fun unchangedNullTextCardExportsOriginalNullsWithoutReplacingOtherSections() {
        val root = JsonParser.parseString("""{"spec":"chara_card_v2","spec_version":"2.0","data":{"name":"角色","description":null,"personality":null,"scenario":null,"first_mes":"完整开场","mes_example":null,"system_prompt":null,"post_history_instructions":null,"creator_notes":null,"extensions":{"unknown":null}}}""").asJsonObject
        val parsed = CharacterCardV2Converter.jsonRootToParsedPortable(root, "fixture.json")
        val exported = CharacterCardV2Converter.buildV2Export(CharacterEntity(name = parsed.name, personaPrompt = parsed.personaPrompt),
            CharacterProfileEntity(characterId = 1, characterCardJson = parsed.profile!!.characterCardJson))
        for (field in fields.filter { it != "name" && it != "first_mes" }) assertTrue(exported.getAsJsonObject("data")[field].isJsonNull)
        assertEquals("完整开场", exported.getAsJsonObject("data")["first_mes"].asString)
        assertEquals(root, JsonParser.parseString(parsed.profile!!.characterCardJson))
        assertEquals(parsed.personaPrompt, CharacterCardV2Converter.jsonRootToParsedPortable(exported, "roundtrip.png").personaPrompt)
    }

    @Test fun legacyRootKnownNullsUseTheExistingEmptyPersonaFallback() {
        val root = JsonObject().apply { fields.forEach { add(it, JsonNull.INSTANCE) } }
        val parsed = CharacterCardV2Converter.jsonRootToParsedPortable(root, "legacy.png")
        assertEquals("", parsed.name)
        assertEquals("（卡内未含人设正文，请补充）", parsed.personaPrompt)
        assertEquals(root, JsonParser.parseString(parsed.profile!!.characterCardJson))
    }
}
