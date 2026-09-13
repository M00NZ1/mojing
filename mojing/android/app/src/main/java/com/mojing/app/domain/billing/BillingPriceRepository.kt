package com.mojing.app.domain.billing

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.mojing.app.data.local.dao.CostRecordDao
import com.mojing.app.data.local.entity.ConfigEntity
import com.mojing.app.data.repository.BillingPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BillingPriceRepository @Inject constructor(
    private val dao: CostRecordDao,
    private val legacy: BillingPreferences,
) {
    private val gson = Gson()
    suspend fun price(platformId: String, model: String): ModelPricing? = withContext(Dispatchers.IO) {
        val stored = dao.storedPrice(key(platformId, model))
        if (stored == null) return@withContext legacy.price(platformId, model)
        if (stored == "null") return@withContext null
        val item = JsonParser.parseString(stored).asJsonObject
        ModelPricing(item.get("currency").asString, item.get("inputPerMillion").asDouble,
            item.get("outputPerMillion").asDouble,
            item.get("cachedInputPerMillion")?.takeUnless { it.isJsonNull }?.asDouble,
            item.get("source").asString, item.get("updatedAt").asLong)
    }

    suspend fun hasPricedHistory(platformId: String, model: String) = dao.hasPricedHistory(platformId, model)

    suspend fun save(platformId: String, model: String, price: ModelPricing, syncHistory: Boolean): Int =
        withContext(Dispatchers.IO) {
            require(platformId.isNotBlank() && model.isNotBlank())
            dao.savePriceAndHistory(key(platformId, model), platformId, model, price, gson.toJson(price), syncHistory)
        }

    suspend fun remove(platformId: String, model: String) = withContext(Dispatchers.IO) {
        // Tombstone prevents an older preference from being revived after clearing.
        dao.writePrice(ConfigEntity(key(platformId, model), "null"))
    }

    internal fun key(platformId: String, model: String) = "billing_price:v1:" + gson.toJson(listOf(platformId, model))
}
