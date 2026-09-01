package com.mojing.app.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImageSaver @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    suspend fun saveToGallery(
        filePath: String,
        originalName: String,
        mimeType: String,
    ): Uri? = withContext(Dispatchers.IO) {
        var pendingUri: Uri? = null
        var pendingFile: File? = null
        try {
            val file = File(filePath)
            if (!file.isFile || file.length() <= 0L) return@withContext null
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val safeMimeType = mimeType.takeIf { it.startsWith("image/") } ?: when (file.extension.lowercase(Locale.ROOT)) {
                "jpg", "jpeg" -> "image/jpeg"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                else -> "image/png"
            }
            val extension = when (safeMimeType.lowercase(Locale.ROOT)) {
                "image/jpeg" -> "jpg"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                else -> file.extension.takeIf { it.isNotBlank() } ?: "png"
            }
            val originalBase = originalName.substringBeforeLast('.').ifBlank { file.nameWithoutExtension }
            val safeBase = originalBase.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(48).ifBlank { "图片" }
            val displayName = "墨境_${timestamp}_${safeBase}.$extension"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, safeMimeType)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/墨境")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext null
                pendingUri = uri
                val output = context.contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException("无法打开系统相册写入流")
                output.use { copyFile(file, it) }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                if (context.contentResolver.update(uri, values, null, null) <= 0) {
                    throw IllegalStateException("系统相册未确认图片写入")
                }
                pendingUri = null
                uri
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES + "/墨境")
                if (!dir.exists() && !dir.mkdirs()) return@withContext null
                val outFile = File(dir, displayName)
                pendingFile = outFile
                outFile.outputStream().use { copyFile(file, it) }
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(outFile.absolutePath),
                    arrayOf(safeMimeType),
                    null,
                )
                pendingFile = null
                Uri.fromFile(outFile)
            }
        } catch (cancelled: CancellationException) {
            pendingUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            pendingFile?.let { runCatching { it.delete() } }
            throw cancelled
        } catch (_: Exception) {
            pendingUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            pendingFile?.let { runCatching { it.delete() } }
            null
        }
    }

    private suspend fun copyFile(source: File, output: java.io.OutputStream) {
        source.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            output.flush()
        }
    }
}
