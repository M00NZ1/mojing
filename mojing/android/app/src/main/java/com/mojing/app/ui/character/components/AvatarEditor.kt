package com.mojing.app.ui.character.components

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mojing.app.ui.common.avatarImageModel
import com.mojing.app.util.LocalImageFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun AvatarEditor(
    avatarImagePath: String,
    avatarColor: String,
    onImageSelected: (String) -> Unit,
    onColorChanged: (String) -> Unit,
    onImportingChanged: (Boolean) -> Unit,
    onOpenBuiltinLibrary: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isImporting by remember { mutableStateOf(false) }
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null && !isImporting) {
            isImporting = true
            onImportingChanged(true)
            scope.launch {
                try {
                    onImageSelected(LocalImageFiles.copyAvatar(context, uri))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    Toast.makeText(context, e.message ?: "无法读取所选图片", Toast.LENGTH_LONG).show()
                } finally {
                    isImporting = false
                    onImportingChanged(false)
                }
            }
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .clickable(enabled = !isImporting) { imagePicker.launch("image/*") },
            contentAlignment = Alignment.Center
        ) {
            if (avatarImagePath.isNotEmpty()) {
                AsyncImage(
                    model = avatarImageModel(context, avatarImagePath),
                    contentDescription = "头像",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize().background(
                        try { Color(android.graphics.Color.parseColor(avatarColor)) } catch (_: Exception) { Color.Gray }
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.CameraAlt, "上传头像", tint = Color.White.copy(alpha = 0.7f))
                }
            }
            if (isImporting) {
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.58f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(if (isImporting) "正在读取头像…" else "点击上传头像", style = MaterialTheme.typography.labelSmall)
        if (onOpenBuiltinLibrary != null) {
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onOpenBuiltinLibrary, enabled = !isImporting) {
                Text("从内置图库选择")
            }
        }
    }
}
