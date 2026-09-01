package com.mojing.app.domain.billing

import com.mojing.app.data.local.dao.CostRecordDao
import com.mojing.app.data.local.entity.CostRecordEntity
import com.mojing.app.domain.engine.TokenCounter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 统一写入 [CostRecordEntity]：成功时写入 token 与估算费用；失败时 success=false、费用 0，
 * 便于设置页「失败次数」与总调用次数统计。
 */
@Singleton
class CostRecorder @Inject constructor(
    private val costRecordDao: CostRecordDao,
) {
    suspend fun recordLlm(
        sessionId: Long?,
        characterId: Long?,
        modelName: String,
        provider: String,
        promptTokens: Int,
        completionTokens: Int,
        durationMs: Int,
        success: Boolean,
        /** API 未返回 usage 时，用正文做 TokenCounter 粗估（仅成功或需区分 prompt/output 时）。 */
        promptTextFallback: String? = null,
        completionTextFallback: String? = null,
    ) {
        var pt = promptTokens.coerceAtLeast(0)
        var ct = completionTokens.coerceAtLeast(0)
        if (pt == 0 && ct == 0) {
            if (!promptTextFallback.isNullOrBlank()) {
                pt = TokenCounter.estimateScaledPrefix(promptTextFallback)
            }
            if (success && !completionTextFallback.isNullOrBlank()) {
                ct = TokenCounter.estimate(completionTextFallback)
            }
        }
        val total = (pt + ct).coerceAtLeast(0)
        val cost = if (success) LlmCostEstimator.estimateUsd(modelName, pt, ct) else 0.0
        withContext(Dispatchers.IO) {
            costRecordDao.insert(
                CostRecordEntity(
                    sessionId = sessionId,
                    characterId = characterId,
                    modelName = modelName,
                    provider = provider,
                    promptTokens = pt,
                    completionTokens = ct,
                    totalTokens = total,
                    estimatedCost = cost,
                    durationMs = durationMs.coerceAtLeast(0),
                    success = success,
                ),
            )
        }
    }

    suspend fun recordImage(
        sessionId: Long?,
        characterId: Long?,
        modelName: String,
        success: Boolean,
        durationMs: Int,
    ) {
        val usd = if (success) MediaCostEstimator.imageUsdPerCall(modelName) else 0.0
        withContext(Dispatchers.IO) {
            costRecordDao.insert(
                CostRecordEntity(
                    sessionId = sessionId,
                    characterId = characterId,
                    modelName = modelName,
                    provider = "image",
                    promptTokens = 0,
                    completionTokens = 0,
                    totalTokens = 0,
                    estimatedCost = usd,
                    durationMs = durationMs.coerceAtLeast(0),
                    success = success,
                ),
            )
        }
    }

    suspend fun recordVoiceHttp(
        sessionId: Long?,
        characterId: Long?,
        modelName: String,
        textCharCount: Int,
        success: Boolean,
        durationMs: Int,
    ) {
        val usd = if (success) MediaCostEstimator.voiceHeuristicUsd(textCharCount) else 0.0
        val pseudoTokens = textCharCount.coerceAtLeast(0)
        withContext(Dispatchers.IO) {
            costRecordDao.insert(
                CostRecordEntity(
                    sessionId = sessionId,
                    characterId = characterId,
                    modelName = modelName.ifBlank { "tts" },
                    provider = "voice_http",
                    promptTokens = 0,
                    completionTokens = 0,
                    totalTokens = pseudoTokens,
                    estimatedCost = usd,
                    durationMs = durationMs.coerceAtLeast(0),
                    success = success,
                ),
            )
        }
    }
}
