package com.mojing.app.data.remote

import com.mojing.app.data.SecureStorage
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

data class AssetItemDto(
    val id: String,
    val label: String,
    val category: String,
    val storagePath: String,
    val licenseName: String,
)

@Singleton
class BackendAssetsApi @Inject constructor(
    private val client: OkHttpClient,
    private val secureStorage: SecureStorage,
) {
    suspend fun listAssets(category: String, q: String): Result<List<AssetItemDto>> = withContext(Dispatchers.IO) {
        runCatching {
            val root = resolveBackendApiRoot(secureStorage).trimEnd('/')
            val qs = mutableListOf<String>()
            if (category.isNotBlank()) {
                qs.add("category=" + URLEncoder.encode(category, Charsets.UTF_8))
            }
            if (q.isNotBlank()) {
                qs.add("q=" + URLEncoder.encode(q, Charsets.UTF_8))
            }
            val url = buildString {
                append(root).append("/assets")
                if (qs.isNotEmpty()) append('?').append(qs.joinToString("&"))
            }
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error(text.ifBlank { "HTTP ${resp.code}" })
                parseAssetArray(text)
            }
        }
    }

    private fun parseAssetArray(json: String): List<AssetItemDto> {
        val el = JsonParser.parseString(json)
        if (!el.isJsonArray) return emptyList()
        val out = ArrayList<AssetItemDto>()
        el.asJsonArray.forEach { item ->
            if (!item.isJsonObject) return@forEach
            val o = item.asJsonObject
            out.add(
                AssetItemDto(
                    id = str(o, "id"),
                    label = str(o, "label"),
                    category = str(o, "category"),
                    storagePath = str(o, "storage_path"),
                    licenseName = str(o, "license_name"),
                )
            )
        }
        return out
    }

    private fun str(o: JsonObject, key: String): String {
        val e: JsonElement? = o.get(key) ?: return ""
        return if (e != null && e.isJsonPrimitive) e.asString else ""
    }
}
