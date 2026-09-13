package com.mojing.app.domain.billing

import com.mojing.app.data.local.dao.CostRecordDao
import com.mojing.app.data.local.entity.CostRecordEntity
import com.mojing.app.domain.engine.TokenCounter
import com.mojing.app.data.SecureStorage
import com.mojing.app.data.repository.BillingPreferences
import com.google.gson.Gson
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class BillingRequestSnapshot(val platformId: String, val platformName: String, val price: ModelPricing?)

/** 保存每次请求的用量、原币费用和请求开始时的价格快照。 */
@Singleton
class CostRecorder @Inject constructor(
    private val costRecordDao: CostRecordDao,
    private val preferences: BillingPriceRepository,
    private val secureStorage: SecureStorage,
) {
    suspend fun capture(model: String, baseUrl: String, apiKey: String): BillingRequestSnapshot {
        fun endpoint(raw: String): String {
            val url = com.mojing.app.domain.config.OpenAiCompatibleRouting.buildChatCompletionsUrl(raw).toHttpUrlOrNull() ?: return "unconfigured"
            return "${url.scheme}://${url.host}:${url.port}${url.encodedPath.trimEnd('/')}"
        }
        val base = endpoint(baseUrl)
        val matches = runCatching { secureStorage.modelPlatforms() }.getOrDefault(emptyList())
            .filter { platform -> platform.baseUrl.lineSequence().any { endpoint(it) == base } &&
                platform.apiKey.trim().removePrefix("Bearer ") == apiKey.trim().removePrefix("Bearer ") }
        val platform = matches.singleOrNull() ?: matches.filter { model in it.models }.singleOrNull()
        val id = platform?.id ?: "endpoint:$base"
        val name = platform?.name ?: (baseUrl.toHttpUrlOrNull()?.host ?: "未配置平台")
        return BillingRequestSnapshot(id, name, runCatching { preferences.price(id, model) }.getOrNull())
    }

    suspend fun recordLlm(
        sessionId: Long?,
        characterId: Long?,
        modelName: String,
        provider: String,
        promptTokens: Int,
        completionTokens: Int,
        durationMs: Int,
        success: Boolean,
        /** API 未返回 usage 时，按请求和已有输出估算 Token。 */
        promptTextFallback: String? = null,
        completionTextFallback: String? = null,
        request: BillingRequestSnapshot? = null,
        usageProvided: Boolean = promptTokens > 0 || completionTokens > 0,
        cachedPromptTokens: Int = 0,
        status: String = if (success) "success" else "failed",
    ): CostRecordEntity {
        var pt = promptTokens.coerceAtLeast(0)
        var ct = completionTokens.coerceAtLeast(0)
        if (!usageProvided) {
            if (!promptTextFallback.isNullOrBlank()) {
                pt = TokenCounter.estimateScaledPrefix(promptTextFallback)
            }
            if (!completionTextFallback.isNullOrBlank()) {
                ct = TokenCounter.estimate(completionTextFallback)
            }
        }
        val total = (pt.toLong() + ct).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val price = request?.price
        val known = price != null
        val cached = cachedPromptTokens.coerceIn(0, pt)
        val amount = price?.let { BillingMath.estimate(it, pt, ct, cached) } ?: 0.0
        val record = CostRecordEntity(
            sessionId = sessionId, characterId = characterId, modelName = modelName, provider = provider,
            promptTokens = pt, completionTokens = ct, totalTokens = total, estimatedCost = amount,
            durationMs = durationMs.coerceAtLeast(0), success = success,
            platformId = request?.platformId.orEmpty(), platformName = request?.platformName.orEmpty(),
            currency = price?.currency ?: "USD", costKnown = known,
            tokenSource = if (usageProvided) "api" else "estimated", status = status,
            cachedPromptTokens = cached, pricingSnapshotJson = price?.let { Gson().toJson(it) } ?: "{}",
        )
        return withContext(Dispatchers.IO) { costRecordDao.insertWithCurrentPrice(record,
            preferences.key(record.platformId, record.modelName)) }
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
