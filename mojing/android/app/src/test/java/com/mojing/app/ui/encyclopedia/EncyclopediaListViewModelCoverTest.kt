package com.mojing.app.ui.encyclopedia

import com.mojing.app.data.SecureStorage
import com.mojing.app.data.local.dao.CharacterDao
import com.mojing.app.data.local.dao.EncyclopediaDao
import com.mojing.app.data.local.dao.EncyclopediaEntryDao
import com.mojing.app.data.local.dao.GenerationTaskDao
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.prefs.UiPreferencesRepository
import com.mojing.app.data.remote.BackendEncyclopediaApi
import com.mojing.app.data.repository.ImageRepository
import com.mojing.app.domain.usecase.SmartImportUseCase
import com.mojing.app.domain.usecase.SaveCharacterEntryUseCase
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
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
class EncyclopediaListViewModelCoverTest {
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

    private fun createViewModel(
        encyclopediaDao: EncyclopediaDao,
        backend: BackendEncyclopediaApi,
        imageRepository: ImageRepository,
        secureStorage: SecureStorage,
    ): EncyclopediaListViewModel {
        val uiPreferences = mockk<UiPreferencesRepository> {
            every { encyclopediaListLayout } returns flowOf("list")
        }
        val generationTaskDao = mockk<GenerationTaskDao> {
            every { observeActiveCount() } returns flowOf(0)
        }
        return EncyclopediaListViewModel(
            deleteWorld = io.mockk.mockk(relaxed = true),
            encyclopediaDao = encyclopediaDao,
            entryDao = mockk<EncyclopediaEntryDao>(relaxed = true),
            characterDao = mockk<CharacterDao>(relaxed = true),
            saveCharacterEntry = mockk<SaveCharacterEntryUseCase>(relaxed = true),
            smartImportUseCase = mockk<SmartImportUseCase>(relaxed = true),
            secureStorage = secureStorage,
            backendEncyclopediaApi = backend,
            imageRepository = imageRepository,
            uiPreferencesRepository = uiPreferences,
            generationTaskDao = generationTaskDao,
        )
    }

