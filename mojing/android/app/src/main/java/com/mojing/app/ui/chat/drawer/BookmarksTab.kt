package com.mojing.app.ui.chat.drawer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkRemove
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    loaded: Boolean = true,
    hasMore: Boolean = false,
    loadingMore: Boolean = false,
    loadError: String? = null,
    onLoadMore: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (!loaded && loadError == null) {
            Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("正在加载收藏…", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
            }
        } else if (bookmarks.isEmpty() && !hasMore && !loadingMore && loadError == null) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.BookmarkBorder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("还没有收藏", style = MaterialTheme.typography.titleMedium)
                Text("长按消息选择「收藏」，以后可在这里回到原文。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Text("已加载 ${bookmarks.size} 条收藏${if (hasMore) " · 还有更早记录" else ""}", Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(bookmarks, key = { it.id }) { b ->
                    val preview = bookmarkPreviews[b.messageId] ?: "…"
                    val savedAt = remember(b.createdAt) {
                        Instant.ofEpochMilli(b.createdAt).atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("yyyy/M/d HH:mm", Locale.getDefault()))
                    }
                    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(onClick = { onJump(b.messageId) },
                            enabled = locatingId == null && b.messageId !in busyIds,
                            modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.surface) {
                            Column(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 12.dp, vertical = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("收藏于 $savedAt", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(preview, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                if (locatingId == b.messageId) Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                    Text("正在定位原文…", Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        IconButton(onClick = { onRemove(b.messageId) }, enabled = b.messageId !in busyIds && locatingId != b.messageId) {
                            if (b.messageId in busyIds) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.BookmarkRemove, "取消收藏", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (hasMore || loadingMore || loadError != null) item(key = "bookmark-load-more") {
                    Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (loadingMore) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        loadError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        if ((hasMore || loadError != null) && !loadingMore) TextButton(onClick = onLoadMore, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(if (loadError == null) "加载更早收藏" else "重试加载")
                        }
                    }
                }
            }
        }
    }
}
