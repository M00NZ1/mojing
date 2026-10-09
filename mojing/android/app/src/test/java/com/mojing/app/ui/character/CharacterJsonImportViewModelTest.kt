package com.mojing.app.ui.character

import com.mojing.app.data.local.dao.CharacterDao
import com.mojing.app.data.local.dao.EncyclopediaDao
import com.mojing.app.data.local.dao.EncyclopediaEntryDao
import com.mojing.app.data.prefs.UiPreferencesRepository
import com.mojing.app.domain.usecase.CreateSessionUseCase
import com.mojing.app.domain.usecase.DeleteCharacterUseCase
import com.mojing.app.domain.usecase.ImportCharacterUseCase
import com.mojing.app.domain.usecase.SaveCharacterBindingUseCase
import com.mojing.app.domain.usecase.SmartImportUseCase
import com.google.gson.JsonParser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CharacterJsonImportViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private data class Subject(
        val viewModel: CharacterListViewModel,
        val importer: ImportCharacterUseCase,
        val smartImport: SmartImportUseCase,
    )

    private fun subject(): Subject {
        val importer = mockk<ImportCharacterUseCase>()
        coEvery { importer.importPortable(any()) } returns 42L
        val smartImport = mockk<SmartImportUseCase>(relaxed = true)
        return Subject(
            viewModel = CharacterListViewModel(
                characterDao = mockk<CharacterDao>(relaxed = true),
                encyclopediaDao = mockk<EncyclopediaDao>(relaxed = true),
                entryDao = mockk<EncyclopediaEntryDao>(relaxed = true),
                deleteCharacter = mockk<DeleteCharacterUseCase>(relaxed = true),
                saveCharacterBinding = mockk<SaveCharacterBindingUseCase>(relaxed = true),
                importCharacter = importer,
                smartImportUseCase = smartImport,
                createSessionUseCase = mockk<CreateSessionUseCase>(relaxed = true),
                uiPreferencesRepository = mockk<UiPreferencesRepository> {
                    every { characterListLayout } returns flowOf("list")
                },
            ),
            importer = importer,
            smartImport = smartImport,
        )
    }

    @Test
    fun v2JsonPersistsAllCardStructureUsesDocumentBasenameAndDoesNotInjectNotes() = runTest(dispatcher) {
        val subject = subject()
        val captured = slot<com.mojing.app.domain.util.CharacterPortableCodec.ParsedPortable>()
        coEvery { subject.importer.importPortable(capture(captured)) } returns 42L
        val json = """
            {
              "spec":"chara_card_v2", "spec_version":"2.0",
              "data": {
                "name":"完整角色", "description":"描述", "personality":"性格",
                "scenario":"场景", "first_mes":"首条开场", "mes_example":"示例",
                "system_prompt":"系统", "post_history_instructions":"后置",
                "creator_notes":"只保留在卡结构", "extensions":{"unknown":null},
                "character_book":{"entries":[{"keys":["秘密"],"content":"书内容"}]}
              }
            }
        """.trimIndent()

        val result = subject.viewModel.importFromDocument(
            json.toByteArray(),
            "content://downloads/nested/完整角色.json",
        )

        assertEquals(listOf(42L), result.importedIds)
        assertTrue(captured.captured.personaPrompt.contains("场景"))
        assertTrue(captured.captured.personaPrompt.contains("首条开场"))
        assertTrue(captured.captured.personaPrompt.contains("后置"))
        assertFalse(captured.captured.personaPrompt.contains("只保留在卡结构"))
        assertEquals("完整角色.json", captured.captured.profile!!.sourceFilename)
        val stored = JsonParser.parseString(checkNotNull(captured.captured.profile).characterCardJson).asJsonObject
        assertTrue(stored.getAsJsonObject("data").getAsJsonObject("extensions").get("unknown").isJsonNull)
        assertEquals("只保留在卡结构", stored.getAsJsonObject("data").get("creator_notes").asString)
        assertTrue(stored.getAsJsonObject("data").has("character_book"))
        coVerify(exactly = 0) { subject.smartImport.parseToStructuredJson(any(), any()) }
    }

    @Test
    fun topLevelLegacyCardUsesCompleteConverterAndBasename() = runTest(dispatcher) {
        val subject = subject()
        val captured = slot<com.mojing.app.domain.util.CharacterPortableCodec.ParsedPortable>()
        coEvery { subject.importer.importPortable(capture(captured)) } returns 42L
        val json = """{"name":"旧格式","description":"描述","first_mes":"开场","extensions":{"x":null}}"""

        subject.viewModel.importFromDocument(json.toByteArray(), "C:\\cards\\legacy-card.json")

        assertEquals("旧格式", captured.captured.name)
        assertTrue(captured.captured.personaPrompt.contains("开场"))
        assertEquals("legacy-card.json", captured.captured.profile!!.sourceFilename)
        assertTrue(JsonParser.parseString(checkNotNull(captured.captured.profile).characterCardJson).asJsonObject.has("extensions"))
        coVerify(exactly = 0) { subject.smartImport.parseToStructuredJson(any(), any()) }
    }

    @Test
    fun portableJsonKeepsEmbeddedSourceFilenameAndBypassesSmartImport() = runTest(dispatcher) {
        val subject = subject()
        val captured = slot<com.mojing.app.domain.util.CharacterPortableCodec.ParsedPortable>()
        coEvery { subject.importer.importPortable(capture(captured)) } returns 42L
        val json = """
            {"kind":"mojing_character_portable","version":1,"name":"便携角色",
             "persona_prompt":"便携正文","profile":{
               "source_filename":"original-source.png","raw_persona_text":"原始文本",
               "character_card_json":{"extensions":{"portable":null}}
             }}
        """.trimIndent()

        subject.viewModel.importFromDocument(json.toByteArray(), "renamed-portable.json")

        assertEquals("便携角色", captured.captured.name)
        assertEquals("便携正文", captured.captured.personaPrompt)
        assertEquals("original-source.png", captured.captured.profile!!.sourceFilename)
        assertTrue(JsonParser.parseString(checkNotNull(captured.captured.profile).characterCardJson).asJsonObject
            .getAsJsonObject("extensions").get("portable").isJsonNull)
        coVerify(exactly = 0) { subject.smartImport.parseToStructuredJson(any(), any()) }
    }
    @Test fun knownNullTextJsonAndPngUseTheSameLocalImportWithoutSmartFallback() = runTest(dispatcher) {
        val root = JsonParser.parseString("""{"spec":"chara_card_v2","spec_version":"2.0","data":{"name":"空字段角色","description":null,"personality":null,"scenario":"场景保留","first_mes":null,"mes_example":null,"system_prompt":null,"post_history_instructions":null,"extensions":{"unknown":null}}}""").asJsonObject
        val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO2W5Z8AAAAASUVORK5CYII=")
        for ((bytes, filename) in listOf(root.toString().toByteArray() to "null-card.json",
            com.mojing.app.domain.util.CharacterCardPngCodec.embedCharaJson(png, root) to "null-card.png")) {
            val subject = subject()
            val captured = slot<com.mojing.app.domain.util.CharacterPortableCodec.ParsedPortable>()
            coEvery { subject.importer.importPortable(capture(captured)) } returns 42L
            val result = subject.viewModel.importFromDocument(bytes, filename)
            assertFalse(result.hasFailure)
            assertEquals(listOf(42L), result.importedIds)
            assertEquals("【场景】场景保留", captured.captured.personaPrompt)
            assertEquals(root, JsonParser.parseString(captured.captured.profile!!.characterCardJson))
            assertEquals(filename, captured.captured.profile!!.sourceFilename)
            coVerify(exactly = 1) { subject.importer.importPortable(any()) }
            coVerify(exactly = 0) { subject.smartImport.parseToStructuredJson(any(), any()) }
        }
    }

}
