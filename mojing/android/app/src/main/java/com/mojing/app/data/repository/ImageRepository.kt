package com.mojing.app.data.repository

import android.content.Context
import com.mojing.app.data.remote.ImageApiService
import com.mojing.app.domain.billing.CostRecorder
import com.mojing.app.media.ImageSaver
import com.mojing.app.media.CharacterCardImageProcessor
import com.mojing.app.util.ApiRootLines
import com.mojing.app.util.UsbSessionLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImageRepository @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val imageApiService: ImageApiService,
    private val imageSaver: ImageSaver,
    private val costRecorder: CostRecorder,
) {
    suspend fun generateImage(
        prompt: String,
        apiKey: String,
        baseUrl: String,
        model: String,
        size: String = "1024x1024",
        quality: String = "standard",
        sessionId: Long? = null,
        characterId: Long? = null,
        onUsedBase: ((String) -> Unit)? = null,
    ): Result<String> {
        val bases = ApiRootLines.splitToOrderedDistinct(baseUrl, imageApiService::normalizeImageBase)
        val t0 = System.currentTimeMillis()
        var lastFailure: Exception? = null
        for ((i, b) in bases.withIndex()) {
            try {
                val imageUrl = imageApiService.generateImage(
                    apiKey = apiKey,
                    baseUrl = b,
                    prompt = prompt,
                    model = model,
                    size = size,
                    quality = quality,
                )
                if (imageUrl != null) {
                    if (i > 0) {
                        UsbSessionLog.i("ImageGen", "fallback line ok model=$model base=${b.take(48)}…")
                    }
                    onUsedBase?.invoke(b)
                    val elapsed = (System.currentTimeMillis() - t0).toInt()
                    costRecorder.recordImage(sessionId, characterId, model, true, elapsed)
                    return Result.success(imageUrl)
                }
                UsbSessionLog.w("ImageGen", "no url model=$model base=${b.take(48)}…")
                lastFailure = Exception("图片生成失败：未返回图片地址")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UsbSessionLog.w("ImageGen", "line failed model=$model base=${b.take(48)}… err=${e.message}")
                lastFailure = e
            }
        }
        val elapsed = (System.currentTimeMillis() - t0).toInt()
        costRecorder.recordImage(sessionId, characterId, model, false, elapsed)
        return Result.failure(lastFailure ?: Exception("图片生成失败"))
    }

    suspend fun generateAndSaveImageForCover(
        prompt: String,
        apiKey: String,
        baseUrl: String,
        model: String,
        size: String = "1024x1024",
        quality: String = "standard",
        sessionId: Long? = null,
        characterId: Long? = null,
        onUsedBase: ((String) -> Unit)? = null,
    ): Result<String> {
        val imageUrl = generateImage(
            prompt = prompt,
            apiKey = apiKey,
            baseUrl = baseUrl,
            model = model,
            size = size,
            quality = quality,
            sessionId = sessionId,
            characterId = characterId,
            onUsedBase = onUsedBase,
        ).getOrElse { return Result.failure(it) }
        return downloadAndSaveImageForCover(imageUrl)
    }

    suspend fun saveLocalImageToGallery(
        filePath: String,
        originalName: String,
        mimeType: String,
    ): Result<Unit> {
        return try {
            val uri = imageSaver.saveToGallery(filePath, originalName, mimeType)
                ?: return Result.failure(Exception("图片未能写入系统相册"))
            UsbSessionLog.i("ImageSave", "saved local image to gallery uri=${uri.scheme.orEmpty()}")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadAndSaveImageForCover(imageUrl: String): Result<String> {
        return try {
            val localPath = materializeGeneratedImage(imageUrl)
                ?: return Result.failure(Exception("图片下载失败"))
            try {
                val finalPath = CharacterCardImageProcessor.processAndSaveFromFilePath(
                    appContext,
                    localPath,
                )
                if (finalPath != null) {
                    Result.success(finalPath)
                } else {
                    Result.failure(Exception("图片裁切保存失败"))
                }
            } finally {
                runCatching { File(localPath).delete() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    internal suspend fun materializeGeneratedImage(source: String): String? {
        if (source.startsWith("http://", ignoreCase = true) ||
            source.startsWith("https://", ignoreCase = true)
        ) {
            return imageApiService.downloadImage(source)
        }
        var tempFile: File? = null
        return try {
            withContext(Dispatchers.IO) {
                val encoded = source.substringAfter("base64,", source).trim()
                val bytes = java.util.Base64.getMimeDecoder().decode(encoded)
                val file = File.createTempFile("generated_cover_", ".png", appContext.cacheDir)
                tempFile = file
                file.writeBytes(bytes)
                file.absolutePath
            }
        } catch (e: CancellationException) {
            tempFile?.let { runCatching { it.delete() } }
            throw e
        } catch (_: Exception) {
            tempFile?.let { runCatching { it.delete() } }
            null
        }
    }

    /**
     * 将生图结果统一物化到会话附件目录。写入完成前由本方法持有文件；取消或失败时不会留下半成品。
     */
    suspend fun saveGeneratedImageForSession(source: String, sessionId: Long): String? {
        val materializedPath = materializeGeneratedImage(source) ?: return null
        val materializedFile = File(materializedPath)
        var targetFile: File? = null
        return try {
            withContext(Dispatchers.IO) {
                val attachmentDir = File(appContext.filesDir, "attachments/$sessionId").apply { mkdirs() }
                val target = File.createTempFile("gen_", ".png", attachmentDir)
                targetFile = target
                materializedFile.inputStream().use { input ->
                    target.outputStream().use(input::copyTo)
                }
                target.absolutePath
            }
        } catch (e: CancellationException) {
            targetFile?.let { runCatching { it.delete() } }
            throw e
        } catch (_: Exception) {
            targetFile?.let { runCatching { it.delete() } }
            null
        } finally {
            runCatching { materializedFile.delete() }
        }
    }

}
