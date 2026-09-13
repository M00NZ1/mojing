package com.mojing.app.data.repository

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojing.app.domain.billing.ModelPricing
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Local model-price preferences. Credentials are intentionally not stored here. */
@Singleton
class BillingPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val gson = Gson()

    @Synchronized
    fun price(platformId: String, model: String): ModelPricing? {
        val root = readRoot() ?: return null
        val platform = root.getAsJsonObject(platformId.trim()) ?: return null
        val value = platform.get(model.trim()) ?: return null
        if (!value.isJsonObject) throw IllegalStateException("价格记录损坏，请先恢复价格配置")
        val item = value.asJsonObject
        return runCatching {
            // Gson's reflective adapter bypasses init blocks; construct explicitly
            // so imported/corrupted values cannot escape ModelPricing validation.
            ModelPricing(
                currency = item.get("currency")?.asString ?: "CNY",
                inputPerMillion = item.get("inputPerMillion")?.asDouble ?: error("缺少输入价格"),
                outputPerMillion = item.get("outputPerMillion")?.asDouble ?: error("缺少输出价格"),
                cachedInputPerMillion = item.get("cachedInputPerMillion")?.takeUnless { it.isJsonNull }?.asDouble,
                source = item.get("source")?.asString ?: "manual",
                updatedAt = item.get("updatedAt")?.asLong ?: error("缺少更新时间"),
            )
        }.getOrElse { throw IllegalStateException("价格记录损坏，请先恢复价格配置", it) }
    }

    @Synchronized
    fun savePrice(platformId: String, model: String, pricing: ModelPricing) {
        val platformKey = platformId.trim()
        val modelKey = model.trim()
        require(platformKey.isNotEmpty() && modelKey.isNotEmpty()) { "平台和模型不能为空" }
        val root = readRoot() ?: JsonObject()
        val platform = root.getAsJsonObject(platformKey) ?: JsonObject().also { root.add(platformKey, it) }
        platform.add(modelKey, gson.toJsonTree(pricing))
        check(preferences.edit().putString(KEY_PRICES, root.toString()).commit()) { "价格保存失败，请重试" }
    }

    @Synchronized
    fun removePrice(platformId: String, model: String) {
        val root = readRoot() ?: return
        root.getAsJsonObject(platformId.trim())?.remove(model.trim())
        check(preferences.edit().putString(KEY_PRICES, root.toString()).commit()) { "价格删除失败，请重试" }
    }

    private fun readRoot(): JsonObject? {
        val raw = preferences.getString(KEY_PRICES, null) ?: return null
        return runCatching { JsonParser.parseString(raw).asJsonObject }
            .getOrElse { throw IllegalStateException("价格配置损坏，请先恢复价格配置", it) }
    }

    private companion object {
        const val PREFERENCES = "mojing_billing_preferences"
        const val KEY_PRICES = "model_prices_v1"
    }
}
