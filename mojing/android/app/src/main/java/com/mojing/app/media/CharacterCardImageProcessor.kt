package com.mojing.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import com.mojing.app.util.ChatAttachmentFiles
import com.mojing.app.util.UsbSessionLog
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.InputStream
import kotlin.math.ceil
import kotlin.math.floor
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 从相册选取的图做 **竖版角色卡比例（2:3）居中裁切** 并压缩保存到应用私有目录。
 * 不依赖第三方裁剪 Activity，等价于「固定比例自动裁切」。
 *
 * 实现说明：先将 URI 复制到应用缓存再解码，避免部分 ContentProvider 不支持「先读 bounds 再二次 open」导致解码失败。
 */
object CharacterCardImageProcessor {

    private const val TARGET_ASPECT_W = 2f
    private const val TARGET_ASPECT_H = 3f
    private const val MAX_DECODE_SIDE = 2400
    private const val OUTPUT_MAX_HEIGHT = 1280
    private const val JPEG_QUALITY = 88
    const val MAX_IMPORT_BYTES: Long = 32L * 1024L * 1024L

    /**
     * 解码相册图片供交互裁切（不写入卡图）。调用方须在不再需要时 [Bitmap.recycle]。
     */
    suspend fun decodeBitmapFromUri(context: Context, sourceUri: Uri): Bitmap? {
        val tmp = File.createTempFile("card_import_", ".bin", context.cacheDir)
        return try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                copyImportSource(input, tmp)
            } ?: run {
                UsbSessionLog.e("CardImage", "decodeBitmap openInputStream null uri=$sourceUri")
                throw IllegalStateException("无法读取所选图片")
            }
            decodeSampledFromFile(tmp)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: IllegalStateException) {
            UsbSessionLog.w("CardImage", "decodeBitmapFromUri rejected uri=$sourceUri: ${e.message}")
            throw e
        } catch (e: Exception) {
            UsbSessionLog.e("CardImage", "decodeBitmapFromUri failed uri=$sourceUri", e)
            null
        } finally {
            runCatching { if (tmp.exists()) tmp.delete() }
        }
    }

    /**
     * 将「视口 (0,0)–(viewportW, viewportH)」内看到的图像区域映射回位图坐标并裁切、再按卡图规则压缩保存。
     * 视口内绘制方式为：先 [topLeft]，再按 [scale] 像素/位图像素 绘制整图（与裁切全屏界面中的手势状态一致）。
     */
    fun cropViewportAndSave(
        context: Context,
        bitmap: Bitmap,
        scale: Float,
        topLeftXPx: Float,
        topLeftYPx: Float,
        viewportWPx: Int,
        viewportHPx: Int,
    ): String? {
        if (scale <= 0f || viewportWPx <= 0 || viewportHPx <= 0) return null
        val leftF = (0f - topLeftXPx) / scale
        val topF = (0f - topLeftYPx) / scale
        val rightF = (viewportWPx.toFloat() - topLeftXPx) / scale
        val bottomF = (viewportHPx.toFloat() - topLeftYPx) / scale
        val l = floor(leftF.toDouble()).toInt().coerceIn(0, (bitmap.width - 1).coerceAtLeast(0))
        val t = floor(topF.toDouble()).toInt().coerceIn(0, (bitmap.height - 1).coerceAtLeast(0))
        val r = ceil(rightF.toDouble()).toInt().coerceIn(l + 1, bitmap.width)
        val b = ceil(bottomF.toDouble()).toInt().coerceIn(t + 1, bitmap.height)
        val sub = try {
            Bitmap.createBitmap(bitmap, l, t, r - l, b - t)
        } catch (e: Exception) {
            UsbSessionLog.e("CardImage", "cropViewport createBitmap l=$l t=$t r=$r b=$b", e)
            return null
        }
        return finalizeCardJpeg(context, sub)
    }

    suspend fun processAndSave(context: Context, sourceUri: Uri): String? {
        val tmp = File.createTempFile("card_import_", ".bin", context.cacheDir)
        return try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                copyImportSource(input, tmp)
            } ?: run {
                UsbSessionLog.e("CardImage", "openInputStream null uri=$sourceUri")
                throw IllegalStateException("无法读取所选图片")
            }
            val decoded = decodeSampledFromFile(tmp) ?: run {
                UsbSessionLog.e("CardImage", "decode failed size=${tmp.length()} path=${tmp.name}")
                return null
            }
            finalizeCardJpeg(context, decoded)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: IllegalStateException) {
            UsbSessionLog.w("CardImage", "processAndSave rejected uri=$sourceUri: ${e.message}")
            throw e
        } catch (e: Exception) {
            UsbSessionLog.e("CardImage", "processAndSave failed uri=$sourceUri", e)
            null
        } finally {
            runCatching { if (tmp.exists()) tmp.delete() }
        }
    }

    /** 对已下载的临时图片（如 AI 生图 URL 落盘文件）做 2:3 裁切并写入私有目录。 */
    fun processAndSaveFromFilePath(context: Context, absolutePath: String): String? {
        val decoded = BitmapFactory.decodeFile(absolutePath) ?: return null
        return finalizeCardJpeg(context, decoded)
    }

    private fun finalizeCardJpeg(context: Context, decoded: Bitmap): String? {
        val cropped = centerCropToAspect(decoded, TARGET_ASPECT_W, TARGET_ASPECT_H)
        if (cropped !== decoded && !decoded.isRecycled) decoded.recycle()
        val scaled = scaleDownIfNeeded(cropped, OUTPUT_MAX_HEIGHT)
        if (scaled !== cropped && !cropped.isRecycled) cropped.recycle()
        var out: File? = null
        return try {
            val target = createFinalJpegFile(context.filesDir)
            out = target
            FileOutputStream(target).use { fos ->
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos)) {
                    runCatching { target.delete() }
                    if (!scaled.isRecycled) scaled.recycle()
                    return null
                }
            }
            if (!scaled.isRecycled) scaled.recycle()
            target.absolutePath
        } catch (e: Exception) {
            UsbSessionLog.e("CardImage", "finalizeCardJpeg failed", e)
            out?.let { runCatching { it.delete() } }
            if (!scaled.isRecycled) scaled.recycle()
            null
        }
    }

    internal fun createFinalJpegFile(filesDir: File): File =
        File.createTempFile("character_card_", ".jpg", filesDir)

    internal suspend fun copyImportSource(
        input: InputStream,
        target: File,
        maxBytes: Long = MAX_IMPORT_BYTES,
    ) {
        ChatAttachmentFiles.copyInputToFile(
            input = input,
            out = target,
            maxBytes = maxBytes,
            oversizeMessage = "图片超过 ${maxBytes / 1024 / 1024} MB 上限",
        )
    }

    private fun decodeSampledFromFile(file: File): Bitmap? {
        val optsBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, optsBounds)
        if (optsBounds.outWidth <= 0 || optsBounds.outHeight <= 0) return null
        var sample = 1
        while (optsBounds.outWidth / sample > MAX_DECODE_SIDE || optsBounds.outHeight / sample > MAX_DECODE_SIDE) {
            sample *= 2
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return runCatching {
                val source = ImageDecoder.createSource(file)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.setTargetSampleSize(sample)
                }
            }.getOrElse { e ->
                UsbSessionLog.w("CardImage", "ImageDecoder failed, fallback BitmapFactory: ${e.message}")
                decodeWithFactory(file, sample)
            }
        }
        return decodeWithFactory(file, sample)
    }

    private fun decodeWithFactory(file: File, sample: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    private fun centerCropToAspect(src: Bitmap, wRatio: Float, hRatio: Float): Bitmap {
        val targetAspect = wRatio / hRatio
        val srcAspect = src.width.toFloat() / src.height.toFloat()
        val cropW: Int
        val cropH: Int
        val x: Int
        val y: Int
        if (srcAspect > targetAspect) {
            cropH = src.height
            cropW = (cropH * targetAspect).roundToInt().coerceIn(1, src.width)
            x = (src.width - cropW) / 2
            y = 0
        } else {
            cropW = src.width
            cropH = (cropW / targetAspect).roundToInt().coerceIn(1, src.height)
            x = 0
            y = (src.height - cropH) / 2
        }
        return Bitmap.createBitmap(src, x, y, cropW, cropH)
    }

    private fun scaleDownIfNeeded(bmp: Bitmap, maxHeight: Int): Bitmap {
        if (bmp.height <= maxHeight) return bmp
        val scale = maxHeight.toFloat() / bmp.height.toFloat()
        val w = max(1, (bmp.width * scale).roundToInt())
        val h = maxHeight
        return Bitmap.createScaledBitmap(bmp, w, h, true)
    }
}
