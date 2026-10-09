package com.mojing.app.domain.usecase

import com.mojing.app.data.SecureStorage
import com.mojing.app.data.remote.ChatMessage
import com.mojing.app.data.remote.LlmHttpException
import com.mojing.app.domain.engine.LlmRetry
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton

class SmartImportException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

@Singleton
class SmartImportUseCase @Inject constructor(
    private val llmRetry: LlmRetry,
    private val secureStorage: SecureStorage,
) {
    /**
     * 将任意文本（非标准JSON、TXT等）解析为对应模块的标准导入 JSON 格式。
     */
    suspend fun parseToStructuredJson(
        rawText: String,
        targetType: String, // "character" | "encyclopedia" | "template"
    ): String {
        val trimmed = rawText.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return trimmed
        
        val prompt = buildString {
            appendLine("将以下文本内容解析为 $targetType 的标准导入JSON格式。")
            appendLine("文本内容：")
            appendLine(trimmed.take(8000))
            when (targetType) {
                "character" -> appendLine("返回JSON: {\"version\":1,\"type\":\"characters\",\"data\":[{\"name\":\"...\",\"personaPrompt\":\"...\"}]}")
                "encyclopedia" -> appendLine("返回JSON: {\"version\":1,\"type\":\"encyclopedias\",\"data\":[{\"name\":\"...\",\"entries\":[{\"title\":\"...\",\"entryType\":\"...\",\"content\":\"...\"}]}]}")
                "template" -> appendLine("返回JSON: {\"version\":1,\"type\":\"templates\",\"data\":[{\"label\":\"...\",\"worldPrompt\":\"...\"}]}")
            }
        }
        try {
            val result = llmRetry.chatCompletionWithRetry(
                secureStorage.publicApiKey, secureStorage.publicBaseUrl, secureStorage.publicModel,
                listOf(
                    ChatMessage("system", "你是格式转换专家，必须严格返回纯JSON，不包含任何Markdown标记。"),
                    ChatMessage("user", prompt),
                ),
                temperature = 0.3f,
                maxTokens = 4000,
            )
            return extractJson(result)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SmartImportException) {
            throw failure
        } catch (failure: Exception) {
            throw SmartImportException(smartImportFailureMessage(failure), failure)
        }
    }

    private fun extractJson(text: String): String {
        val start = text.indexOf("{")
        val startArray = text.indexOf("[")
        val end = text.lastIndexOf("}")
        val endArray = text.lastIndexOf("]")
        
        val startIndex = if (start != -1 && startArray != -1) minOf(start, startArray)
                         else if (start != -1) start
                         else startArray
                         
        val endIndex = if (end != -1 && endArray != -1) maxOf(end, endArray)
                       else if (end != -1) end
                       else endArray

        if (startIndex == -1 || endIndex == -1 || endIndex < startIndex) {
            throw SmartImportException("模型未返回有效 JSON，请重试")
        }
        val candidate = text.substring(startIndex, endIndex + 1).trim()
        try {
            val parsed = JsonParser.parseString(candidate)
            if (!parsed.isJsonObject && !parsed.isJsonArray) throw IllegalArgumentException("root")
        } catch (failure: Exception) {
            throw SmartImportException("模型未返回有效 JSON，请重试", failure)
        }
        return candidate
    }

    private fun smartImportFailureMessage(failure: Throwable): String = when {
        failure is LlmHttpException && failure.status in setOf(401, 403) ->
            "模型服务认证失败，请检查 API Key"
        failure is SocketTimeoutException || failure is TimeoutException ||
            failure.message?.contains("timeout", ignoreCase = true) == true ->
            "模型服务请求超时，请检查网络后重试"
        else -> "模型解析失败，请重试"
    }
}
