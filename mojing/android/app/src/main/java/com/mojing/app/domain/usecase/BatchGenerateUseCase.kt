package com.mojing.app.domain.usecase

import com.mojing.app.domain.engine.BatchGenerator
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BatchGenerateUseCase @Inject constructor(
    private val batchGenerator: BatchGenerator
) {
    suspend operator fun invoke(
        apiKey: String, baseUrl: String, model: String,
        encyclopediaId: Long, entryType: String, count: Int, worldPrompt: String,
        minWords: Int = 200,
        maxWords: Int = 800,
        extraUserContext: String = "",
    ): List<Map<String, Any>> {
        return batchGenerator.generateBatch(
            apiKey, baseUrl, model, encyclopediaId, entryType, count, worldPrompt,
            minWords, maxWords, extraUserContext,
        )
    }
}
