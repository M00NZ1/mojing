package com.mojing.app.domain.engine

import com.mojing.app.data.remote.ChatMessage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BatchGenerator @Inject constructor(
    private val llmRetry: LlmRetry,
) {
    /**
     * 生成若干条百科条目，返回 JSON 数组解析后的 map 列表。
     * [count] 建议单次不超过 5，总量大时由上层分块多次调用。
     */
    suspend fun generateBatch(
        apiKey: String,
        baseUrl: String,
        model: String,
        encyclopediaId: Long,
        entryType: String,
        count: Int,
        worldPrompt: String = "",
        minWords: Int = 200,
        maxWords: Int = 800,
        extraUserContext: String = "",
    ): List<Map<String, Any>> {
        val safeCount = count.coerceIn(1, 10)
        val wp = worldPrompt.trim()
        val ex = extraUserContext.trim()
        val prompt = buildString {
            appendLine("请生成 $safeCount 个「$entryType」类型的百科条目，只返回一个 JSON 数组，不要其它说明文字。")
            appendLine("每个对象必须包含以下键：title（标题）、summary（摘要）、content（正文）、tags（字符串，多个标签用逗号分隔）。")
            appendLine("要求：各条 title 互不重复；content 正文字数控制在约 $minWords～$maxWords 字（中文按字符计）。")
            if (entryType == "world") {
                appendLine("类型为 world：请写宏观设定维度（规则、版图、历史脉络、力量体系等），summary 为一段总览，content 可偏纲要但信息密度要高。")
                if (ex.isNotEmpty()) {
                    appendLine("用户给出的世界梗概 / 核心要求（请在此基础上充分扩写，不要只复述几句）：")
                    appendLine(ex)
                }
            } else if (ex.isNotEmpty()) {
                appendLine("用户额外要求：\n$ex")
            }
            if (wp.isNotEmpty()) appendLine("世界观与百科背景：\n$wp")
            appendLine("格式示例：[{\"title\":\"…\",\"summary\":\"…\",\"content\":\"…\",\"tags\":\"标签1,标签2\"}]")
            appendLine("百科 id（仅作参考）: $encyclopediaId")
        }

        val messages = listOf(
            ChatMessage("system", "你是百科内容生成专家。只输出合法 JSON 数组，键使用英文。"),
            ChatMessage("user", prompt),
        )

        val maxTokens = (2000 + safeCount * 1200).coerceAtMost(16_000)
        return try {
            val result = llmRetry.chatCompletionWithRetry(
                apiKey = apiKey,
                baseUrl = baseUrl,
                model = model,
                messages = messages,
                temperature = 0.75f,
                maxTokens = maxTokens,
            )
            val json = extractJsonArray(result)
            Gson().fromJson(json, object : TypeToken<List<Map<String, Any>>>() {}.type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun generateTimelineEventsBatch(
        apiKey: String,
        baseUrl: String,
        model: String,
        encyclopediaId: Long,
        count: Int,
        worldPrompt: String = "",
        minWords: Int = 80,
        maxWords: Int = 600,
        extraUserContext: String = "",
    ): List<Map<String, Any>> {
        val safeCount = count.coerceIn(1, 10)
        val wp = worldPrompt.trim()
        val ex = extraUserContext.trim()
        val prompt = buildString {
            appendLine("请生成 $safeCount 条「百科时间线事件」，只返回一个 JSON 数组，不要其它说明文字。")
            appendLine("每个对象必须包含：title（标题）、description（事件描述）、eventTime（时间标签，自由文本，如「历372年春」或「大战前夜」）。")
            appendLine("可选键：sortOrder（整数，0 表示最早；若省略将由客户端按顺序追加）。")
            appendLine("description 字数约 $minWords～$maxWords 字（中文按字符计）；各 title 尽量不重复。")
            if (wp.isNotEmpty()) appendLine("世界观与百科背景：\n$wp")
            if (ex.isNotEmpty()) appendLine("用户额外要求：\n$ex")
            appendLine("格式示例：[{\"title\":\"…\",\"description\":\"…\",\"eventTime\":\"…\",\"sortOrder\":0}]")
            appendLine("百科 id（仅作参考）: $encyclopediaId")
        }
        val messages = listOf(
            ChatMessage("system", "你是世界观编年史助手。只输出合法 JSON 数组，键使用英文。"),
            ChatMessage("user", prompt),
        )
        val maxTokens = (1800 + safeCount * 900).coerceAtMost(10000)
        return try {
            val result = llmRetry.chatCompletionWithRetry(
                apiKey = apiKey,
                baseUrl = baseUrl,
                model = model,
                messages = messages,
                temperature = 0.72f,
                maxTokens = maxTokens,
            )
            val json = extractJsonArray(result)
            Gson().fromJson(json, object : TypeToken<List<Map<String, Any>>>() {}.type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun extractJsonArray(text: String): String {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        return if (start >= 0 && end > start) text.substring(start, end + 1) else "[]"
    }
}
