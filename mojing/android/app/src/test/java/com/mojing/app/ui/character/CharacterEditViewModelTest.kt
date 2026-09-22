package com.mojing.app.ui.character

import android.content.Context
import com.mojing.app.data.SecureStorage
import com.mojing.app.data.local.dao.CharacterDao
import com.mojing.app.data.local.dao.CharacterProfileDao
import com.mojing.app.data.local.dao.EncyclopediaDao
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.CharacterProfileEntity
import com.mojing.app.data.local.entity.GenerationTaskEntity
import com.mojing.app.data.remote.BackendSystemProbeApi
import com.mojing.app.data.remote.LlmApiService
import com.mojing.app.data.repository.ImageRepository
import com.mojing.app.domain.engine.LlmRetry
import com.mojing.app.domain.generation.GenerationQueueProcessor
import com.mojing.app.domain.usecase.SaveCharacterBindingUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CharacterEditViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private data class TestSubject(
        val viewModel: CharacterEditViewModel,
        val saveCharacterBinding: SaveCharacterBindingUseCase,
        val profileDao: CharacterProfileDao,
    )

    private fun createSubject(
        characterDao: CharacterDao,
        profileDao: CharacterProfileDao = mockk(relaxed = true),
        saveCharacterBinding: SaveCharacterBindingUseCase = mockk(relaxed = true),
        activeTasks: Flow<List<GenerationTaskEntity>> = flowOf(emptyList()),
    ): TestSubject {
        val encyclopediaDao = mockk<EncyclopediaDao> {
            coEvery { getAll() } returns emptyList()
        }
        val queue = mockk<GenerationQueueProcessor>(relaxed = true)
        every { queue.observeActiveForCharacter(any()) } returns activeTasks
        val secureStorage = mockk<SecureStorage>(relaxed = true)
        every { secureStorage.publicApiKey } returns ""
        return TestSubject(
            viewModel = CharacterEditViewModel(
                appContext = mockk<Context>(relaxed = true),
                characterDao = characterDao,
                characterProfileDao = profileDao,
                encyclopediaDao = encyclopediaDao,
                saveCharacterBinding = saveCharacterBinding,
                secureStorage = secureStorage,
                generationQueueProcessor = queue,
                llmRetry = mockk<LlmRetry>(relaxed = true),
                systemProbeApi = mockk<BackendSystemProbeApi>(relaxed = true),
                imageRepository = mockk<ImageRepository>(relaxed = true),
                llmApiService = mockk<LlmApiService>(relaxed = true),
            ),
            saveCharacterBinding = saveCharacterBinding,
            profileDao = profileDao,
        )
    }

    @Test
    fun existingCharacterTracksRealDraftChangesAndClearsAfterSave() = runTest(dispatcher) {
        val original = CharacterEntity(id = 7L, name = "林云", personaPrompt = "沉静")
        val persisted = original.copy(name = "林云舟")
        val dao = mockk<CharacterDao> {
            coEvery { getById(7L) } returnsMany listOf(original, persisted)
        }
        val profileDao = mockk<CharacterProfileDao> {
            coEvery { getByCharacter(7L) } returns null
            coEvery { upsert(any()) } returns 1L
        }
        val save = mockk<SaveCharacterBindingUseCase> {
            coEvery { this@mockk.invoke(any()) } returns 7L
        }
        val viewModel = createSubject(dao, profileDao, save).viewModel

        viewModel.load(7L)
        assertTrue(viewModel.state.value.isPersisted)
        assertFalse(viewModel.state.value.isDirty)

        viewModel.updateName("林云舟")
        assertTrue(viewModel.state.value.isDirty)
        viewModel.updateName("林云")
        assertFalse(viewModel.state.value.isDirty)

        viewModel.updateName("林云舟")
        var exits = 0
        viewModel.save(7L) { exits++ }
        assertEquals(1, exits)
        assertTrue(viewModel.state.value.isPersisted)
        assertFalse(viewModel.state.value.isDirty)
    }

    @Test
    fun missingExistingCharacterShowsLoadErrorInsteadOfOpeningANewDraft() = runTest(dispatcher) {
        val dao = mockk<CharacterDao> {
            coEvery { getById(9L) } returns null
        }
        val viewModel = createSubject(dao).viewModel

        viewModel.load(9L)

        assertTrue(viewModel.state.value.isLoaded)
        assertFalse(viewModel.state.value.isPersisted)
        assertFalse(viewModel.state.value.isDirty)
        assertNotNull(viewModel.state.value.loadError)
    }

    @Test
    fun samplingDraftKeepsIntermediateTextAndSavesParsedNumbers() = runTest(dispatcher) {
        var stored = CharacterEntity(id = 7L, name = "林云")
        val dao = mockk<CharacterDao> { coEvery { getById(7L) } answers { stored } }
        val save = mockk<SaveCharacterBindingUseCase> {
            coEvery { this@mockk.invoke(any()) } answers { stored = firstArg(); 7L }
        }
        val vm = createSubject(dao, saveCharacterBinding = save).viewModel
        vm.load(7L)
        vm.updateTemperature("")
        assertEquals("", vm.state.value.temperature)
        assertTrue(vm.state.value.isDirty)
        var exited = false
        vm.save(7L) { exited = true }
        assertFalse(exited)
        coVerify(exactly = 0) { save(any()) }
        vm.updateTemperature("0.")
        assertEquals("0.", vm.state.value.temperature)
        vm.updateTemperature("0.75")
        vm.updatePresencePenalty("-")
        assertEquals("-", vm.state.value.presencePenalty)
        vm.updatePresencePenalty("-0.25")
        vm.updateFrequencyPenalty("0.125")
        vm.updateTopP("0.95")
        vm.updateMaxTokens("2048")
        vm.save(7L)

        assertEquals(0.75f, stored.temperature)
        assertEquals(-0.25f, stored.presencePenalty)
        assertEquals(0.125f, stored.frequencyPenalty)
        assertEquals(0.95f, stored.topP)
        assertEquals(2048, stored.maxTokens)
        assertFalse(vm.state.value.isDirty)
    }

    @Test
    fun invalidSamplingValuesBlockSaveAndExportWithoutDiscardingDraft() = runTest(dispatcher) {
        val dao = mockk<CharacterDao> {
            coEvery { getById(7L) } returns CharacterEntity(id = 7L, name = "林云")
        }
        val subject = createSubject(dao)
        val vm = subject.viewModel
        vm.load(7L)
        for (input in listOf("NaN", "Infinity", "1e999", "-", "")) {
            vm.updateTemperature(input)
            vm.save(7L)
            assertEquals(input, vm.state.value.temperature)
            assertNotNull(vm.state.value.samplingError())
        }
        var exportFinished = false
        vm.buildTavernPngExport(7L) { result -> assertEquals(null, result); exportFinished = true }
        assertTrue(exportFinished)
        assertTrue(vm.state.value.exportMessage.orEmpty().contains("温度"))
        vm.updateTemperature("0.9")
        for (input in listOf("0", "-1", "1.5", "99999999999999")) {
            vm.updateMaxTokens(input)
            vm.save(7L)
            assertNotNull(vm.state.value.samplingError())
        }
        coVerify(exactly = 0) { subject.saveCharacterBinding(any()) }
        assertTrue(vm.state.value.isDirty)
    }

    @Test
    fun newCharacterBecomesPersistedAfterFirstSave() = runTest(dispatcher) {
        val persisted = CharacterEntity(id = 11L, name = "初雪")
        val dao = mockk<CharacterDao> {
            coEvery { getById(11L) } returns persisted
        }
        val profileDao = mockk<CharacterProfileDao> {
            coEvery { getByCharacter(11L) } returns null
            coEvery { upsert(any()) } returns 1L
        }
        val save = mockk<SaveCharacterBindingUseCase> {
            coEvery { this@mockk.invoke(any()) } returns 11L
        }
        val viewModel = createSubject(dao, profileDao, save).viewModel

        viewModel.load(0L)
        viewModel.updateName("初雪")
        viewModel.save(0L)

        assertTrue(viewModel.state.value.isPersisted)
        assertFalse(viewModel.state.value.isDirty)
        assertEquals("初雪", viewModel.state.value.name)
    }

    @Test
    fun saveKeepsDraftChangesMadeWhilePersistenceIsInFlight() = runTest(dispatcher) {
        val original = CharacterEntity(id = 7L, name = "林云")
        val persisted = original.copy(name = "林云舟")
        val saveRelease = CompletableDeferred<Unit>()
        val dao = mockk<CharacterDao> {
            coEvery { getById(7L) } returnsMany listOf(original, persisted)
        }
        val profileDao = mockk<CharacterProfileDao> {
            coEvery { getByCharacter(7L) } returns null
            coEvery { upsert(any()) } returns 1L
        }
        val save = mockk<SaveCharacterBindingUseCase> {
            coEvery { this@mockk.invoke(any()) } coAnswers {
                saveRelease.await()
                7L
            }
        }
        val viewModel = createSubject(dao, profileDao, save).viewModel

        viewModel.load(7L)
        viewModel.updateName("林云舟")
        var exited = false
        viewModel.save(7L) { exited = true }
        assertTrue(viewModel.state.value.isSaving)

        viewModel.updateName("林云舟·续")
        saveRelease.complete(Unit)
        advanceUntilIdle()

        assertFalse(exited)
        assertEquals("林云舟·续", viewModel.state.value.name)
        assertTrue(viewModel.state.value.isPersisted)
        assertTrue(viewModel.state.value.isDirty)
    }

    @Test
    fun clearingExtendedJsonPersistsAnEmptyObject() = runTest(dispatcher) {
        val original = CharacterEntity(id = 7L, name = "林云")
        val dao = mockk<CharacterDao> {
            coEvery { getById(7L) } returnsMany listOf(original, original)
        }
        val profileDao = mockk<CharacterProfileDao> {
            coEvery { getByCharacter(7L) } returns CharacterProfileEntity(
                id = 3L,
                characterId = 7L,
                characterCardJson = "{\"title\":\"旧设定\"}",
            )
            coEvery { upsert(any()) } returns 3L
        }
        val save = mockk<SaveCharacterBindingUseCase> {
            coEvery { this@mockk.invoke(any()) } returns 7L
        }
        val viewModel = createSubject(dao, profileDao, save).viewModel

        viewModel.load(7L)
        viewModel.updateCharacterCardJsonRaw("   ")
        viewModel.save(7L)

        coVerify {
            profileDao.upsert(match { it.characterId == 7L && it.characterCardJson == "{}" })
        }
        assertEquals("{}", viewModel.state.value.characterCardJsonRaw)
        assertFalse(viewModel.state.value.isDirty)
    }

    @Test
    fun aiCompletionKeepsOtherDraftChangesDirty() = runTest(dispatcher) {
        val original = CharacterEntity(id = 7L, name = "林云", personaPrompt = "旧人设")
        val completed = original.copy(personaPrompt = "补全后人设")
        val tasks = MutableStateFlow<List<GenerationTaskEntity>>(emptyList())
        val dao = mockk<CharacterDao> {
            coEvery { getById(7L) } returnsMany listOf(original, completed)
        }
        val viewModel = createSubject(dao, activeTasks = tasks).viewModel

        viewModel.load(7L)
        tasks.value = listOf(
            GenerationTaskEntity(
                taskKind = "character_persona_ai",
                title = "补全角色人设",
                status = "RUNNING",
                payloadJson = "{}",
                targetCharacterId = 7L,
            ),
        )
        advanceUntilIdle()
        viewModel.updateName("林云舟")
        tasks.value = emptyList()
        advanceUntilIdle()

        assertEquals("林云舟", viewModel.state.value.name)
        assertEquals("补全后人设", viewModel.state.value.personaPrompt)
        assertTrue(viewModel.state.value.isPersisted)
        assertTrue(viewModel.state.value.isDirty)
    }
    @Test
    fun savedNewCharacterRetainsIdWhenReadbackFailsAndRetryUpdatesIt() = runTest(dispatcher) {
        val dao = mockk<CharacterDao> { coEvery { getById(51) } throws IllegalStateException("read failed") }
        val save = mockk<SaveCharacterBindingUseCase>()
        val savedIds = mutableListOf<Long>()
        coEvery { save(any()) } coAnswers { savedIds += firstArg<CharacterEntity>().id; 51L }
        val vm = createSubject(dao, saveCharacterBinding = save).viewModel
        vm.load(0)
        vm.updateName("新角色")
        var exits = 0
        vm.save(0) { exits++ }
        assertEquals(0, exits)
        assertTrue(vm.state.value.isPersisted)
        assertTrue(vm.state.value.isDirty)
        assertNotNull(vm.state.value.saveError)
        assertEquals("新角色", vm.state.value.name)
        vm.updateName("改名角色")
        coEvery { dao.getById(51) } returns CharacterEntity(id = 51, name = "改名角色")
        vm.save(0) { exits++ }
        assertEquals(1, exits)
        assertEquals(listOf(0L, 51L), savedIds)
        assertFalse(vm.state.value.isDirty)
        assertEquals(null, vm.state.value.saveError)
    }

    @Test
    fun saveBlocksDuplicateCallsBeforeDispatcherStarts() = runTest(dispatcher) {
        Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val dao = mockk<CharacterDao> { coEvery { getById(7) } returns CharacterEntity(id = 7, name = "角色") }
        val save = mockk<SaveCharacterBindingUseCase> { coEvery { this@mockk.invoke(any()) } throws IllegalStateException("disk") }
        val vm = createSubject(dao, saveCharacterBinding = save).viewModel
        vm.load(7)
        advanceUntilIdle()
        vm.updateName("修改")
        vm.save(7)
        vm.save(7)
        assertTrue(vm.state.value.isSaving)
        advanceUntilIdle()
        coVerify(exactly = 1) { save(any()) }
        assertFalse(vm.state.value.isSaving)
        assertNotNull(vm.state.value.saveError)
        assertEquals("修改", vm.state.value.name)
    }

    @Test
    fun personaCompletionKeepsManualInputAndUsesGeneratedBaseline() = runTest(dispatcher) {
        val original = CharacterEntity(id = 7, name = "林云", personaPrompt = "旧人设")
        val task = GenerationTaskEntity(taskKind = "character_persona_ai", title = "补全", status = "RUNNING", payloadJson = "{}", targetCharacterId = 7)
        val tasks = MutableStateFlow(listOf(task))
        val dao = mockk<CharacterDao> { coEvery { getById(7) } returns original }
        val vm = createSubject(dao, activeTasks = tasks).viewModel
        vm.load(7)
        assertTrue(vm.state.value.isAiCompleting)
        vm.load(7)
        vm.updatePersonaPrompt("手动人设")
        coEvery { dao.getById(7) } returns original.copy(personaPrompt = "生成的人设")
        tasks.value = emptyList()
        advanceUntilIdle()
        assertEquals("手动人设", vm.state.value.personaPrompt)
        assertFalse(vm.state.value.isAiCompleting)
        assertTrue(vm.state.value.isDirty)
        vm.updatePersonaPrompt("生成的人设")
        assertFalse(vm.state.value.isDirty)
    }

    @Test
    fun personaReadFailureCanRetryAndOldRetryCannotReplaceNewCompletion() = runTest(dispatcher) {
        val original = CharacterEntity(id = 7, name = "林云", personaPrompt = "旧人设")
        val task = GenerationTaskEntity(taskKind = "character_persona_ai", title = "补全", status = "RUNNING", payloadJson = "{}", targetCharacterId = 7)
        val tasks = MutableStateFlow(listOf(task))
        val dao = mockk<CharacterDao> { coEvery { getById(7) } returns original }
        val subject = createSubject(dao, activeTasks = tasks)
        val vm = subject.viewModel
        vm.load(7)
        coEvery { dao.getById(7) } throws IllegalStateException("read")
        tasks.value = emptyList()
        advanceUntilIdle()
        assertFalse(vm.state.value.isAiCompleting)
        assertNotNull(vm.state.value.personaRefreshError)
        vm.save(7)
        coVerify(exactly = 0) { subject.saveCharacterBinding(any()) }
        vm.retryPersonaRefresh()
        assertNotNull(vm.state.value.personaRefreshError)
        val oldRead = CompletableDeferred<CharacterEntity>()
        coEvery { dao.getById(7) } coAnswers { oldRead.await() }
        vm.retryPersonaRefresh()
        vm.retryPersonaRefresh()
        assertTrue(vm.state.value.isRefreshingPersona)
        coVerify(exactly = 4) { dao.getById(7) }
        vm.updateName("手动改名")
        tasks.value = listOf(task)
        advanceUntilIdle()
        coEvery { dao.getById(7) } returns original.copy(personaPrompt = "最新人设")
        tasks.value = emptyList()
        advanceUntilIdle()
        oldRead.complete(original.copy(personaPrompt = "过时人设"))
        advanceUntilIdle()
        assertEquals("最新人设", vm.state.value.personaPrompt)
        assertEquals("手动改名", vm.state.value.name)
        assertEquals(null, vm.state.value.personaRefreshError)
        assertFalse(vm.state.value.isRefreshingPersona)
    }

}
