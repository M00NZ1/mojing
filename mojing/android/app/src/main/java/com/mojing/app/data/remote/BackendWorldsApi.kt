package com.mojing.app.data.remote

import com.mojing.app.data.SecureStorage
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.text.Charsets
import javax.inject.Inject
import javax.inject.Singleton

data class WorldQualityIssueDto(
    val level: String,
    val code: String,
    val message: String,
    val suggestion: String,
)

data class WorldQualityReportDto(
    val score: Int,
    val verdict: String,
    val strengths: List<String>,
    val risks: List<String>,
    val issues: List<WorldQualityIssueDto>,
)

@Singleton
class BackendWorldsApi @Inject constructor(
    private val client: OkHttpClient,
    private val secureStorage: SecureStorage,
) {
    private fun apiRoot(): String = resolveBackendApiRoot(secureStorage)

    /** 根地址可解析为墨境 FastAPI（非纯 OpenAI 网关）时，浏览器导出等伴侣接口可用。 */
    fun isCompanionBackendConfigured(): Boolean = apiRoot().isNotBlank()

    suspend fun reviewWorldQualityJson(body: JsonObject): Result<WorldQualityReportDto> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("${apiRoot()}/worlds/review-quality")
                .post(body.toString().toRequestBody(JSON))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error(text.ifBlank { "HTTP ${resp.code}" })
                parseQualityReport(JsonParser.parseString(text).asJsonObject)
            }
        }
    }

    suspend fun generateWorldJson(body: JsonObject): Result<JsonObject> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("${apiRoot()}/worlds/generate")
                .post(body.toString().toRequestBody(JSON))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error(text.ifBlank { "HTTP ${resp.code}" })
                JsonParser.parseString(text).asJsonObject
            }
        }
    }

    /** 浏览器打开以下载单模板文件（与 Web `exportWorldTemplate` 同源）。 */
    fun worldTemplateExportFileUrl(templateId: String): String? {
        val tid = templateId.trim()
        if (tid.isEmpty()) return null
        if (secureStorage.publicBaseUrl.isBlank()) return null
        return "${apiRoot()}/worlds/templates/${java.net.URLEncoder.encode(tid, Charsets.UTF_8)}/export-file"
    }

    /** 浏览器打开以下载模板包（与 Web `exportWorldTemplateBundle` 同源）。 */
    fun worldTemplateBundleExportFileUrl(includeBuiltin: Boolean): String? {
        if (secureStorage.publicBaseUrl.isBlank()) return null
        val flag = if (includeBuiltin) "true" else "false"
        return "${apiRoot()}/worlds/templates/export-bundle-file?include_builtin=$flag"
    }

    private fun parseQualityReport(root: JsonObject): WorldQualityReportDto {
        val issues = mutableListOf<WorldQualityIssueDto>()
        root.getAsJsonArray("issues")?.forEach { el ->
            if (!el.isJsonObject) return@forEach
            val o = el.asJsonObject
            issues.add(
                WorldQualityIssueDto(
                    level = jsonString(o.get("level")),
                    code = jsonString(o.get("code")),
                    message = jsonString(o.get("message")),
                    suggestion = jsonString(o.get("suggestion")),
                )
            )
        }
        return WorldQualityReportDto(
            score = jsonInt(root.get("score")),
            verdict = jsonString(root.get("verdict")),
            strengths = root.stringList("strengths"),
            risks = root.stringList("risks"),
            issues = issues,
        )
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

private fun JsonObject.stringList(key: String): List<String> {
    val arr = get(key) as? JsonArray ?: return emptyList()
    val out = ArrayList<String>()
    arr.forEach { el ->
        if (el.isJsonPrimitive) {
            val s = el.asJsonPrimitive.asString.trim()
            if (s.isNotEmpty()) out.add(s)
        }
    }
    return out
}

private fun jsonString(e: JsonElement?): String =
    if (e != null && e.isJsonPrimitive) e.asJsonPrimitive.asString else ""

private fun jsonInt(e: JsonElement?): Int =
    if (e != null && e.isJsonPrimitive) runCatching { e.asJsonPrimitive.asInt }.getOrDefault(0) else 0
