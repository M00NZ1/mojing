package com.mojing.app.domain.story

import com.mojing.app.data.remote.ChatMessage
import com.mojing.app.domain.engine.LlmRetry
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import javax.inject.Inject
import javax.inject.Singleton

data class StoryWritingRequest(
    val premise: String,
    val direction: String = "",
    val tone: String = "",
    val worldContext: String = "",
    val characterContext: String = "",
    val chapterCount: Int = 2,
    val personaName: String = "玩家",
    val userDescription: String = "",
)

data class StoryChapter(
    val number: Int,
    val title: String,
    val content: String,
)

data class StoryWritingResult(
    val title: String,
    val chapters: List<StoryChapter>,
    val nextChoices: List<String>,
)

data class StoryWritingProgress(
    val stage: String,
    val model: String,
    val elapsedMs: Long,
    val firstContentDelayMs: Long? = null,
    val receivedChars: Int = 0,
    val preview: String = "",
    val attempt: Int = 1,
    val retryDelayMs: Long? = null,
)

class StoryWritingException(message: String) : IllegalStateException(message)

/** 只负责生成小说正文；会话、角色绑定和导航仍由已有会话领域负责。 */
@Singleton
class StoryWritingUseCase @Inject constructor(
    private val llmRetry: LlmRetry,
) {
    suspend fun write(
        apiKey: String,
        baseUrl: String,
        model: String,
        request: StoryWritingRequest,
        onProgress: (StoryWritingProgress) -> Unit = {},
    ): StoryWritingResult {
        val premise = request.premise.trim()
        if (premise.isBlank()) throw StoryWritingException("请先填写故事背景")
        val chapterCount = request.chapterCount.coerceIn(1, 3)
        val prompt = buildString {
            appendLine("请把用户提供的故事背景直接写成长篇小说开篇，共 $chapterCount 章。")
            appendLine("只返回合法 JSON，不要 Markdown 或解释。")
            appendLine("JSON 格式：{\"title\":\"小说名\",\"chapters\":[{\"title\":\"第一章标题\",\"content\":\"完整小说正文\"}],\"next_choices\":[\"后续走向一\",\"后续走向二\"]}。")
            appendLine("即使只生成 1 章，chapters 也必须是只含一个章节对象的数组；next_choices 始终是字符串数组。")
            appendLine("要求：这是小说正文，不是大纲、分析或候选方案；每章以 1200 个中文字符为目标，在 900～1800 字内收束，包含场景、动作、人物对话、心理与因果推进，章节连续。")
            appendLine("最后一章停在可继续的位置；next_choices 根据刚写出的情节动态生成 2～4 个不同后续走向。")
            appendLine(StoryCanon.promptRules)
            StoryCanon.modelInstruction(model).takeIf(String::isNotEmpty)?.let { appendLine(it) }
            appendLine("故事背景：\n$premise")
            request.direction.trim().takeIf(String::isNotEmpty)?.let { appendLine("开篇剧情走向：\n$it") }
            request.tone.trim().takeIf(String::isNotEmpty)?.let { appendLine("文风与节奏：$it") }
            request.worldContext.trim().takeIf(String::isNotEmpty)?.let { appendLine("世界设定：\n$it") }
            request.characterContext.trim().takeIf(String::isNotEmpty)?.let { appendLine("已有角色：\n$it") }
            request.userDescription.trim().takeIf(String::isNotBlank)?.let {
                appendLine("用户资料：\n姓名：${request.personaName.ifBlank { "玩家" }}\n自我描述：$it")
            } ?: appendLine("用户姓名：${request.personaName.ifBlank { "玩家" }}")
            appendLine("提交前检查：恰好 $chapterCount 章，每章不超过 1800 字。使用 JSON 字符串转义换行和双引号，返回完整对象。")
        }
        val startedAt = System.nanoTime()
        fun elapsed() = (System.nanoTime() - startedAt) / 1_000_000L
        val requestId = java.util.UUID.randomUUID().toString().take(8)
        val host = runCatching { java.net.URI(baseUrl).host }.getOrNull().orEmpty()
        val modelTag = java.security.MessageDigest.getInstance("SHA-256").digest(model.toByteArray())
            .take(6).joinToString("") { "%02x".format(it) }
        fun log(message: String) = com.mojing.app.util.UsbSessionLog.i("StoryWriting", "request=$requestId $message")
        log("start host=$host modelTag=$modelTag chapters=$chapterCount promptChars=${prompt.length}")
        var firstContentMs: Long? = null
        var lastPublishedMs = -100L
        var attempt = 1
        val previewParser = StoryStreamingPreviewParser()
        fun publish(stage: String, retryDelay: Long? = null) {
            lastPublishedMs = elapsed()
            onProgress(StoryWritingProgress(stage, model, lastPublishedMs, firstContentMs,
                previewParser.receivedChars, previewParser.previewText(), attempt, retryDelay))
        }
        val raw = try {
            llmRetry.chatCompletionStreamingWithRetry(
                apiKey = apiKey, baseUrl = baseUrl, model = model,
                messages = listOf(
                    ChatMessage("system", "你是长篇小说作者。直接写连续小说正文，并严格遵守 JSON 输出协议。"),
                    ChatMessage("user", prompt),
                ),
                temperature = StoryCanon.temperatureFor(model),
                maxTokens = (chapterCount * 2_600 + 2_000).coerceAtMost(10_000),
                onDelta = { delta ->
                    previewParser.append(delta)
                    if (previewParser.receivedChars > 0 && firstContentMs == null) {
                        firstContentMs = elapsed()
                        log("firstContent elapsedMs=$firstContentMs")
                        publish("接收正文")
                    } else if (elapsed() - lastPublishedMs >= 100L) publish("接收正文")
                },
                onRetry = { nextAttempt, delayMs -> attempt = nextAttempt; log("retry attempt=$attempt delayMs=$delayMs elapsedMs=${elapsed()}"); publish("等待第 $nextAttempt 次请求", delayMs) },
                onAttempt = { current -> attempt = current; publish("等待模型响应") },
            )
        } catch (error: Exception) {
            publish(if (error is kotlinx.coroutines.CancellationException) "已停止" else "失败")
            log("ended elapsedMs=${elapsed()} receivedChars=${previewParser.receivedChars} ${com.mojing.app.domain.engine.LlmFailureDiagnostics.summary(error)}")
            throw error
        }
        publish("校验完整正文")
        return parse(raw, chapterCount, premise).also {
            log("complete elapsedMs=${elapsed()} firstContentMs=${firstContentMs ?: -1} receivedChars=${previewParser.receivedChars}")
        }
    }

    internal fun parse(raw: String, expectedChapterCount: Int, premise: String = ""): StoryWritingResult {
        val root = extractJsonObject(raw) ?: throw StoryWritingException("模型没有返回可识别的小说正文")
        val chapterValue = root.get("chapters")
        val chapterItems = when {
            chapterValue == null || chapterValue.isJsonNull -> throw StoryWritingException("模型返回缺少章节正文，请重试")
            chapterValue.isJsonArray -> chapterValue.asJsonArray.toList()
            chapterValue.isJsonObject && expectedChapterCount == 1 -> listOf(chapterValue)
            else -> throw StoryWritingException("模型返回的章节格式不正确，请重试")
        }
        if (chapterItems.size != expectedChapterCount) {
            throw StoryWritingException("模型没有返回完整的 $expectedChapterCount 章正文，请重试")
        }
        val chapters = chapterItems.mapIndexed { index, element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject
                ?: throw StoryWritingException("模型返回的章节格式不完整")
            val content = obj.string("content").ifBlank { obj.string("narrative") }.trim()
            if (content.isBlank()) throw StoryWritingException("模型返回了空章节，请重试")
            StoryChapter(
                number = index + 1,
                title = obj.string("title").trim().ifBlank { "第 ${index + 1} 章" },
                content = content,
            )
        }
        val choiceValue = root.get("next_choices")
        if (choiceValue != null && !choiceValue.isJsonNull && !choiceValue.isJsonArray) {
            throw StoryWritingException("模型返回的后续剧情选项格式不正确，请重试")
        }
        val choices = StoryCanon.filterChoices(choiceValue?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { value -> value.isJsonPrimitive }?.asString?.trim()?.takeIf(String::isNotEmpty) }
            .orEmpty(), premise).take(4)
        if (choices.size < 2) throw StoryWritingException("模型没有生成可用的后续剧情选项，请重试")
        return StoryWritingResult(
            title = root.string("title").trim().ifBlank { chapters.first().title },
            chapters = chapters,
            nextChoices = choices,
        )
    }

    fun toMessageContent(chapter: StoryChapter, choices: List<String> = emptyList()): String = buildString {
        append("<NARRATION>")
        append("${chapter.title}\n\n${chapter.content}")
        append("</NARRATION>")
        if (choices.isNotEmpty()) {
            append("\n<CHOICES>")
            choices.forEach { append("<OPTION>${escapeXml(it)}</OPTION>") }
            append("</CHOICES>")
        }
    }

    fun toStructuredJson(chapter: StoryChapter, choices: List<String>): String = Gson().toJson(
        mapOf(
            "mode" to "story_writing",
            "chapter_number" to chapter.number,
            "chapter_title" to chapter.title,
            "choices" to choices,
        ),
    )

    private fun extractJsonObject(raw: String): JsonObject? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JsonParser.parseString(raw.substring(start, end + 1)).asJsonObject }.getOrNull()
    }

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun JsonObject.string(name: String): String =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
}
