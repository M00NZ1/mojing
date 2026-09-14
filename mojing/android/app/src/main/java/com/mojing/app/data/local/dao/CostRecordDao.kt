package com.mojing.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mojing.app.data.local.entity.CostRecordEntity

data class ModelUsageSummary(
    val modelName: String,
    val totalCalls: Int,
    val failedCalls: Int,
    val totalTokens: Long,
    val estimatedCost: Double,
)

data class UsageCurrencySummary(
    val currency: String, val totalCalls: Long, val failedCalls: Long, val cancelledCalls: Long,
    val promptTokens: Long, val completionTokens: Long, val totalTokens: Long,
    val estimatedCost: Double, val unknownCostCalls: Long,
)
data class PlatformUsageSummary(val platformId: String, val platformName: String,
    val totalCalls: Long, val failedCalls: Long, val totalTokens: Long)
data class ModelChannelUsageSummary(val modelName: String, val totalCalls: Long,
    val failedCalls: Long, val totalTokens: Long)

@Dao
interface CostRecordDao {
    @Query("SELECT valueJson FROM app_config WHERE `key`=:key")
    suspend fun storedPrice(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun writePrice(value: com.mojing.app.data.local.entity.ConfigEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM llm_cost_records WHERE platformId=:platformId AND modelName=:modelName AND costKnown=1)")
    suspend fun hasPricedHistory(platformId: String, modelName: String): Boolean

    @Query("SELECT * FROM llm_cost_records WHERE id=:id")
    fun observeRecord(id: Long): kotlinx.coroutines.flow.Flow<CostRecordEntity?>

    @Query("UPDATE llm_cost_records SET estimatedCost=(" +
        "(MAX(promptTokens,0)-MIN(MAX(cachedPromptTokens,0),MAX(promptTokens,0)))*:inputRate + " +
        "MIN(MAX(cachedPromptTokens,0),MAX(promptTokens,0))*:cachedRate + MAX(completionTokens,0)*:outputRate)/1000000.0, " +
        "currency=:currency, costKnown=1, pricingSnapshotJson=:snapshot " +
        "WHERE platformId=:platformId AND modelName=:modelName AND (:includeKnown OR costKnown=0)")
    suspend fun repriceHistory(platformId: String, modelName: String, inputRate: Double, outputRate: Double,
        cachedRate: Double, currency: String, snapshot: String, includeKnown: Boolean): Int

    @androidx.room.Transaction
    suspend fun savePriceAndHistory(key: String, platformId: String, modelName: String,
        price: com.mojing.app.domain.billing.ModelPricing, snapshot: String, syncHistory: Boolean): Int {
        val backfill = !hasPricedHistory(platformId, modelName)
        writePrice(com.mojing.app.data.local.entity.ConfigEntity(key, snapshot))
        return if (syncHistory || backfill) repriceHistory(platformId, modelName, price.inputPerMillion,
            price.outputPerMillion, price.cachedInputPerMillion ?: price.inputPerMillion,
            price.currency, snapshot, syncHistory) else 0
    }

    @androidx.room.Transaction
    suspend fun insertWithCurrentPrice(record: CostRecordEntity, priceKey: String): CostRecordEntity {
        var current = record
        if (!record.costKnown && record.platformId.isNotBlank()) {
            val snapshot = storedPrice(priceKey)
            if (snapshot != null && snapshot != "null") {
                val item = com.google.gson.JsonParser.parseString(snapshot).asJsonObject
                val price = com.mojing.app.domain.billing.ModelPricing(item.get("currency").asString,
                    item.get("inputPerMillion").asDouble, item.get("outputPerMillion").asDouble,
                    item.get("cachedInputPerMillion")?.takeUnless { it.isJsonNull }?.asDouble)
                current = record.copy(costKnown = true, currency = price.currency,
                    estimatedCost = com.mojing.app.domain.billing.BillingMath.estimate(price,
                        record.promptTokens, record.completionTokens, record.cachedPromptTokens),
                    pricingSnapshotJson = snapshot)
            }
        }
        return current.copy(id = insert(current))
    }

    @Query("SELECT * FROM llm_cost_records ORDER BY createdAt DESC LIMIT 100")
    suspend fun getRecent(): List<CostRecordEntity>

    @Query("SELECT SUM(estimatedCost) FROM llm_cost_records")
    suspend fun getTotalCost(): Double?

    @Query("SELECT SUM(totalTokens) FROM llm_cost_records")
    suspend fun getTotalTokens(): Long?

    @Query("SELECT COUNT(*) FROM llm_cost_records")
    suspend fun getTotalCalls(): Int

    @Query("SELECT COUNT(*) FROM llm_cost_records WHERE success = 0")
    suspend fun getFailedCalls(): Int

    @Query(
        """
        SELECT
            CASE WHEN TRIM(modelName) = '' THEN '未记录模型' ELSE modelName END AS modelName,
            COUNT(*) AS totalCalls,
            SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END) AS failedCalls,
            COALESCE(SUM(totalTokens), 0) AS totalTokens,
            COALESCE(SUM(estimatedCost), 0.0) AS estimatedCost
        FROM llm_cost_records
        GROUP BY CASE WHEN TRIM(modelName) = '' THEN '未记录模型' ELSE modelName END
        ORDER BY totalCalls DESC, modelName ASC
        """,
    )
    suspend fun getModelUsageSummaries(): List<ModelUsageSummary>


    @Query("SELECT currency, COUNT(*) AS totalCalls, SUM(CASE WHEN success=0 THEN 1 ELSE 0 END) AS failedCalls, " +
        "SUM(CASE WHEN status='cancelled' THEN 1 ELSE 0 END) AS cancelledCalls, " +
        "COALESCE(SUM(promptTokens),0) AS promptTokens, COALESCE(SUM(completionTokens),0) AS completionTokens, " +
        "COALESCE(SUM(totalTokens),0) AS totalTokens, COALESCE(SUM(CASE WHEN costKnown=1 THEN estimatedCost ELSE 0 END),0.0) AS estimatedCost, " +
        "SUM(CASE WHEN costKnown=0 THEN 1 ELSE 0 END) AS unknownCostCalls FROM llm_cost_records " +
        "WHERE (:platformId IS NULL OR platformId=:platformId) AND (:modelName IS NULL OR modelName=:modelName) GROUP BY currency")
    suspend fun usageSummary(platformId: String? = null, modelName: String? = null): List<UsageCurrencySummary>

    @Query("SELECT platformId, CASE WHEN platformId='' THEN '历史记录（未记录平台）' ELSE MAX(platformName) END AS platformName, " +
        "COUNT(*) AS totalCalls, SUM(CASE WHEN success=0 THEN 1 ELSE 0 END) AS failedCalls, COALESCE(SUM(totalTokens),0) AS totalTokens " +
        "FROM llm_cost_records GROUP BY platformId ORDER BY MAX(id) DESC")
    suspend fun platformUsage(): List<PlatformUsageSummary>

    @Query("SELECT modelName, COUNT(*) AS totalCalls, SUM(CASE WHEN success=0 THEN 1 ELSE 0 END) AS failedCalls, " +
        "COALESCE(SUM(totalTokens),0) AS totalTokens FROM llm_cost_records WHERE platformId=:platformId " +
        "GROUP BY modelName ORDER BY MAX(id) DESC")
    suspend fun modelUsage(platformId: String): List<ModelChannelUsageSummary>

    @Query("SELECT * FROM llm_cost_records WHERE platformId=:platformId AND modelName=:modelName " +
        "AND (:statusFilter='all' OR (:statusFilter='success' AND success=1) OR (:statusFilter='failed' AND success=0 AND status!='cancelled') OR (:statusFilter='cancelled' AND status='cancelled')) " +
        "AND ((CASE WHEN :sortOrder='tokens' THEN totalTokens ELSE id END) < :beforeValue OR ((CASE WHEN :sortOrder='tokens' THEN totalTokens ELSE id END) = :beforeValue AND id < :beforeId)) " +
        "ORDER BY (CASE WHEN :sortOrder='tokens' THEN totalTokens ELSE id END) DESC, id DESC LIMIT :limit")
    suspend fun filteredRequestPage(platformId: String, modelName: String, beforeId: Long, beforeValue: Long, limit: Int, statusFilter: String, sortOrder: String): List<CostRecordEntity>

    @Query("SELECT * FROM llm_cost_records WHERE platformId=:platformId AND modelName=:modelName AND id < :beforeId ORDER BY id DESC LIMIT :limit")
    suspend fun requestPage(platformId: String, modelName: String, beforeId: Long, limit: Int): List<CostRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: CostRecordEntity): Long
}
