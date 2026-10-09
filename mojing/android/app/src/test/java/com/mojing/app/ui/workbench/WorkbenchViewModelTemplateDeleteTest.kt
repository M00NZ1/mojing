package com.mojing.app.ui.workbench

import com.mojing.app.data.SecureStorage
import com.mojing.app.data.local.dao.WorldTemplateDao
import com.mojing.app.data.local.dao.WorldTemplateLibraryItem
import com.mojing.app.data.remote.BackendWorldsApi
import com.mojing.app.data.prefs.UiPreferencesRepository
import com.mojing.app.data.repository.ImageRepository
import com.mojing.app.domain.engine.LlmRetry
import com.mojing.app.domain.usecase.DeleteWorldTemplateResult
import com.mojing.app.domain.usecase.DeleteWorldTemplateUseCase
import com.mojing.app.domain.usecase.PromoteWorldTemplateUseCase
import com.mojing.app.domain.usecase.SaveWorldTemplatePackageUseCase
import com.mojing.app.domain.usecase.SmartImportUseCase
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchViewModelTemplateDeleteTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun repeatedSubmissionForSameIdRunsOnceAndPublishesResult() = runTest(dispatcher) {
        val release = CompletableDeferred<Unit>()
        val templateDao = mockk<WorldTemplateDao> {
            coEvery { getLibraryPage(any(), any(), any(), any(), any(), any()) } returns emptyList()
        }
        val delete = mockk<DeleteWorldTemplateUseCase>()
        coEvery { delete(7L) } coAnswers {
            release.await()
            DeleteWorldTemplateResult.Deleted
        }
        val viewModel = createViewModel(templateDao, delete)

        viewModel.deleteTemplate(7L)
        assertEquals(TemplateDeleteState(templateId = 7L, isDeleting = true), viewModel.deleteTemplateState.value)
        viewModel.deleteTemplate(7L)
        coVerify(exactly = 1) { delete(7L) }

        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(TemplateDeleteState(templateId = 7L, result = DeleteWorldTemplateResult.Deleted), viewModel.deleteTemplateState.value)
    }

    @Test
    fun failedDeleteCanRetryWithoutReportingSuccessEarly() = runTest(dispatcher) {
        val templateDao = mockk<WorldTemplateDao> {
            coEvery { getLibraryPage(any(), any(), any(), any(), any(), any()) } returns emptyList()
        }
        val delete = mockk<DeleteWorldTemplateUseCase>()
        coEvery { delete(7L) } returnsMany listOf(
            DeleteWorldTemplateResult.Failed(IllegalStateException("db unavailable")),
            DeleteWorldTemplateResult.Deleted,
        )
        val viewModel = createViewModel(templateDao, delete)

        viewModel.deleteTemplate(7L)
        advanceUntilIdle()
        assertEquals(DeleteWorldTemplateResult.Failed::class, viewModel.deleteTemplateState.value.result!!::class)

        viewModel.deleteTemplate(7L)
        advanceUntilIdle()
        assertEquals(TemplateDeleteState(templateId = 7L, result = DeleteWorldTemplateResult.Deleted), viewModel.deleteTemplateState.value)
        coVerify(exactly = 2) { delete(7L) }
    }

    @Test
    fun deletingLastTemplateOnSecondPageFallsBackAndOldDeleteCannotUseResetCursor() = runTest(dispatcher) {
        val page = List(25) { templateItem(it.toLong() + 1) }
        val secondPage = listOf(templateItem(25L))
        val previousPage = List(24) { templateItem(it.toLong() + 1) }
        val templateDao = mockk<WorldTemplateDao> {
            coEvery { getLibraryPage(any(), any(), any(), any(), any(), any()) } returnsMany
                listOf(page, secondPage, emptyList(), previousPage)
        }
        val delete = mockk<DeleteWorldTemplateUseCase>()
        coEvery { delete(25L) } returns DeleteWorldTemplateResult.Deleted
        val viewModel = createViewModel(templateDao, delete)
        advanceUntilIdle()
        viewModel.nextPage()
        advanceUntilIdle()
        viewModel.deleteTemplate(25L)
        advanceUntilIdle()
        assertEquals(0, viewModel.library.value.pageIndex)
        assertEquals(24, viewModel.library.value.items.size)
    }

    @Test
    fun deleteCompletionAfterSearchRoundTripDoesNotReloadOldPage() = runTest(dispatcher) {
        val release = CompletableDeferred<DeleteWorldTemplateResult>()
        val page = List(25) { templateItem(it.toLong() + 1) }
        val templateDao = mockk<WorldTemplateDao> {
            coEvery { getLibraryPage(any(), any(), any(), any(), any(), any()) } returns page
        }
        val delete = mockk<DeleteWorldTemplateUseCase>()
        coEvery { delete(25L) } coAnswers { release.await() }
        val viewModel = createViewModel(templateDao, delete)
        advanceUntilIdle()
        viewModel.nextPage()
        advanceUntilIdle()
        viewModel.deleteTemplate(25L)
        viewModel.setSearchQuery("B")
        advanceUntilIdle()
        viewModel.setSearchQuery("")
        advanceUntilIdle()
        release.complete(DeleteWorldTemplateResult.Deleted)
        advanceUntilIdle()
        assertEquals("", viewModel.library.value.query)
        assertEquals(0, viewModel.library.value.pageIndex)
    }

    @Test
    fun oldDeleteCompletesWhileNewQueryLoadIsSuspendedWithoutUsingOldCursor() = runTest(dispatcher) {
        val deleteRelease = CompletableDeferred<DeleteWorldTemplateResult>()
        val queryRelease = CompletableDeferred<Unit>()
        val page = List(25) { templateItem(it.toLong() + 1) }
        val templateDao = mockk<WorldTemplateDao> {
            coEvery { getLibraryPage(any(), any(), any(), any(), any(), any()) } coAnswers {
                if (firstArg<String>() == "B") queryRelease.await()
                page
            }
        }
        val delete = mockk<DeleteWorldTemplateUseCase>()
        coEvery { delete(25L) } coAnswers { deleteRelease.await() }
        val viewModel = createViewModel(templateDao, delete)
        advanceUntilIdle()
        viewModel.nextPage()
        advanceUntilIdle()
        viewModel.deleteTemplate(25L)
        viewModel.setSearchQuery("B")
        deleteRelease.complete(DeleteWorldTemplateResult.Deleted)
        advanceUntilIdle()
        assertEquals("B", viewModel.library.value.query)
        assertEquals(0, viewModel.library.value.pageIndex)
        queryRelease.complete(Unit)
        advanceUntilIdle()
        assertEquals("B", viewModel.library.value.query)
        assertEquals(0, viewModel.library.value.pageIndex)
    }

    @Test
    fun deletingTemplateWithRemainingSecondPageRowsKeepsPage() = runTest(dispatcher) {
        val page = List(25) { templateItem(it.toLong() + 1) }
        val secondPage = listOf(templateItem(25L), templateItem(26L))
        val refreshedSecondPage = listOf(templateItem(26L))
        val templateDao = mockk<WorldTemplateDao> {
            coEvery { getLibraryPage(any(), any(), any(), any(), any(), any()) } returnsMany
                listOf(page, secondPage, refreshedSecondPage)
        }
        val delete = mockk<DeleteWorldTemplateUseCase>()
        coEvery { delete(25L) } returns DeleteWorldTemplateResult.Deleted
        val viewModel = createViewModel(templateDao, delete)
        advanceUntilIdle()
        viewModel.nextPage()
        advanceUntilIdle()
        viewModel.deleteTemplate(25L)
        advanceUntilIdle()
        assertEquals(1, viewModel.library.value.pageIndex)
        assertEquals(26L, viewModel.library.value.items.single().id)
    }

    private fun createViewModel(templateDao: WorldTemplateDao, delete: DeleteWorldTemplateUseCase) = WorkbenchViewModel(
        templateDao = templateDao,
        legacyWorldMappingDao = mockk {
            every { observeAll() } returns flowOf(emptyList())
        },
        promoteWorldTemplate = mockk<PromoteWorldTemplateUseCase>(relaxed = true),
        smartImportUseCase = mockk<SmartImportUseCase>(relaxed = true),
        secureStorage = mockk<SecureStorage>(relaxed = true),
        backendWorldsApi = mockk<Lazy<BackendWorldsApi>>(relaxed = true),
        imageRepository = mockk<ImageRepository>(relaxed = true),
        llmRetry = mockk<LlmRetry>(relaxed = true),
        uiPreferencesRepository = mockk<UiPreferencesRepository> { every { workbenchListLayout } returns flowOf("list") },
        saveWorldTemplatePackage = mockk<SaveWorldTemplatePackageUseCase>(relaxed = true),
        deleteWorldTemplateUseCase = delete,
    )

    private fun templateItem(id: Long) = WorldTemplateLibraryItem(
        id = id, templateId = "template-$id", label = "模板$id", category = "其他",
        summary = "", coverImagePath = "", pinnedAt = 0L, updatedAt = id,
    )
}
