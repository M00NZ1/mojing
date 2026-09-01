package com.mojing.app.ui.chat.drawer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mojing.app.data.local.entity.MessageBookmarkEntity

@Composable
fun BookmarksTab(
    bookmarks: List<MessageBookmarkEntity>,
    bookmarkPreviews: Map<Long, String>,
    onJump: (Long) -> Unit,
    onRemove: (Long) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (bookmarks.isEmpty()) {
            Text(
                "长按消息可选「收藏」，在此快速跳转。",
                modifier = Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(bookmarks, key = { it.id }) { b ->
                    val preview = bookmarkPreviews[b.messageId] ?: "…"
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .clickable { onJump(b.messageId) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.PushPin, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("消息 #${b.messageId}", style = MaterialTheme.typography.labelSmall)
                                Text(preview, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                            }
                            IconButton(onClick = { onRemove(b.messageId) }) {
                                Icon(Icons.Default.Delete, "取消收藏", tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
                            }
                        }
                    }
                }
            }
        }
    }
}
