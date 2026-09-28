package com.mojing.app.ui.workbench

import com.mojing.app.data.SecureStorage
import com.mojing.app.data.local.dao.WorldTemplateDao
import com.mojing.app.data.local.dao.WorldTemplateLibraryItem
import com.mojing.app.data.local.entity.WorldTemplateEntity
import com.mojing.app.data.prefs.UiPreferencesRepository
import com.mojing.app.data.remote.BackendWorldsApi
import com.mojing.app.data.repository.ImageRepository
import com.mojing.app.domain.engine.LlmRetry
import com.mojing.app.domain.usecase.DeleteWorldTemplateUseCase
import com.mojing.app.domain.usecase.SaveWorldTemplatePackageUseCase
import com.mojing.app.domain.usecase.SmartImportUseCase
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
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

    private fun WorldTemplateEntity.libraryItem() = WorldTemplateLibraryItem(
        id = id, templateId = templateId, label = label, category = category,
        summary = summary.take(96), coverImagePath = coverImagePath,
        pinnedAt = pinnedAt, updatedAt = updatedAt,
    )

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
            deleteWorldTemplateUseCase = mockk<DeleteWorldTemplateUseCase>(relaxed = true),
        )
    }

    @Test
    fun manualCoverReportsSuccessOnlyAfterRoomAcceptsTheExactTarget() = runTest(dispatcher) {
        val localFile = Files.createTempFile("manual-template-cover", ".jpg").toFile()
        val original = WorldTemplateEntity(id = 7L, templateId = "world", label = "测试世界", coverImagePath = "old.jpg")
        val updated = original.copy(coverImagePath = localFile.absolutePath)
        val templateDao = mockk<WorldTemplateDao> {
            coEvery { getLibraryPage(any(), any(), any(), any(), any(), any()) } returnsMany
                listOf(listOf(original.libraryItem()), listOf(updated.libraryItem()))
            coEvery { updateCover(7L, localFile.absolutePath, any()) } returns 1
        }
        val viewModel = createViewModel(templateDao)

        val message = viewModel.updateTemplateCover(7L, localFile.absolutePath)

        assertEquals("已更新封面", message)
        assertEquals(localFile.absolutePath, viewModel.library.value.items.single().coverImagePath)
        assertTrue(localFile.exists())
    }

    @Test
    fun manualCoverRoomFailureDeletesTheUnclaimedFile() = runTest(dispatcher) {
        val localFile = Files.createTempFile("failed-manual-template-cover", ".jpg").toFile()
        val template = WorldTemplateEntity(id = 7L, templateId = "world", label = "测试世界", coverImagePath = "old.jpg")
        val templateDao = mockk<WorldTemplateDao> {
            coEvery { getLibraryPage(any(), any(), any(), any(), any(), any()) } returns listOf(template.libraryItem())
            coEvery { updateCover(7L, localFile.absolutePath, any()) } throws
                IllegalStateException("database unavailable")
        }
        val viewModel = createViewModel(templateDao)

        val message = viewModel.updateTemplateCover(7L, localFile.absolutePath)

        assertEquals("封面未能保存到本机，请重试。", message)
        assertFalse(localFile.exists())
    }

    @Test
    fun failedPageKeepsCardsAndExportStillIncludesEveryTemplate() = runTest(dispatcher) {
        val all = (1L..26L).map { id -> WorldTemplateEntity(id = id, label = "模板$id") }
        val exportRows = (70L downTo 1L).map { id ->
            WorldTemplateEntity(id = id, label = "模板$id", worldPrompt = "设定$id")
        }
        var nextReads = 0
        val templateDao = mockk<WorldTemplateDao> {
            coEvery { getLibraryPage(any(), any(), any(), any(), any(), any()) } coAnswers {
                if (arg<Long?>(4) == null) all.take(25).map { it.libraryItem() }
                else {
                    nextReads++
                    if (nextReads == 1) throw IllegalStateException("read failed")
                    listOf(all.last().libraryItem())
                }
            }
            coEvery { getExportPage(any(), any(), any(), any(), 32) } coAnswers {
                exportRows.filter { row -> arg<Long?>(3)?.let { row.id < it } ?: true }.take(32)
            }
        }
        val viewModel = createViewModel(templateDao)
        assertEquals(24, viewModel.library.value.items.size)

        viewModel.nextPage()
        assertEquals(24, viewModel.library.value.items.size)
        assertEquals(0, viewModel.library.value.pageIndex)
        assertEquals("模板列表加载失败，请重试", viewModel.library.value.error)
        viewModel.retryPage()
        assertEquals(listOf(all.last().libraryItem()), viewModel.library.value.items)
        assertEquals(1, viewModel.library.value.pageIndex)

        val output = java.io.ByteArrayOutputStream()
        viewModel.exportJson(output)
        val exported = com.google.gson.JsonParser.parseString(output.toString("UTF-8")).asJsonObject
        assertEquals(1, exported.get("version").asInt)
        assertEquals("templates", exported.get("type").asString)
        val data = exported.getAsJsonArray("data")
        assertEquals(70, data.size())
        assertEquals("模板70", data[0].asJsonObject.get("label").asString)
        assertEquals("设定1", data[69].asJsonObject.get("worldPrompt").asString)
        coVerify(exactly = 3) { templateDao.getExportPage(any(), any(), any(), any(), 32) }
        coVerify(exactly = 0) { templateDao.getAll() }
    }
}
