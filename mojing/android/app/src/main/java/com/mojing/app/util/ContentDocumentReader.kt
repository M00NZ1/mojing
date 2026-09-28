package com.mojing.app.util

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** 对系统文件选择器返回的内容做可取消、有限大小的后台读取。 */
object ContentDocumentReader {
    const val CHARACTER_IMPORT_MAX_BYTES: Int = 32 * 1024 * 1024
    const val STRUCTURED_TEXT_IMPORT_MAX_BYTES: Int = 16 * 1024 * 1024
    const val WORLD_INFO_IMPORT_MAX_BYTES: Int = 64 * 1024 * 1024

    suspend fun readBytes(context: Context, uri: Uri, maxBytes: Int): ByteArray = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            readBytes(input, maxBytes)
        } ?: throw IllegalStateException("无法读取所选文件")
    }

    suspend fun readUtf8Text(context: Context, uri: Uri, maxBytes: Int): String =
        readBytes(context, uri, maxBytes).toString(Charsets.UTF_8)

    suspend fun <T> readStream(
        context: Context,
        uri: Uri,
        reader: suspend (InputStream) -> T,
    ): T = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.buffered()?.use { input -> reader(input) }
            ?: throw IllegalStateException("无法读取所选文件")
    }

    internal suspend fun readBytes(input: InputStream, maxBytes: Int): ByteArray {
        require(maxBytes > 0)
        val output = ByteArrayOutputStream(minOf(maxBytes, BUFFER_SIZE))
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            if (read > maxBytes - total) {
                val maxMiB = maxBytes / (1024 * 1024)
                throw IllegalArgumentException("文件超过 ${maxMiB} MB 上限")
            }
            output.write(buffer, 0, read)
            total += read
        }
        return output.toByteArray()
    }

    private const val BUFFER_SIZE = 64 * 1024
}
