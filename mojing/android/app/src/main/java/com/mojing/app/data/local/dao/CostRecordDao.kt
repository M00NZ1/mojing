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

@Dao
interface CostRecordDao {
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: CostRecordEntity): Long
}
