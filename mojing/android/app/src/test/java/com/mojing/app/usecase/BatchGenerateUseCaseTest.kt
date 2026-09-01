package com.mojing.app.usecase

import com.mojing.app.domain.engine.BatchGenerator
import com.mojing.app.domain.usecase.BatchGenerateUseCase
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchGenerateUseCaseTest {

    private val batchGenerator = mockk<BatchGenerator>(relaxed = true)
    private val useCase = BatchGenerateUseCase(batchGenerator)

    @Test
    fun invokeReturnsCorrectCount() = runTest {
        coEvery {
            batchGenerator.generateBatch(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns listOf(
            mapOf("title" to "条目1"),
            mapOf("title" to "条目2"),
            mapOf("title" to "条目3")
        )
        val result = useCase(
            apiKey = "k",
            baseUrl = "https://api.test.com",
            model = "gpt-4",
            encyclopediaId = 1L,
            entryType = "location",
            count = 3,
            worldPrompt = "修仙世界"
        )
        assertEquals(3, result.size)
    }

    @Test
    fun invokePropagatesEmptyList() = runTest {
        coEvery { batchGenerator.generateBatch(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns emptyList()
        val result = useCase("k", "https://x.com", "m", 1L, "character", 2, "")
        assertTrue(result.isEmpty())
    }
}
