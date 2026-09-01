package com.mojing.app.domain.encyclopedia

import com.mojing.app.data.remote.ChatMessage
import com.mojing.app.domain.engine.LlmRetry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 将任意纯文本 / 杂乱设定稿转为 SillyTavern 风格的 WorldInfo JSON 数组字符串，
 * 再交给 [WorldInfoImportParser] 落库。
 */
@Singleton
class WorldInfoAiConverter @Inject constructor(
    private val llmRetry: LlmRetry,
) {
    suspend fun rawTextToWorldInfoJsonArray(
        apiKey: String,
        baseUrl: String,
        model: String,
        rawText: String,
        encyclopediaHint: String,
    ): String {
        val body = rawText.trim().take(80_000)
        if (body.isEmpty()) throw IllegalArgumentException("文本为空")
        val prompt = buildString {
            appendLine("用户上传了一段设定资料（可能含 Markdown、大纲、对话摘录或非结构化叙述），也可能混有少量 JSON。")
            appendLine("请将其**整理并拆分**为若干条「世界信息 / Lorebook」条目，输出 **仅一个 JSON 数组**，不要 Markdown 围栏、不要解释。")
            appendLine("数组中每个元素为对象，尽量包含以下键（字符串用中文亦可）：")
            appendLine("- comment 或 name：条目标题（简短）")
            appendLine("- content：正文（可较长，保留关键设定）")
            appendLine("- keys：触发关键词，字符串数组或逗号分隔字符串")
            appendLine("- secondary_keys：次要关键词（可选）")
            appendLine("- priority / depth / selective / constant / position / order：按需填数字或布尔，缺省可省略")
            appendLine("若原文明显已是合法 WorldInfo JSON 数组，可直接规范化后输出。")
            if (encyclopediaHint.isNotBlank()) {
                appendLine("百科/世界背景参考（勿逐字照抄，用于一致性与称呼）：")
                appendLine(encyclopediaHint.take(6000))
            }
            appendLine("--- 用户原文 ---")
            appendLine(body)
        }
        val messages = listOf(
            ChatMessage("system", "你是设定整理助手，只输出合法 JSON 数组。"),
            ChatMessage("user", prompt),
        )
        val result = llmRetry.chatCompletionWithRetry(
            apiKey = apiKey,
            baseUrl = baseUrl,
            model = model,
            messages = messages,
            temperature = 0.45f,
            maxTokens = 8192,
        )
        return extractJsonArray(result)
    }

    private fun extractJsonArray(text: String): String {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) throw IllegalArgumentException("模型未返回 JSON 数组")
        return text.substring(start, end + 1)
    }

    fun parseWorldInfoArrayJson(json: String): List<WorldInfoImportParser.DraftEntry> =
        WorldInfoImportParser.parse(json)
}
