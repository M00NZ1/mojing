package com.mojing.app.util

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.OutputStream

/** 对系统文档选择器返回的目标执行可取消的后台分块写入。 */
object ContentDocumentWriter {
    suspend fun writeBytes(context: Context, uri: Uri, bytes: ByteArray) = withContext(Dispatchers.IO) {
        writeToUri(context, uri) { output -> writeBytes(output, bytes) }
    }

    suspend fun writeUtf8Text(context: Context, uri: Uri, text: String) = withContext(Dispatchers.IO) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        writeToUri(context, uri) { output -> writeBytes(output, bytes) }
    }

    suspend fun <T> writeStream(
        context: Context,
        uri: Uri,
        writer: suspend (OutputStream) -> T,
    ): T = withContext(Dispatchers.IO) {
        writeToUri(context, uri, writer)
    }

    private suspend fun <T> writeToUri(
        context: Context,
        uri: Uri,
        writer: suspend (OutputStream) -> T,
    ): T {
        val target = context.contentResolver.openOutputStream(uri, "w")
            ?: throw IllegalStateException("无法打开导出文件")
        return target.buffered().use { output ->
            writeStream(output, writer)
        }
    }

    internal suspend fun <T> writeStream(
        output: OutputStream,
        writer: suspend (OutputStream) -> T,
    ): T {
        currentCoroutineContext().ensureActive()
        val result = writer(output)
        currentCoroutineContext().ensureActive()
        output.flush()
        return result
    }

    internal suspend fun writeBytes(
        output: OutputStream,
        bytes: ByteArray,
        chunkSize: Int = DEFAULT_CHUNK_BYTES,
    ) {
        require(chunkSize > 0)
        var offset = 0
        while (offset < bytes.size) {
            currentCoroutineContext().ensureActive()
            val count = minOf(chunkSize, bytes.size - offset)
            output.write(bytes, offset, count)
            offset += count
        }
    }

    private const val DEFAULT_CHUNK_BYTES = 64 * 1024
}
