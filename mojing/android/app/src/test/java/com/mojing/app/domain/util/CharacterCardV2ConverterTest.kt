package com.mojing.app.domain.util

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.CharacterProfileEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterCardV2ConverterTest {
    private val gson = Gson()

    @Test
    fun newCharacterKeepsComplete16000CharacterPersona() {
        val persona = "长".repeat(16000)

        val root = CharacterCardV2Converter.buildV2Export(
            CharacterEntity(id = 11, name = "长角色", personaPrompt = persona),
            profile = null,
        )

        assertEquals(16000, persona.length)
        assertEquals(persona, root.data().get("description").asString)
        assertEquals(16000, root.data().get("description").asString.length)
    }

    @Test
    fun unchangedProfileKeepsStructuredBodyNotesAndUnknownExtensions() {
        val original = profileJson(
            description = "核心描述",
            personality = "谨慎而温柔",
            scenario = "雨夜车站",
            firstMes = "你终于来了。",
            creatorNotes = "用户备注：不要删掉这句话",
        ).apply {
            data().addProperty("vendor_extra", "keep-me")
            data().add("extensions", JsonObject().apply { addProperty("x_unknown", "保留") })
        }
        val unchangedPersona = "【场景】雨夜车站\n\n核心描述\n\n谨慎而温柔\n\n【开场白】\n你终于来了。"
        val profile = CharacterProfileEntity(
            characterId = 11,
            characterCardJson = gson.toJson(original),
        )

        val exported = CharacterCardV2Converter.buildV2Export(
            CharacterEntity(id = 11, name = "改名但正文未改", personaPrompt = unchangedPersona),
            profile,
        )
        val data = exported.data()

        assertEquals("改名但正文未改", data.get("name").asString)
        assertEquals("核心描述", data.get("description").asString)
        assertEquals("用户备注：不要删掉这句话", data.get("creator_notes").asString)
        assertEquals("keep-me", data.get("vendor_extra").asString)
        assertEquals("保留", data.getAsJsonObject("extensions").get("x_unknown").asString)
    }

    @Test
    fun editedProfileUsesCompleteDescriptionWithoutDuplicatingOnReimportAndLeavesProfileJsonUntouched() {
        val original = profileJson(
            description = "旧描述",
            personality = "旧性格",
            scenario = "旧场景",
            firstMes = "旧开场",
            creatorNotes = "独立创作者备注",
        ).apply {
            data().add("extensions", JsonObject().apply { addProperty("unknown", true) })
        }
        val originalJson = gson.toJson(original)
        val profile = CharacterProfileEntity(characterId = 12, characterCardJson = originalJson)
        val editedPersona = "当前完整正文：" + "新设定。".repeat(3000)

        val exported = CharacterCardV2Converter.buildV2Export(
            CharacterEntity(id = 12, name = "已编辑角色", personaPrompt = editedPersona),
            profile,
        )
        val data = exported.data()
        val reparsed = CharacterCardV2Converter.jsonRootToParsedPortable(exported, "edited.png")

        assertEquals(editedPersona, data.get("description").asString)
        assertEquals("", data.get("personality").asString)
        assertEquals("", data.get("scenario").asString)
        assertEquals("", data.get("first_mes").asString)
        assertEquals("", data.get("mes_example").asString)
        assertEquals("", data.get("system_prompt").asString)
        assertEquals("", data.get("post_history_instructions").asString)
        assertEquals("独立创作者备注", data.get("creator_notes").asString)
        assertEquals(true, data.getAsJsonObject("extensions").get("unknown").asBoolean)
        assertEquals(editedPersona, reparsed.personaPrompt)
        assertEquals(originalJson, profile.characterCardJson)
    }

    @Test
    fun editedCardJsonSurvivesPngEmbedAndReadRoundTrip() {
        val root = CharacterCardV2Converter.buildV2Export(
            CharacterEntity(id = 13, name = "PNG角色", personaPrompt = "PNG 当前完整正文"),
            CharacterProfileEntity(
                characterId = 13,
                characterCardJson = gson.toJson(profileJson(creatorNotes = "保留备注")),
            ),
        )

        val encoded = CharacterCardPngCodec.embedCharaJson(minimalWhitePng, root)
        val decoded = CharacterCardPngCodec.readCharaCardJsonRoot(encoded)

        assertNotNull(decoded)
        assertEquals(root, decoded)
        assertEquals("PNG 当前完整正文", decoded!!.data().get("description").asString)
        assertEquals("保留备注", decoded.data().get("creator_notes").asString)
        assertTrue(CharacterCardPngCodec.isPng(encoded))
    }

    @Test
    fun pngRoundTripPreservesNestedNullsRootUnknownsAndImportedProfileJson() {
        val original = profileJson(description = "完整正文", creatorNotes = "保留备注").apply {
            data().add("extensions", JsonObject().apply {
                add("explicit_null", JsonNull.INSTANCE)
                add("nested", JsonObject().apply { add("nested_null", JsonNull.INSTANCE) })
            })
            add("root_unknown", JsonObject().apply { add("root_null", JsonNull.INSTANCE) })
        }
        val profileJson = GsonBuilder().serializeNulls().create().toJson(original)
        val profile = CharacterProfileEntity(characterId = 17, characterCardJson = profileJson)
        val root = CharacterCardV2Converter.buildV2Export(
            CharacterEntity(id = 17, name = "空值角色", personaPrompt = "完整正文"),
            profile,
        )

        val decoded = CharacterCardPngCodec.readCharaCardJsonRoot(
            CharacterCardPngCodec.embedCharaJson(minimalWhitePng, root),
        )!!
        val reparsed = CharacterCardV2Converter.jsonRootToParsedPortable(decoded, "nulls.png")

        assertEquals(JsonNull.INSTANCE, decoded.data().getAsJsonObject("extensions").get("explicit_null"))
        assertEquals(JsonNull.INSTANCE, decoded.data().getAsJsonObject("extensions").getAsJsonObject("nested").get("nested_null"))
        assertEquals(JsonNull.INSTANCE, decoded.getAsJsonObject("root_unknown").get("root_null"))
        assertEquals(GsonBuilder().serializeNulls().create().toJson(decoded), reparsed.profile!!.characterCardJson)
    }

    @Test
    fun standaloneCreatorNotesStayInProfileJsonButNeverEnterPersona() {
        val root = profileJson(description = "独立正文", creatorNotes = "只属于元资料的备注")

        val parsed = CharacterCardV2Converter.jsonRootToParsedPortable(root, "notes.png")

        assertEquals("独立正文", parsed.personaPrompt)
        assertTrue(parsed.profile!!.characterCardJson.contains("只属于元资料的备注"))
    }

    @Test
    fun malformedProfileFallsBackToCompleteCurrentPersona() {
        val persona = "损坏 profile 后仍应导出的完整正文：" + "长段落。".repeat(3000)
        val profile = CharacterProfileEntity(characterId = 14, characterCardJson = "{not-json")

        val exported = CharacterCardV2Converter.buildV2Export(
            CharacterEntity(id = 14, name = "损坏卡角色", personaPrompt = persona),
            profile,
        )

        assertEquals(persona, exported.data().get("description").asString)
        assertEquals("", exported.data().get("personality").asString)
        assertEquals("", exported.data().get("scenario").asString)
        assertEquals("", exported.data().get("first_mes").asString)
        assertEquals("", exported.data().get("mes_example").asString)
        assertEquals("", exported.data().get("system_prompt").asString)
        assertEquals("", exported.data().get("post_history_instructions").asString)
    }

    @Test
    fun editedLegacyPersonaContainingOldNotesStaysCompleteWithoutReimportDuplication() {
        val original = profileJson(description = "旧正文", creatorNotes = "旧备注")
        val originalJson = gson.toJson(original)
        val profile = CharacterProfileEntity(characterId = 15, characterCardJson = originalJson)
        val currentPersona = "旧正文\n\n旧备注\n\n当前追加正文"

        val exported = CharacterCardV2Converter.buildV2Export(
            CharacterEntity(id = 15, name = "历史角色", personaPrompt = currentPersona),
            profile,
        )
        val reparsed = CharacterCardV2Converter.jsonRootToParsedPortable(exported, "legacy.png")

        assertEquals(currentPersona, exported.data().get("description").asString)
        assertEquals("旧备注", exported.data().get("creator_notes").asString)
        assertEquals(currentPersona, reparsed.personaPrompt)
        assertEquals(originalJson, profile.characterCardJson)
    }

    @Test
    fun blankPersonaExportsBlankDescriptionAndKeepsExistingImportPlaceholder() {
        val exported = CharacterCardV2Converter.buildV2Export(
            CharacterEntity(id = 16, name = "空正文角色", personaPrompt = ""),
            profile = null,
        )

        assertEquals("", exported.data().get("description").asString)
        assertEquals(
            "（卡内未含人设正文，请补充）",
            CharacterCardV2Converter.jsonRootToParsedPortable(exported, "blank.png").personaPrompt,
        )
    }

    private fun profileJson(
        description: String = "",
        personality: String = "",
        scenario: String = "",
        firstMes: String = "",
        creatorNotes: String = "",
    ): JsonObject {
        val data = JsonObject().apply {
            addProperty("name", "原角色")
            addProperty("description", description)
            addProperty("personality", personality)
            addProperty("scenario", scenario)
            addProperty("first_mes", firstMes)
            addProperty("mes_example", "")
            addProperty("system_prompt", "")
            addProperty("post_history_instructions", "")
            addProperty("creator_notes", creatorNotes)
            addProperty("creator", "原作者")
        }
        return JsonObject().apply {
            addProperty("spec", "chara_card_v2")
            addProperty("spec_version", "2.0")
            add("data", data)
        }
    }

    private fun JsonObject.data(): JsonObject = getAsJsonObject("data")

    private val minimalWhitePng: ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
        0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(), 0x89.toByte(),
        0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54,
        0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00, 0x05, 0x00, 0x01,
        0x0D, 0x0A, 0x2D, 0xB4.toByte(),
        0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
        0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
    )
}
