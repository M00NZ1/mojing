package com.mojing.app.ui.workbench.components

import com.mojing.app.ui.common.MoJingIcon as Icon
import androidx.compose.material.icons.outlined.AddAPhoto
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.mojing.app.util.LocalImageFiles
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun CoverImagePicker(
    imagePath: String,
    onImageSelected: (String) -> Unit,
    onImportingChanged: (Boolean) -> Unit,
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
                    onImageSelected(LocalImageFiles.copyCover(context, uri))
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

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("封面图片", style = MaterialTheme.typography.titleMedium)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(enabled = !isImporting) { imagePicker.launch("image/*") },
            contentAlignment = Alignment.Center
        ) {
            if (imagePath.isNotBlank()) {
                AsyncImage(
                    model = File(imagePath),
                    contentDescription = "封面",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.AddAPhoto, "选封面",
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "点击选择封面",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }
            if (isImporting) {
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.58f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
                }
            }
        }
        if (isImporting) {
            Text("正在读取封面…", style = MaterialTheme.typography.bodySmall)
        }
    }
}
