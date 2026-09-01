package com.mojing.app.repository

import android.net.Uri
import com.mojing.app.data.remote.ImageApiService
import com.mojing.app.data.repository.ImageRepository
import com.mojing.app.domain.billing.CostRecorder
import com.mojing.app.media.ImageSaver
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ImageRepositoryTest {

    private val context = mockk<android.content.Context>(relaxed = true)
    private val imageApi = mockk<ImageApiService>(relaxed = true)
    private val imageSaver = mockk<ImageSaver>(relaxed = true)
    private val costRecorder = mockk<CostRecorder>(relaxed = true)
    private val repo = ImageRepository(context, imageApi, imageSaver, costRecorder)

    @Before
    fun stubCostRecorder() {
        clearMocks(imageApi, answers = true, recordedCalls = true, childMocks = true)
        coEvery { costRecorder.recordImage(any(), any(), any(), any(), any()) } returns Unit
    }

    @Test
    fun generateImageSuccess() = runTest {
        every { imageApi.normalizeImageBase(any()) } answers { (invocation.args[0] as String).trim().trimEnd('/') }
        coEvery { imageApi.generateImage(any(), any(), any(), any(), any(), any()) } returns "https://example.com/img.png"
        val result = repo.generateImage("test prompt", "sk-key", "https://api.openai.com", "dall-e-3")
        assertTrue(result.isSuccess)
        assertEquals("https://example.com/img.png", result.getOrNull())
    }

    @Test
    fun generateImageFailure() = runTest {
        every { imageApi.normalizeImageBase(any()) } answers { (invocation.args[0] as String).trim().trimEnd('/') }
        coEvery { imageApi.generateImage(any(), any(), any(), any(), any(), any()) } returns null
        val result = repo.generateImage("test prompt", "sk-key", "https://api.test.com", "dall-e-3")
        assertTrue(result.isFailure)
    }

    @Test
    fun generateImagePropagatesException() = runTest {
        every { imageApi.normalizeImageBase(any()) } answers { (invocation.args[0] as String).trim().trimEnd('/') }
        coEvery { imageApi.generateImage(any(), any(), any(), any(), any(), any()) } coAnswers { throw RuntimeException("API Error") }
        val result = repo.generateImage("test", "key", "url", "model")
        assertTrue(result.isFailure)
        assertEquals("API Error", result.exceptionOrNull()?.message)
    }

    @Test
    fun generateImageDoesNotConvertCancellationIntoFailure() = runTest {
        every { imageApi.normalizeImageBase(any()) } answers { (invocation.args[0] as String).trim().trimEnd('/') }
        coEvery { imageApi.generateImage(any(), any(), any(), any(), any(), any()) } throws CancellationException("stop")

        try {
            repo.generateImage("test", "key", "https://api.test.com", "model")
            fail("CancellationException should propagate")
        } catch (_: CancellationException) {
            // expected
        }
    }

    @Test
    fun generateAndSaveImageForCoverPreservesGenerationFailure() = runTest {
        every { imageApi.normalizeImageBase(any()) } answers { (invocation.args[0] as String).trim().trimEnd('/') }
        coEvery { imageApi.generateImage(any(), any(), any(), any(), any(), any()) } throws RuntimeException("API Error")

        val result = repo.generateAndSaveImageForCover(
            prompt = "test prompt",
            apiKey = "sk-key",
            baseUrl = "https://api.openai.com",
            model = "dall-e-3",
        )

        assertTrue(result.isFailure)
        assertEquals("API Error", result.exceptionOrNull()?.message)
    }

    @Test
    fun coverMaterializationAcceptsBase64WithoutHttpDownload() = runTest {
        val cacheDir = java.nio.file.Files.createTempDirectory("image-repository-test").toFile()
        every { context.cacheDir } returns cacheDir
        try {
            val path = repo.materializeGeneratedImage("data:image/png;base64,aGVsbG8=")

            assertNotNull(path)
            assertEquals("hello", java.io.File(path!!).readText())
            coVerify(exactly = 0) { imageApi.downloadImage(any()) }
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun sessionImageUsesSharedMaterializationAndTransfersOwnership() = runTest {
        val root = java.nio.file.Files.createTempDirectory("image-session-test").toFile()
        val cacheDir = java.io.File(root, "cache").apply { mkdirs() }
        val filesDir = java.io.File(root, "files").apply { mkdirs() }
        every { context.cacheDir } returns cacheDir
        every { context.filesDir } returns filesDir
        try {
            val path = repo.saveGeneratedImageForSession("data:image/png;base64,aGVsbG8=", 42L)

            assertNotNull(path)
            val saved = java.io.File(path!!)
            assertEquals(
                java.io.File(filesDir, "attachments/42").canonicalPath,
                requireNotNull(saved.parentFile).canonicalPath,
            )
            assertEquals("hello", saved.readText())
            assertTrue(cacheDir.listFiles().isNullOrEmpty())
            coVerify(exactly = 0) { imageApi.downloadImage(any()) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun saveImageToGallerySuccess() = runTest {
        coEvery { imageSaver.saveToGallery("/tmp/img.png", "scene.png", "image/png") } returns
            mockk<Uri> { every { scheme } returns "content" }

        val result = repo.saveLocalImageToGallery("/tmp/img.png", "scene.png", "image/png")

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { imageSaver.saveToGallery("/tmp/img.png", "scene.png", "image/png") }
    }

    @Test
    fun saveImageToGalleryDownloadFailed() = runTest {
        coEvery { imageSaver.saveToGallery(any(), any(), any()) } returns null

        val result = repo.saveLocalImageToGallery("/missing.png", "missing.png", "image/png")

        assertTrue(result.isFailure)
    }

    @Test
    fun saveImageToGalleryPreservesCancellation() = runTest {
        coEvery { imageSaver.saveToGallery(any(), any(), any()) } throws CancellationException("leave")

        try {
            repo.saveLocalImageToGallery("/tmp/img.png", "scene.png", "image/png")
            fail("CancellationException should propagate")
        } catch (_: CancellationException) {
            // expected
        }
    }
}
