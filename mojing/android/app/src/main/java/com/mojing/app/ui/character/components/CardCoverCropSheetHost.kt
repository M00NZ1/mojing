package com.mojing.app.ui.character.components

import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.mojing.app.media.CharacterCardImageProcessor
import com.mojing.app.ui.util.UserFacingStrings
import com.mojing.app.util.UsbSessionLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 全屏 2∶3 裁切弹层：与 [CardImageEditor] 同一套 decode → [CardImageCropSheet] → [CharacterCardImageProcessor.cropViewportAndSave]。
 * 调用方在 [onBitmapDisposed] 里清空 bitmap 状态；用户点关闭时额外调用 [onCropCancelled]（用于清除待写入的条目 id 等）。
 * 确定裁切时快照 [requestToken]，异步完成后把同一 token 连同路径交还，避免后续选择覆盖目标。
 */
@Composable
fun CardCoverCropSheetHost(
    bitmap: Bitmap?,
    requestToken: Long? = null,
    onBitmapDisposed: () -> Unit,
    onCropCancelled: () -> Unit = {},
    onProcessingChanged: (Boolean) -> Unit,
    onCroppedPath: suspend (Long?, String) -> Unit,
    onCroppedFailed: (Context) -> Unit = { ctx ->
        Toast.makeText(ctx, UserFacingStrings.cardImageProcessFailed(), Toast.LENGTH_SHORT).show()
    },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bmp = bitmap
    if (bmp != null) {
        CardImageCropSheet(
            bitmap = bmp,
            onDismiss = {
                runCatching { bmp.recycle() }
                onBitmapDisposed()
                onCropCancelled()
                onProcessingChanged(false)
            },
            onConfirm = { scale, tlx, tly, vw, vh ->
                val b = bmp
                val requestTokenSnapshot = requestToken
                onBitmapDisposed()
                onProcessingChanged(true)
                scope.launch {
                    var path: String? = null
                    var handedOff = false
                    try {
                        withContext(Dispatchers.IO) {
                            path = CharacterCardImageProcessor.cropViewportAndSave(context, b, scale, tlx, tly, vw, vh)
                        }
                        runCatching { b.recycle() }
                        if (path != null) {
                            // 从这里起由回调 owner 负责持久化或在失败时删除文件。
                            handedOff = true
                            onCroppedPath(requestTokenSnapshot, path!!)
                        } else {
                            UsbSessionLog.w("CardCoverCrop", "cropViewportAndSave returned null")
                            onCroppedFailed(context)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } finally {
                        runCatching { b.recycle() }
                        if (!handedOff) {
                            path?.let { unclaimed ->
                                withContext(NonCancellable + Dispatchers.IO) { File(unclaimed).delete() }
                            }
                        }
                        onProcessingChanged(false)
                    }
                }
            },
        )
    }
}
