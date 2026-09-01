package com.mojing.app.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

object ChatAttachmentFiles {

    /**
     * Performs file I/O; call from [kotlinx.coroutines.Dispatchers.IO].
     * @param maxBytes 单文件最大字节；默认不限制。超出时删除已写入文件并抛出异常（提示文案由调用方展示）。
     */
    suspend fun copyUriToSessionFile(
        context: Context,
        uri: Uri,
        sessionId: Long,
        maxBytes: Long = Long.MAX_VALUE,
    ): String {
        require(sessionId > 0L) { "对话不存在，无法添加图片" }
        val mime = context.contentResolver.getType(uri).orEmpty()
        val ext = when {
            mime.contains("png") -> "png"
            mime.contains("webp") -> "webp"
            mime.contains("gif") -> "gif"
            else -> "jpg"
        }
        val dir = File(context.filesDir, "attachments/$sessionId")
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IllegalStateException("无法创建图片存储目录")
        }
        val out = File(dir, "attach_${UUID.randomUUID()}.$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            copyInputToFile(input, out, maxBytes)
        } ?: throw IllegalStateException("无法读取所选图片")
        return out.absolutePath
    }

    internal suspend fun copyInputToFile(
        input: InputStream,
        out: File,
        maxBytes: Long = Long.MAX_VALUE,
        oversizeMessage: String? = null,
        emptyMessage: String = "所选图片为空",
    ) {
        var completed = false
        try {
            out.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var totalBytes = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    val nextTotal = totalBytes + read
                    if (maxBytes < Long.MAX_VALUE && nextTotal > maxBytes) {
                        throw IllegalStateException(
                            oversizeMessage
                                ?: "图片超过 ${maxBytes / 1024 / 1024}MB 上限（可在设置 → 默认配置修改）",
                        )
                    }
                    output.write(buffer, 0, read)
                    totalBytes = nextTotal
                }
                if (totalBytes == 0L) throw IllegalStateException(emptyMessage)
            }
            completed = true
        } finally {
            if (!completed) out.delete()
        }
    }

    fun validPendingPaths(
        context: Context,
        sessionId: Long,
        paths: List<String>,
        maxBytes: Long = Long.MAX_VALUE,
    ): List<String> = paths.distinct().filter { path ->
        val file = ownedSessionFileOrNull(context, sessionId, path) ?: return@filter false
        file.isFile && file.canRead() && file.length() > 0L &&
            (maxBytes == Long.MAX_VALUE || file.length() <= maxBytes)
    }

    fun deleteOwnedPendingFile(context: Context, sessionId: Long, path: String): Boolean {
        val file = ownedSessionFileOrNull(context, sessionId, path) ?: return false
        return !file.exists() || file.delete()
    }

    /**
     * 删除已无数据库引用的应用私有聊天媒体。外部路径按“无需处理”返回成功，绝不越界删除。
     */
    fun deleteOwnedPersistedMediaFile(context: Context, sessionId: Long, path: String): Boolean {
        val file = ownedSessionFileOrNull(context, sessionId, path)
            ?: ownedTtsFileOrNull(context, path)
            ?: return true
        return !file.exists() || file.delete()
    }

    private fun ownedSessionFileOrNull(context: Context, sessionId: Long, path: String): File? {
        if (sessionId <= 0L || path.isBlank()) return null
        return runCatching {
            val dir = File(context.filesDir, "attachments/$sessionId").canonicalFile
            val file = File(path).canonicalFile
            file.takeIf { candidate -> candidate.parentFile == dir }
        }.getOrNull()
    }

    private fun ownedTtsFileOrNull(context: Context, path: String): File? {
        if (path.isBlank()) return null
        return runCatching {
            val dir = File(context.filesDir, "tts_cache").canonicalFile
            val file = File(path).canonicalFile
            file.takeIf { candidate -> candidate.parentFile == dir }
        }.getOrNull()
    }
}
