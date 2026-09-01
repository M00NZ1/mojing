package com.mojing.app.domain.chat

import com.mojing.app.data.local.entity.MessageEntity
import com.google.gson.stream.JsonWriter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.OutputStream
import java.io.OutputStreamWriter

/** 以固定上界分页写出主线消息，避免同时持有完整实体列表、JSON 树、字符串和字节数组。 */
object MainBranchChatExportWriter {
    const val PAGE_SIZE = 256

    suspend fun write(
        output: OutputStream,
        sessionId: Long,
        exportedAt: Long,
        maxMessageId: Long,
        loadPage: suspend (afterMessageId: Long, maxMessageId: Long, limit: Int) -> List<MessageEntity>,
    ): Long {
        require(sessionId > 0L)
        require(maxMessageId >= 0L)

        val json = JsonWriter(OutputStreamWriter(output, Charsets.UTF_8)).apply {
            isHtmlSafe = true
        }
        var cursor = 0L
        var written = 0L

        json.beginObject()
        json.name("format").value("mojing_chat_export")
        json.name("version").value(1L)
        json.name("sessionId").value(sessionId)
        json.name("exportedAt").value(exportedAt)
        json.name("messages").beginArray()

        while (cursor < maxMessageId) {
            currentCoroutineContext().ensureActive()
            val page = loadPage(cursor, maxMessageId, PAGE_SIZE)
            currentCoroutineContext().ensureActive()
            if (page.isEmpty()) break
            require(page.size <= PAGE_SIZE)
            var previousId = cursor
            page.forEach { message ->
                currentCoroutineContext().ensureActive()
                require(message.sessionId == sessionId)
                require(message.branchId == "main")
                require(message.id > previousId && message.id <= maxMessageId)
                writeMessage(json, message)
                previousId = message.id
                written += 1
            }
            cursor = previousId
            if (page.size < PAGE_SIZE) break
        }

        json.endArray()
        json.endObject()
        json.flush()
        return written
    }

    private fun writeMessage(json: JsonWriter, message: MessageEntity) {
        json.beginObject()
        json.name("id").value(message.id)
        json.name("sessionId").value(message.sessionId)
        json.name("speakerType").value(message.speakerType)
        message.characterId?.let { json.name("characterId").value(it) }
        json.name("branchId").value(message.branchId)
        json.name("content").value(message.content)
        json.name("structuredContentJson").value(message.structuredContentJson)
        json.name("includeInContext").value(message.includeInContext)
        json.name("createdAt").value(message.createdAt)
        json.endObject()
    }
}
