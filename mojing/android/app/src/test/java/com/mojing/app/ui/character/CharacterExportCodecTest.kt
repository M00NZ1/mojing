package com.mojing.app.ui.character

import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.dao.EncyclopediaNameOption
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.StringWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterExportCodecTest {
    @Test
    fun streamedCharactersKeepTheVersionTwoEnvelopeAndPortableFields() {
        val characters = (1L..33L).map { id ->
            CharacterEntity(id = id, name = "角色$id", personaPrompt = "长人设<$id>",
                apiKey = "secret-$id", boundEncyclopediaId = if (id % 2L == 0L) 8L else 0L)
        }
        val output = StringWriter()
        val writer = Gson().newJsonWriter(output)
        CharacterExportCodec.begin(writer)
        characters.take(32).forEach { CharacterExportCodec.writeCharacter(writer, it, if (it.boundEncyclopediaId == 8L) "世界A" else "") }
        characters.drop(32).forEach { CharacterExportCodec.writeCharacter(writer, it, if (it.boundEncyclopediaId == 8L) "世界A" else "") }
        CharacterExportCodec.end(writer)
        writer.flush()

        val root = JsonParser.parseString(output.toString()).asJsonObject
        assertEquals(2, root.get("version").asInt)
        assertEquals("characters", root.get("type").asString)
        assertEquals(33, root.getAsJsonArray("data").size())
        assertEquals("世界A", root.getAsJsonArray("data")[1].asJsonObject.get("boundEncyclopediaName").asString)
        assertEquals("", root.getAsJsonArray("data")[0].asJsonObject.get("boundEncyclopediaName").asString)
        assertFalse(output.toString().contains("secret-"))
        assertEquals(33, CharacterExportCodec.fromJson(output.toString()).size)
    }

    @Test
    fun exportOmitsAllApiKeysAndImportRestoresSafeConfiguration() {
        val character = CharacterEntity(
            id = 3,
            name = "测试角色",
            personaPrompt = "谨慎而博学",
            apiKey = "chat-secret",
            apiBaseUrl = "https://api.example.test/v1",
            modelName = "model-x",
            temperature = 0.4f,
            maxTokens = 2048,
            voiceApiKey = "voice-secret",
            imageGenApiKey = "image-secret",
            voiceProvider = "fish",
            voiceApiBaseUrl = "https://voice.example.test",
            voiceModel = "voice-x",
            imageGenEnabled = true,
            imageGenBaseUrl = "https://image.example.test",
            imageGenModel = "image-x",
            thinkMaxEnabled = true,
            thinkMaxModelName = "think-x",
            boundEncyclopediaId = 9,
        )
        val encyclopedia = EncyclopediaNameOption(id = 9, name = "修仙世界")

        val json = CharacterExportCodec.toJson(listOf(character), listOf(encyclopedia))
        val imported = CharacterExportCodec.fromJson(json).single()
        val restored = imported.toEntity(CharacterExportCodec.resolveBoundEncyclopediaId(imported.boundEncyclopediaName, listOf(encyclopedia)))

        assertFalse(json.contains("chat-secret"))
        assertFalse(json.contains("voice-secret"))
        assertFalse(json.contains("image-secret"))
        assertFalse(json.contains("apiKey"))
        assertEquals("model-x", restored.modelName)
        assertEquals(2048, restored.maxTokens)
        assertEquals("https://voice.example.test", restored.voiceApiBaseUrl)
        assertTrue(restored.imageGenEnabled)
        assertEquals(9L, restored.boundEncyclopediaId)
        assertEquals("", restored.apiKey)
        assertEquals("", restored.voiceApiKey)
        assertEquals("", restored.imageGenApiKey)
    }

    @Test
    fun resolvesBindingOnlyForOneExactEncyclopediaName() {
        val encyclopedias = listOf(
            EncyclopediaNameOption(id = 1, name = "修仙世界"),
            EncyclopediaNameOption(id = 2, name = "科幻世界"),
        )

        assertEquals(1L, CharacterExportCodec.resolveBoundEncyclopediaId(" 修仙世界 ", encyclopedias))
        assertEquals(0L, CharacterExportCodec.resolveBoundEncyclopediaId("不存在", encyclopedias))
        assertEquals(
            0L,
            CharacterExportCodec.resolveBoundEncyclopediaId(
                "修仙世界",
                encyclopedias + EncyclopediaNameOption(id = 3, name = "修仙世界"),
            ),
        )
    }
}
