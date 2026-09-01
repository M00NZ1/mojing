package com.mojing.app.domain.engine

import com.mojing.app.data.remote.ChatMessage
import com.mojing.app.ui.encyclopedia.meta.EncyclopediaMetaDefinitions
import com.mojing.app.util.UsbSessionLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiCompleter @Inject constructor(
    private val llmRetry: LlmRetry
) {
    data class CompleteRequest(
        val targetType: String,
        val entryType: String? = null,
        val currentData: Map<String, Any> = emptyMap(),
        val extraContext: String = ""
    )

    suspend fun complete(
        apiKey: String,
        baseUrl: String,
        model: String,
        request: CompleteRequest
    ): Map<String, Any> {
        val fields = getFieldsForType(request.targetType, request.entryType)
        if (fields.isEmpty()) return emptyMap()

        val fieldList = fields.keys.joinToString("、")
        val currentJson = Gson().toJson(request.currentData)

        val prompt = buildString {
            appendLine("根据以下已有信息，补全缺少的字段。")
            appendLine("目标类型：${request.targetType}${request.entryType?.let { " / $it" } ?: ""}")
            appendLine("需要补全的字段：$fieldList")
            if (request.extraContext.isNotBlank()) appendLine("额外上下文：${request.extraContext}")
            appendLine("当前数据：$currentJson")
            appendLine("请返回一个JSON对象，只包含需要补全的字段及其值。确保内容与已有信息一致。")
        }

        val messages = listOf(
            ChatMessage("system", "你是内容补全助手。根据已有信息填充缺少的字段，输出纯JSON。"),
            ChatMessage("user", prompt)
        )

        val maxTokens = when (request.targetType) {
            "encyclopedia_entry_meta" -> 8000
            else -> 3000
        }
        val result = llmRetry.chatCompletionWithRetry(
            apiKey = apiKey,
            baseUrl = baseUrl,
            model = model,
            messages = messages,
            temperature = 0.7f,
            maxTokens = maxTokens,
        )
        if (request.targetType == "world_template") {
            UsbSessionLog.i(
                "AiCompleter",
                "type=${request.targetType} model=${model.trim()} base=${baseUrl.trim()} extraLen=${request.extraContext.length} currentLen=${currentJson.length} rawLen=${result.length} raw=${clipLog(result)}",
            )
        }

        val json = extractJson(result)
        return try {
            Gson().fromJson(json, object : TypeToken<Map<String, Any>>() {}.type) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun clipLog(text: String, limit: Int = 400): String {
        val t = text.trim()
        return if (t.length <= limit) t else t.take(limit) + "…len=" + t.length
    }

    private fun extractJson(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start >= 0 && end > start) text.substring(start, end + 1) else "{}"
    }

    private fun getFieldsForType(targetType: String, entryType: String?): Map<String, String> {
        return when {
            targetType == "character" -> mapOf(
                "name" to "角色名", "persona_prompt" to "人设提示词",
                "appearance" to "外貌描述", "background" to "背景故事"
            )
            targetType == "encyclopedia_entry" -> when (entryType) {
                "character" -> mapOf("alias" to "别名", "race" to "种族", "gender" to "性别", "age" to "年龄", "occupation" to "职业", "abilities" to "能力")
                "location" -> mapOf("region" to "区域", "climate" to "气候", "landmarks" to "地标", "population" to "人口")
                "item" -> mapOf("type" to "类型", "origin" to "来源", "abilities" to "能力/效果", "rarity" to "稀有度")
                else -> mapOf("background" to "背景", "significance" to "重要性", "relationships" to "关联")
            }
            targetType == "encyclopedia_entry_meta" -> {
                val et = entryType?.takeIf { it.isNotBlank() } ?: "concept"
                EncyclopediaMetaDefinitions.aiFieldMapForCompleter(et)
            }
            targetType == "world_template" -> mapOf(
                "summary" to "摘要", "category" to "分类",
                "themes" to "主题标签", "tone" to "风格基调",
                "worldPrompt" to "世界书"
            )
            else -> emptyMap()
        }
    }

    fun fieldKeysFor(targetType: String, entryType: String?): Set<String> =
        getFieldsForType(targetType, entryType).keys
}
