package com.mojing.app.ui.character

import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.prefs.UiPreferencesRepository
import com.mojing.app.domain.usecase.ImportCharacterUseCase
import com.mojing.app.domain.usecase.SmartImportException
import com.mojing.app.domain.usecase.SmartImportUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CharacterImportWorkflowTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val smartImport = mockk<SmartImportUseCase>()
    private val importer = mockk<ImportCharacterUseCase>()
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { Dispatchers.resetMain() }

    private fun viewModel() = CharacterListViewModel(
        characterDao = mockk(relaxed = true), encyclopediaDao = mockk(relaxed = true),
        entryDao = mockk(relaxed = true), deleteCharacter = mockk(), saveCharacterBinding = mockk(),
        importCharacter = importer, smartImportUseCase = smartImport, createSessionUseCase = mockk(),
        uiPreferencesRepository = mockk<UiPreferencesRepository> {
            every { characterListLayout } returns flowOf("list")
        },
    )

    @Test fun modelFailureReachesResultWithoutEnteringPersistence() = runTest(dispatcher) {
        coEvery { smartImport.parseToStructuredJson(any(), "character") } throws
            SmartImportException("模型服务认证失败，请检查 API Key")
        val result = viewModel().importFromDocument(("# mojing_character_portable v1\n{invalid}").toByteArray(), "role.txt")
        assertEquals("模型服务认证失败，请检查 API Key", result.message)
        assertTrue(result.hasFailure)
        assertTrue(result.importedIds.isEmpty())
        coVerify(exactly = 0) { importer.importStandard(any()) }
    }

    @Test fun invalidRecognizedPortableLimitShowsErrorWithoutAiOrPersistence() = runTest(dispatcher) {
        val json = """{"kind":"mojing_character_portable","version":1,"name":"合成角色","max_tokens":0}"""
        val result = viewModel().importFromDocument(json.toByteArray(), "role.json")
        assertTrue(result.hasFailure)
        assertEquals("角色最大 Token 必须是正整数", result.message)
        assertTrue(result.importedIds.isEmpty())
        coVerify(exactly = 0) { smartImport.parseToStructuredJson(any(), any()) }
        coVerify(exactly = 0) { importer.importPortable(any()) }
        coVerify(exactly = 0) { importer.importStandard(any()) }
    }

    @Test fun transactionFailureNeverPublishesPartialIds() = runTest(dispatcher) {
        val json = CharacterExportCodec.toJson(listOf(CharacterEntity(name = "灯塔")), emptyList())
        coEvery { smartImport.parseToStructuredJson(any(), "character") } returns json
        coEvery { importer.importStandard(any()) } throws IllegalStateException("disk full")
        val result = viewModel().importFromDocument(json.toByteArray(), "role.json")
        assertTrue(result.hasFailure)
        assertTrue(result.importedIds.isEmpty())
        assertFalse(result.message.contains("disk full"))
    }

    @Test fun cancellationRemainsCancellationAndDoesNotProduceAnErrorResult() = runTest(dispatcher) {
        coEvery { smartImport.parseToStructuredJson(any(), "character") } throws CancellationException("stop")
        try {
            viewModel().importFromDocument(("# mojing_character_portable v1\n{invalid}").toByteArray(), "role.txt")
            fail("Cancellation must propagate to the screen's job")
        } catch (_: CancellationException) { }
        coVerify(exactly = 0) { importer.importStandard(any()) }
    }
}
