package com.mojing.app.ui.character

import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.dao.EncyclopediaNameOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterExportCodecTest {
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
