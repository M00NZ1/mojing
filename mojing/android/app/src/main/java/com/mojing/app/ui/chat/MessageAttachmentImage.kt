package com.mojing.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mojing.app.data.local.entity.MessageAttachmentEntity
import com.mojing.app.ui.common.avatarImageModel

/** Retries only reading the stored attachment; it never regenerates or changes the message. */
@Composable
internal fun MessageAttachmentImage(attachment: MessageAttachmentEntity, onImageClick: (String) -> Unit) {
    val context = LocalContext.current
    val path = attachment.storagePath
    val name = attachment.fileName.ifBlank { "图片" }
    var revision by remember(path) { mutableIntStateOf(0) }
    key(path, revision) {
        var loading by remember { mutableStateOf(true) }
        var failed by remember { mutableStateOf(false) }
        val model = remember(path, revision) { avatarImageModel(context, path) }
        Box(Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(8.dp))) {
            AsyncImage(
                model = model,
                contentDescription = name,
                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)
                    .clickable { onImageClick(path) },
                contentScale = ContentScale.Fit,
                onLoading = { loading = true; failed = false },
                onSuccess = { loading = false; failed = false },
                onError = { loading = false; failed = true },
            )
            if (loading) Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            if (failed) Column(
                Modifier.matchParentSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("图片无法读取", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { revision++ },
                    modifier = Modifier.semantics { contentDescription = "重新加载$name" }) {
                    Text("重新加载")
                }
            }
        }
    }
}