    @Test
    fun backendGeneratedImageDownloadFailureDoesNotGenerateAnotherImage() = runTest(dispatcher) {
        val encyclopedia = EncyclopediaEntity(id = 7L, name = "测试百科")
        val encyclopediaDao = mockk<EncyclopediaDao> {
            coEvery { getById(7L) } returns encyclopedia
            coEvery { getAll() } returns listOf(encyclopedia)
        }
        val backend = mockk<BackendEncyclopediaApi> {
            coEvery { previewEntryCoverImage(any(), any(), any(), any(), any()) } returns Result.success(
                JsonObject().apply { add("urls", JsonArray().apply { add("https://example.com/generated.jpg") }) },
            )
        }
        val imageRepository = mockk<ImageRepository> {
            coEvery { downloadAndSaveImageForCover(any()) } returns Result.failure(Exception("图片下载失败"))
        }
        val secureStorage = mockk<SecureStorage> {
            every { publicBaseUrl } returns "https://backend.example"
            every { publicApiKey } returns "public-key"
            every { imageBaseUrl } returns "https://images.example/v1"
            every { imageApiKey } returns "image-key"
            every { imageModel } returns "image-model"
        }
        val viewModel = createViewModel(encyclopediaDao, backend, imageRepository, secureStorage)
        val message = viewModel.generateEncyclopediaCoverAiInternal(7L)

        assertEquals("下载封面图片失败", message)
        coVerify(exactly = 1) { imageRepository.downloadAndSaveImageForCover(any()) }
        coVerify(exactly = 0) {
            imageRepository.generateAndSaveImageForCover(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun manualCoverChangeWinsOverLateGeneratedImageAndNewFileIsRemoved() = runTest(dispatcher) {
        val generatedFile = Files.createTempFile("late-cover", ".jpg").toFile()
        val atStart = EncyclopediaEntity(id = 7L, name = "测试百科", coverImagePath = "old.jpg")
        val afterManualChange = atStart.copy(coverImagePath = "manual.jpg")
        val encyclopediaDao = mockk<EncyclopediaDao> {
            coEvery { getById(7L) } returnsMany listOf(atStart, afterManualChange)
            coEvery { getAll() } returns listOf(atStart)
            coEvery { updateCoverIfUnchanged(7L, "old.jpg", generatedFile.absolutePath, any()) } returns 0
        }
        val backend = mockk<BackendEncyclopediaApi>(relaxed = true)
        val imageRepository = mockk<ImageRepository> {
            coEvery {
                generateAndSaveImageForCover(any(), any(), any(), any(), any(), any())
            } returns Result.success(generatedFile.absolutePath)
        }
        val secureStorage = mockk<SecureStorage> {
            every { publicBaseUrl } returns "https://gateway.example/v1"
            every { publicApiKey } returns "public-key"
            every { imageBaseUrl } returns "https://images.example/v1"
            every { imageApiKey } returns "image-key"
            every { imageModel } returns "image-model"
        }
        val viewModel = createViewModel(encyclopediaDao, backend, imageRepository, secureStorage)
        val message = viewModel.generateEncyclopediaCoverAiInternal(7L)

        assertEquals("封面已在生成期间更新，未覆盖当前封面", message)
        assertFalse(generatedFile.exists())
        coVerify(exactly = 1) {
            encyclopediaDao.updateCoverIfUnchanged(7L, "old.jpg", generatedFile.absolutePath, any())
        }
    }

    @Test
    fun localCoverSaveFailureIsNotReportedAsNetworkOrPermissionError() = runTest(dispatcher) {
        val generatedFile = Files.createTempFile("failed-cover-save", ".jpg").toFile()
        val encyclopedia = EncyclopediaEntity(id = 7L, name = "测试百科", coverImagePath = "old.jpg")
        val encyclopediaDao = mockk<EncyclopediaDao> {
            coEvery { getById(7L) } returns encyclopedia
            coEvery { getAll() } returns listOf(encyclopedia)
            coEvery {
                updateCoverIfUnchanged(7L, "old.jpg", generatedFile.absolutePath, any())
            } throws IllegalStateException("database unavailable")
        }
        val backend = mockk<BackendEncyclopediaApi>(relaxed = true)
        val imageRepository = mockk<ImageRepository> {
            coEvery {
                generateAndSaveImageForCover(any(), any(), any(), any(), any(), any())
            } returns Result.success(generatedFile.absolutePath)
        }
        val secureStorage = mockk<SecureStorage> {
            every { publicBaseUrl } returns "https://gateway.example/v1"
            every { publicApiKey } returns "public-key"
            every { imageBaseUrl } returns "https://images.example/v1"
            every { imageApiKey } returns "image-key"
            every { imageModel } returns "image-model"
        }
        val viewModel = createViewModel(encyclopediaDao, backend, imageRepository, secureStorage)

        val message = viewModel.generateEncyclopediaCoverAiInternal(7L)

        assertEquals("图片已生成，但封面未能保存到本机，请重试。", message)
        assertFalse(message.contains("网络"))
        assertFalse(message.contains("权限"))
        assertFalse(generatedFile.exists())
    }

    @Test
    fun manualCoverReportsSuccessOnlyAfterRoomAcceptsTheExactTarget() = runTest(dispatcher) {
        val localFile = Files.createTempFile("manual-encyclopedia-cover", ".jpg").toFile()
        val original = EncyclopediaEntity(id = 7L, name = "测试百科", coverImagePath = "old.jpg")
        val updated = original.copy(coverImagePath = localFile.absolutePath)
        val encyclopediaDao = mockk<EncyclopediaDao> {
            coEvery { getAll() } returnsMany listOf(listOf(original), listOf(updated))
            coEvery { updateCover(7L, localFile.absolutePath, any()) } returns 1
        }
        val viewModel = createViewModel(
            encyclopediaDao,
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
        )

        val message = viewModel.updateEncyclopediaCover(7L, localFile.absolutePath)

        assertEquals("已更新封面", message)
        assertEquals(localFile.absolutePath, viewModel.encyclopedias.value.single().coverImagePath)
        assertTrue(localFile.exists())
    }

    @Test
    fun manualCoverRoomFailureDeletesTheUnclaimedFile() = runTest(dispatcher) {
        val localFile = Files.createTempFile("failed-manual-encyclopedia-cover", ".jpg").toFile()
        val encyclopedia = EncyclopediaEntity(id = 7L, name = "测试百科", coverImagePath = "old.jpg")
        val encyclopediaDao = mockk<EncyclopediaDao> {
            coEvery { getAll() } returns listOf(encyclopedia)
            coEvery { updateCover(7L, localFile.absolutePath, any()) } throws
                IllegalStateException("database unavailable")
        }
        val viewModel = createViewModel(
            encyclopediaDao,
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
        )

        val message = viewModel.updateEncyclopediaCover(7L, localFile.absolutePath)

        assertEquals("封面未能保存到本机，请重试。", message)
        assertFalse(localFile.exists())
    }
}
