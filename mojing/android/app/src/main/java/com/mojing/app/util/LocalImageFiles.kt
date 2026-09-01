package com.mojing.app.util

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/** 将系统图片选择器返回的内容有限、可取消地保存到应用私有目录。 */
object LocalImageFiles {
    const val MAX_IMPORT_BYTES: Long = 32L * 1024L * 1024L

    suspend fun copyAvatar(context: Context, uri: Uri): String =
        copyUriToPrivateImage(context, uri, "avatar_")

    suspend fun copyCover(context: Context, uri: Uri): String =
        copyUriToPrivateImage(context, uri, "cover_")

    private suspend fun copyUriToPrivateImage(
        context: Context,
        uri: Uri,
        prefix: String,
    ): String {
        var copiedPath: String? = null
        var handedOff = false
        try {
            withContext(Dispatchers.IO) {
                val extension = extensionForMime(context.contentResolver.getType(uri).orEmpty())
                copiedPath = context.contentResolver.openInputStream(uri)?.use { input ->
                    copyInputToPrivateImage(
                        input = input,
                        filesDir = context.filesDir,
                        prefix = prefix,
                        extension = extension,
                        maxBytes = MAX_IMPORT_BYTES,
                    )
                } ?: throw IllegalStateException("无法读取所选图片")
            }
            handedOff = true
            return requireNotNull(copiedPath)
        } finally {
            if (!handedOff) {
                copiedPath?.let { path ->
                    withContext(NonCancellable + Dispatchers.IO) { File(path).delete() }
                }
            }
        }
    }

    internal suspend fun copyInputToPrivateImage(
        input: InputStream,
        filesDir: File,
        prefix: String,
        extension: String,
        maxBytes: Long = MAX_IMPORT_BYTES,
    ): String {
        require(prefix == "avatar_" || prefix == "cover_")
        require(extension.matches(Regex("[a-z0-9]{2,5}")))
        val target = File.createTempFile(prefix, ".$extension", filesDir)
        ChatAttachmentFiles.copyInputToFile(
            input = input,
            out = target,
            maxBytes = maxBytes,
            oversizeMessage = "图片超过 ${maxBytes / 1024 / 1024} MB 上限",
        )
        return target.absolutePath
    }

    private fun extensionForMime(mime: String): String = when {
        mime.contains("png", ignoreCase = true) -> "png"
        mime.contains("webp", ignoreCase = true) -> "webp"
        mime.contains("gif", ignoreCase = true) -> "gif"
        mime.contains("heic", ignoreCase = true) -> "heic"
        mime.contains("heif", ignoreCase = true) -> "heif"
        else -> "jpg"
    }
}
