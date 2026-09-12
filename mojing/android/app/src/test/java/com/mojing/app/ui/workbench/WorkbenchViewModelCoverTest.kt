package com.mojing.app.ui.workbench

import com.mojing.app.data.SecureStorage
import com.mojing.app.data.local.dao.WorldTemplateDao
import com.mojing.app.data.local.entity.WorldTemplateEntity
import com.mojing.app.data.prefs.UiPreferencesRepository
import com.mojing.app.data.remote.BackendWorldsApi
import com.mojing.app.data.repository.ImageRepository
import com.mojing.app.domain.engine.LlmRetry
import com.mojing.app.domain.usecase.SaveWorldTemplatePackageUseCase
import com.mojing.app.domain.usecase.SmartImportUseCase
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestDispatcher
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
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchViewModelCoverTest {
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

    private fun createViewModel(templateDao: WorldTemplateDao): WorkbenchViewModel {
        val preferences = mockk<UiPreferencesRepository> {
            io.mockk.every { workbenchListLayout } returns flowOf("list")
        }
        return WorkbenchViewModel(
            legacyWorldMappingDao = io.mockk.mockk {
                io.mockk.every { observeAll() } returns kotlinx.coroutines.flow.flowOf(emptyList())
                io.mockk.coEvery { getByTemplateId(any()) } returns null
            },
            promoteWorldTemplate = io.mockk.mockk(relaxed = true),
            templateDao = templateDao,
            smartImportUseCase = mockk<SmartImportUseCase>(relaxed = true),
            secureStorage = mockk<SecureStorage>(relaxed = true),
            backendWorldsApi = mockk<Lazy<BackendWorldsApi>>(relaxed = true),
            imageRepository = mockk<ImageRepository>(relaxed = true),
            llmRetry = mockk<LlmRetry>(relaxed = true),
            uiPreferencesRepository = preferences,
            saveWorldTemplatePackage = mockk<SaveWorldTemplatePackageUseCase>(relaxed = true),
        )
    }

    @Test
    fun manualCoverReportsSuccessOnlyAfterRoomAcceptsTheExactTarget() = runTest(dispatcher) {
        val localFile = Files.createTempFile("manual-template-cover", ".jpg").toFile()
        val original = WorldTemplateEntity(id = 7L, templateId = "world", label = "测试世界", coverImagePath = "old.jpg")
        val updated = original.copy(coverImagePath = localFile.absolutePath)
        val templateDao = mockk<WorldTemplateDao> {
            coEvery { getAll() } returnsMany listOf(listOf(original), listOf(updated))
            coEvery { updateCover(7L, localFile.absolutePath, any()) } returns 1
        }
        val viewModel = createViewModel(templateDao)

        val message = viewModel.updateTemplateCover(7L, localFile.absolutePath)

        assertEquals("已更新封面", message)
        assertEquals(localFile.absolutePath, viewModel.templates.value.single().coverImagePath)
        assertTrue(localFile.exists())
    }

    @Test
    fun manualCoverRoomFailureDeletesTheUnclaimedFile() = runTest(dispatcher) {
        val localFile = Files.createTempFile("failed-manual-template-cover", ".jpg").toFile()
        val template = WorldTemplateEntity(id = 7L, templateId = "world", label = "测试世界", coverImagePath = "old.jpg")
        val templateDao = mockk<WorldTemplateDao> {
            coEvery { getAll() } returns listOf(template)
            coEvery { updateCover(7L, localFile.absolutePath, any()) } throws
                IllegalStateException("database unavailable")
        }
        val viewModel = createViewModel(templateDao)

        val message = viewModel.updateTemplateCover(7L, localFile.absolutePath)

        assertEquals("封面未能保存到本机，请重试。", message)
        assertFalse(localFile.exists())
    }
}
