package com.mojing.app.ui.chat.drawer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.mojing.app.data.local.entity.MessageBookmarkEntity

@Composable
fun BookmarksTab(
    bookmarks: List<MessageBookmarkEntity>,
    bookmarkPreviews: Map<Long, String>,
    onJump: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    busyIds: Set<Long> = emptySet(),
    locatingId: Long? = null,
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
                    val savedAt = remember(b.createdAt) {
                        Instant.ofEpochMilli(b.createdAt).atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("yyyy/M/d HH:mm", Locale.getDefault()))
                    }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .clickable(enabled = locatingId == null && b.messageId !in busyIds) { onJump(b.messageId) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.PushPin, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(if (locatingId == b.messageId) "正在定位原文…" else "收藏于 $savedAt", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(preview, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { onRemove(b.messageId) }, enabled = b.messageId !in busyIds && locatingId != b.messageId) {
                                if (b.messageId in busyIds) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                else
                                Icon(Icons.Default.Delete, "取消收藏", tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
                            }
                        }
                    }
                }
            }
        }
    }
}
