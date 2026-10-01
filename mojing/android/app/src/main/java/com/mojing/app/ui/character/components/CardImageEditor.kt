package com.mojing.app.ui.character.components

import com.mojing.app.ui.common.MoJingIcon as Icon
import androidx.compose.material.icons.outlined.Image
import com.mojing.app.ui.common.MoJingButton as Button
import com.mojing.app.ui.common.MoJingOutlinedButton as OutlinedButton

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mojing.app.media.CharacterCardImageProcessor
import com.mojing.app.ui.common.avatarImageModel
import com.mojing.app.ui.util.UserFacingStrings
import com.mojing.app.util.UsbSessionLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CardImageEditor(
    cardImagePath: String,
    avatarImagePath: String,
    onCardImagePathChanged: (String) -> Unit,
    onDuplicateFromAvatar: () -> Unit,
    onOpenBuiltinLibrary: (() -> Unit)? = null,
    isGeneratingCardImage: Boolean = false,
    onGenerateWithBackend: (() -> Unit)? = null,
    onProcessingChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var cropBitmap by remember { mutableStateOf<Bitmap?>(null) }

    fun setBusy(value: Boolean) {
        busy = value
        onProcessingChanged(value)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (busy) return@rememberLauncherForActivityResult
        setBusy(true)
        scope.launch {
            var keepBusyForCrop = false
            var decodedBitmap: Bitmap? = null
            try {
                withContext(Dispatchers.IO) {
                    decodedBitmap = CharacterCardImageProcessor.decodeBitmapFromUri(context, uri)
                }
                if (decodedBitmap != null) {
                    cropBitmap = decodedBitmap
                    keepBusyForCrop = true
                } else {
                    UsbSessionLog.w("CardImageEditor", "decodeBitmapFromUri returned null uri=$uri")
                    Toast.makeText(context, UserFacingStrings.cardImageProcessFailed(), Toast.LENGTH_SHORT).show()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    e.message ?: UserFacingStrings.cardImageProcessFailed(),
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                if (!keepBusyForCrop) {
                    runCatching { decodedBitmap?.recycle() }
                    setBusy(false)
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("竖版封面（约 2∶3，列表里优先显示）", style = MaterialTheme.typography.titleMedium)
        Text(
            "与头像分开；选图后全屏裁切。下方「AI 竖版封面」用配图模型按角色名+人设出图，不是写人设文字。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.55f)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            when {
                cardImagePath.isNotEmpty() -> AsyncImage(
                    model = avatarImageModel(context, cardImagePath),
                    contentDescription = "竖版封面图",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Image, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
                }
            }
            if (busy) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator(Modifier.size(36.dp), strokeWidth = 3.dp) }
            }
            if (isGeneratingCardImage) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator(Modifier.size(36.dp), strokeWidth = 3.dp) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { picker.launch("image/*") },
                enabled = !busy && !isGeneratingCardImage,
                modifier = Modifier.weight(1f)
            ) { Text("选图并裁切") }
            if (cardImagePath.isNotEmpty()) {
                OutlinedButton(onClick = { onCardImagePathChanged("") }, enabled = !busy && !isGeneratingCardImage) { Text("清除卡图") }
            }
        }
        if (onGenerateWithBackend != null) {
            OutlinedButton(
                onClick = onGenerateWithBackend,
                enabled = !busy && !isGeneratingCardImage,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("AI 竖版封面（配图模型）")
            }
        }
        if (onOpenBuiltinLibrary != null) {
            TextButton(onClick = onOpenBuiltinLibrary, enabled = !busy && !isGeneratingCardImage) {
                Text("从内置图库选封面")
            }
        }
        if (avatarImagePath.isNotEmpty() && cardImagePath != avatarImagePath) {
            TextButton(onClick = onDuplicateFromAvatar, enabled = !busy && !isGeneratingCardImage) {
                Text("复制头像为独立卡图文件")
            }
        }
    }

    CardCoverCropSheetHost(
        bitmap = cropBitmap,
        onBitmapDisposed = { cropBitmap = null },
        onProcessingChanged = ::setBusy,
        onCroppedPath = { _, path -> onCardImagePathChanged(path) },
        onCroppedFailed = { ctx ->
            UsbSessionLog.w("CardImageEditor", "cropViewportAndSave returned null")
            Toast.makeText(ctx, UserFacingStrings.cardImageProcessFailed(), Toast.LENGTH_SHORT).show()
        },
    )
}
