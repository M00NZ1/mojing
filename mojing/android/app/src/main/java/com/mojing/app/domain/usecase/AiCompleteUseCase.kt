package com.mojing.app.domain.usecase

import com.mojing.app.domain.engine.AiCompleter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiCompleteUseCase @Inject constructor(
    private val aiCompleter: AiCompleter
) {
    suspend operator fun invoke(
        apiKey: String, baseUrl: String, model: String,
        targetType: String, entryType: String?, currentData: Map<String, Any>, extraContext: String
    ): Map<String, Any> {
        return aiCompleter.complete(
            apiKey, baseUrl, model,
            AiCompleter.CompleteRequest(targetType, entryType, currentData, extraContext)
        )
    }
}
