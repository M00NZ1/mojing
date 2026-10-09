package com.mojing.app.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable

object ChatAttachmentFiles {

    private val requestIdPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")

    private fun bundleSessionDirectory(context: Context, sessionId: Long): File? {
        if (sessionId <= 0) return null
        val base = context.filesDir.canonicalFile
        val attachments = File(base, "attachments")
        val session = File(attachments, sessionId.toString())
        if (Files.isSymbolicLink(attachments.toPath()) || Files.isSymbolicLink(session.toPath())) return null
        return session.canonicalFile.takeIf {
            it.parentFile == attachments && attachments.parentFile == base
        }
    }

    /** Exact request-owned file name; callers must retain the request UUID for cleanup. */
    fun requestMediaFile(context: Context, sessionId: Long, requestId: String, ordinal: Int): File {
        require(sessionId > 0L && ordinal >= 0) { "媒体文件归属无效" }
        val normalizedRequestId = normalizedRequestId(requestId)
        val dir = bundleSessionDirectory(context, sessionId) ?: error("媒体存储目录无效")
        if (!dir.isDirectory && !dir.mkdirs()) throw IllegalStateException("无法创建媒体存储目录")
        require(!Files.isSymbolicLink(dir.toPath())) { "媒体存储目录无效" }
        return File(dir, "bundle_${normalizedRequestId}_$ordinal.bin")
    }

    /** Copies one stream into an exact request-owned path and removes partial output on failure. */
    suspend fun copyInputToRequestFile(
        context: Context,
        sessionId: Long,
        requestId: String,
        ordinal: Int,
        input: InputStream,
        maxBytes: Long = Long.MAX_VALUE,
    ): String {
        val out = requestMediaFile(context, sessionId, requestId, ordinal)
        require(!out.exists() && !Files.isSymbolicLink(out.toPath())) { "媒体请求文件已存在" }
        copyInputToFile(
            input,
            out,
            maxBytes,
            oversizeMessage = "媒体文件超过大小上限",
            emptyMessage = "媒体文件为空",
            createNew = true,
        )
        check(ownedRequestFileOrNull(context, sessionId, requestId, out.absolutePath) != null) {
            "媒体请求文件路径无效"
        }
        return out.absolutePath
    }

    /** Enumerates only regular, non-symlink files for this exact request prefix. */
    fun enumerateRequestFiles(context: Context, sessionId: Long, requestId: String): List<File> {
        val normalizedRequestId = normalizedRequestId(requestId)
        val canonicalDir = runCatching { bundleSessionDirectory(context, sessionId) }.getOrNull() ?: return emptyList()
        val prefix = "bundle_${normalizedRequestId}_"
        return requestFileStream(canonicalDir, prefix).toList()
    }

    /** Lists exact UUIDs for explicit crash-recovery selection; callers still choose each ID. */
    fun enumerateRequestIds(context: Context, sessionId: Long): List<String> {
        val dir = bundleSessionDirectory(context, sessionId) ?: return emptyList()
        return runCatching {
            Files.newDirectoryStream(dir.toPath()).use { stream ->
                stream.asSequence()
                    .mapNotNull { path -> requestFileRequestId(path.fileName.toString()) }
                    .distinct()
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Crash/cancel cleanup for one explicitly named request. The database reference check runs
     * immediately before every delete and the whole cleanup remains cancellation-safe.
     */
    suspend fun cleanupRequestFiles(
        context: Context,
        sessionId: Long,
        requestId: String,
        isReferenced: suspend (String) -> Boolean,
    ): Int = withContext(NonCancellable) {
        var deleted = 0
        val dir = bundleSessionDirectory(context, sessionId) ?: return@withContext 0
        val prefix = "bundle_${normalizedRequestId(requestId)}_"
        requestFileStream(dir, prefix).forEach { file ->
            if (!isReferenced(file.absolutePath) && file.delete()) deleted++
        }
        deleted
    }

    /** Explicit crash-recovery sweep; active request UUIDs are never considered orphaned. */
    suspend fun cleanupOrphanedBundleFiles(
        context: Context,
        sessionId: Long,
        activeRequestIds: Set<String>,
        isReferenced: suspend (String) -> Boolean,
    ): Int = withContext(NonCancellable) {
        val dir = bundleSessionDirectory(context, sessionId) ?: return@withContext 0
        if (!dir.isDirectory || Files.isSymbolicLink(dir.toPath())) return@withContext 0
        var deleted = 0
        Files.newDirectoryStream(dir.toPath()).use { stream ->
            for (path in stream) {
                val file = path.toFile()
                val fileRequestId = requestFileRequestId(file.name) ?: continue
                if (fileRequestId in activeRequestIds || file.parentFile?.canonicalFile != dir ||
                    Files.isSymbolicLink(path) || !file.isFile || !file.canRead()
                ) continue
                if (!isReferenced(file.absolutePath) && fileRequestId !in activeRequestIds && file.delete()) deleted++
            }
        }
        deleted
    }

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
        createNew: Boolean = false,
    ) {
        var completed = false
        var createdByCall = false
        try {
            val output = if (createNew) {
                Channels.newOutputStream(
                    Files.newByteChannel(out.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                ).also { createdByCall = true }.buffered()
            } else {
                FileOutputStream(out, false).buffered()
            }
            output.use { outputStream ->
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
                    outputStream.write(buffer, 0, read)
                    totalBytes = nextTotal
                }
                if (totalBytes == 0L) throw IllegalStateException(emptyMessage)
            }
            completed = true
        } finally {
            if (!completed && (createdByCall || !createNew)) out.delete()
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

    private fun ownedRequestFileOrNull(
        context: Context,
        sessionId: Long,
        requestId: String,
        path: String,
    ): File? {
        val normalizedRequestId = runCatching { normalizedRequestId(requestId) }.getOrNull() ?: return null
        val directory = bundleSessionDirectory(context, sessionId) ?: return null
        val file = File(path)
        if (Files.isSymbolicLink(file.toPath()) || file.canonicalFile.parentFile != directory) return null
        val prefix = "bundle_${normalizedRequestId}_"
        return file.takeIf {
            it.name.startsWith(prefix) &&
                it.name.removePrefix(prefix).matches(Regex("[0-9]+\\.bin")) &&
                !Files.isSymbolicLink(it.toPath()) && it.isFile
        }
    }

    private fun normalizedRequestId(requestId: String): String {
        require(requestIdPattern.matches(requestId)) { "媒体请求标识必须是 UUID" }
        return UUID.fromString(requestId).toString()
    }

    private fun requestFileStream(dir: File, prefix: String): Sequence<File> = sequence {
        if (!dir.isDirectory || Files.isSymbolicLink(dir.toPath())) return@sequence
        Files.newDirectoryStream(dir.toPath()).use { stream ->
            for (path in stream) {
                val file = path.toFile()
                if (file.name.startsWith(prefix) &&
                    file.name.removePrefix(prefix).matches(Regex("[0-9]+\\.bin")) &&
                    file.parentFile?.canonicalFile == dir &&
                    !Files.isSymbolicLink(path) && file.isFile && file.canRead()
                ) yield(file)
            }
        }
    }

    private fun requestFileRequestId(name: String): String? {
        val match = Regex("^bundle_($requestIdPattern)_[0-9]+\\.bin$").matchEntire(name) ?: return null
        return runCatching { normalizedRequestId(match.groupValues[1]) }.getOrNull()
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
