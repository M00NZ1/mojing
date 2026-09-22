package com.mojing.app.ui.workbench

import com.mojing.app.data.SecureStorage
import com.mojing.app.data.local.dao.WorldTemplateDao
import com.mojing.app.data.local.entity.WorldLoreEntryEntity
import com.mojing.app.data.local.entity.WorldTemplateEntity
import com.mojing.app.data.remote.BackendWorldsApi
import com.mojing.app.data.repository.ImageRepository
import com.mojing.app.domain.engine.LlmRetry
import com.mojing.app.domain.usecase.SavedWorldTemplatePackage
import com.mojing.app.domain.usecase.SaveWorldTemplatePackageUseCase
import com.mojing.app.domain.usecase.SmartImportUseCase
import com.mojing.app.data.prefs.UiPreferencesRepository
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchViewModelWorldGenerationTest {
    private lateinit var dispatcher: TestDispatcher

    @Before
    fun setUp() {
        dispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun cancelWorldGenerationClearsBusyAndDoesNotSaveOrReport() = runTest(dispatcher) {
        val llmStarted = CompletableDeferred<Unit>()
        val llmRelease = CompletableDeferred<String>()
        val templateDao = mockk<WorldTemplateDao> {
            coEvery { getAll() } returns emptyList()
        }
        val llmRetry = mockk<LlmRetry> {
            coEvery { chatCompletionWithRetry(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                llmStarted.complete(Unit)
                llmRelease.await()
            }
        }
        val savePackage = mockk<SaveWorldTemplatePackageUseCase>(relaxed = true)
        val viewModel = createViewModel(templateDao, llmRetry, savePackage)
        val results = mutableListOf<String>()

        viewModel.generateWorldRemote("奇幻", "失落王国", "庄严", "", "", results::add)
        llmStarted.await()

        assertTrue(viewModel.generateBusy.value)
        assertTrue(viewModel.cancelWorldGeneration())
        awaitGenerationIdle(viewModel)
        advanceUntilIdle()

        assertFalse(viewModel.generateBusy.value)
        assertTrue(results.isEmpty())
        coVerify(exactly = 0) { savePackage.invoke(any(), any()) }
        coVerify(exactly = 1) { templateDao.getAll() }
        assertFalse(viewModel.cancelWorldGeneration())
    }

    @Test
    fun worldGenerationRemainsSingleFlightWhileFirstRequestIsActive() = runTest(dispatcher) {
        val llmStarted = CompletableDeferred<Unit>()
        val llmRelease = CompletableDeferred<String>()
        val templateDao = mockk<WorldTemplateDao> {
            coEvery { getAll() } returns emptyList()
        }
        val llmRetry = mockk<LlmRetry> {
            coEvery { chatCompletionWithRetry(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                llmStarted.complete(Unit)
                llmRelease.await()
            }
        }
        val viewModel = createViewModel(templateDao, llmRetry, mockk(relaxed = true))

        viewModel.generateWorldRemote("奇幻", "失落王国", "庄严", "", "", {})
        llmStarted.await()
        viewModel.generateWorldRemote("科幻", "太空站", "冷峻", "", "", {})

        assertTrue(viewModel.generateBusy.value)
        coVerify(exactly = 1) { llmRetry.chatCompletionWithRetry(any(), any(), any(), any(), any(), any(), any()) }
        assertTrue(viewModel.cancelWorldGeneration())
        awaitGenerationIdle(viewModel)
        advanceUntilIdle()
        assertFalse(viewModel.generateBusy.value)
    }

    @Test
    fun savingPhaseCannotBeCancelledAndReportsCompletion() = runTest(dispatcher) {
        val saveStarted = CompletableDeferred<Unit>()
        val saveRelease = CompletableDeferred<Unit>()
        val templateDao = mockk<WorldTemplateDao> {
            coEvery { getAll() } returns emptyList()
        }
        val llmRetry = mockk<LlmRetry> {
            coEvery { chatCompletionWithRetry(any(), any(), any(), any(), any(), any(), any()) } returns VALID_WORLD_JSON
        }
        val savePackage = mockk<SaveWorldTemplatePackageUseCase>()
        coEvery {
            savePackage.invoke(any<WorldTemplateEntity>(), any<List<WorldLoreEntryEntity>>())
        } coAnswers {
            val template = args[0] as WorldTemplateEntity
            saveStarted.complete(Unit)
            saveRelease.await()
            SavedWorldTemplatePackage(template.copy(id = 42L), loreCount = 3)
        }
        val viewModel = createViewModel(templateDao, llmRetry, savePackage)
        val results = mutableListOf<String>()

        viewModel.generateWorldRemote("奇幻", "失落王国", "庄严", "", "", results::add)
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { saveStarted.await() }
        }

        assertTrue(viewModel.generateBusy.value)
        assertTrue(viewModel.generateSaving.value)
        assertFalse(viewModel.cancelWorldGeneration())

        saveRelease.complete(Unit)
        awaitGenerationIdle(viewModel)
        advanceUntilIdle()

        assertFalse(viewModel.generateBusy.value)
        assertFalse(viewModel.generateSaving.value)
        assertEquals(1, results.size)
        assertTrue(results.single().contains("已写入本地「雾都」"))
        coVerify(exactly = 1) { savePackage.invoke(any(), match { it.size == 3 }) }
        coVerify(exactly = 2) { templateDao.getAll() }
    }

    private suspend fun awaitGenerationIdle(viewModel: WorkbenchViewModel) {
        // Generation runs on Dispatchers.IO. Keep the timeout on a real dispatcher so
        // runTest does not advance virtual time to the deadline before IO can finish.
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { viewModel.generateBusy.first { !it } }
        }
    }

    private fun createViewModel(
        templateDao: WorldTemplateDao,
        llmRetry: LlmRetry,
        savePackage: SaveWorldTemplatePackageUseCase,
    ): WorkbenchViewModel {
        val preferences = mockk<UiPreferencesRepository> {
            every { workbenchListLayout } returns flowOf("list")
        }
        val secureStorage = mockk<SecureStorage> {
            every { publicApiKey } returns "test-key"
            every { publicBaseUrl } returns "https://example.test"
            every { publicModel } returns "test-model"
        }
        return WorkbenchViewModel(
            legacyWorldMappingDao = io.mockk.mockk {
                io.mockk.every { observeAll() } returns kotlinx.coroutines.flow.flowOf(emptyList())
                io.mockk.coEvery { getByTemplateId(any()) } returns null
            },
            promoteWorldTemplate = io.mockk.mockk(relaxed = true),
            templateDao = templateDao,
            smartImportUseCase = mockk<SmartImportUseCase>(relaxed = true),
            secureStorage = secureStorage,
            backendWorldsApi = mockk<Lazy<BackendWorldsApi>>(relaxed = true),
            imageRepository = mockk<ImageRepository>(relaxed = true),
            llmRetry = llmRetry,
            uiPreferencesRepository = preferences,
            saveWorldTemplatePackage = savePackage,
            deleteWorldTemplateUseCase = mockk(relaxed = true),
        )
    }

    private companion object {
        val VALID_WORLD_JSON = """
            {
              "template": {
                "template_id": "mist-city",
                "label": "雾都",
                "world_prompt": "雾潮中的城市邦国",
                "suggested_choices": ["进入城门", "调查雾潮"]
              },
              "lore_entries": [
                {"title":"雾潮","content":"每天午夜漫过旧城区"},
                {"title":"城门","content":"由三家行会轮流守卫"},
                {"title":"灯塔","content":"唯一能穿透浓雾的地标"}
              ]
            }
        """.trimIndent()
    }
}
