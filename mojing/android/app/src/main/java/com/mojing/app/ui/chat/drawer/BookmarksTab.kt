package com.mojing.app.ui.chat.drawer

import com.mojing.app.ui.common.MoJingIcon as Icon
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Search
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.mojing.app.ui.common.MoJingTextField as OutlinedTextField
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.key
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.mojing.app.data.local.entity.MessageBookmarkEntity

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BookmarksTab(
    bookmarks: List<MessageBookmarkEntity>,
    bookmarkPreviews: Map<Long, String>,
    bookmarkNoteDrafts: Map<Long, String> = emptyMap(),
    bookmarkNoteErrors: Map<Long, String> = emptyMap(),
    bookmarkNoteSavingIds: Set<Long> = emptySet(),
    onJump: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onNoteDraftChange: (Long, String) -> Unit = { _, _ -> },
    onSaveNote: (Long, String, (Boolean) -> Unit) -> Unit = { _, _, _ -> },
    busyIds: Set<Long> = emptySet(),
    locatingId: Long? = null,
    loaded: Boolean = true,
    hasMore: Boolean = false,
    loadingMore: Boolean = false,
    loadError: String? = null,
    onLoadMore: () -> Unit,
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    hasNewer: Boolean = false,
    onResetWindow: () -> Unit = {},
    sessionReady: Boolean = true,
    sessionId: Long? = null,
) {
    var editingBookmarkId by rememberSaveable { mutableStateOf<Long?>(null) }
    val listState = key(sessionId, query) { rememberLazyListState() }
    val listScope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            inputModifier = Modifier.semantics { contentDescription = "搜索收藏备注" },
            singleLine = true,
            placeholder = { Text("搜索收藏备注") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = if (query.isNotEmpty()) {
                { IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Outlined.Clear, contentDescription = "清除收藏备注搜索")
                } }
            } else null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                keyboard?.hide()
                focusManager.clearFocus()
            }),
        )
        if (hasNewer) TextButton(onClick = {
            listScope.launch { listState.scrollToItem(0) }
            onResetWindow()
        }, modifier = Modifier.fillMaxWidth()) {
            Text("回到最近收藏")
        }
        if (!sessionReady || (!loaded && loadError == null)) {
            Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("正在加载收藏…", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
            }
        } else if (!loaded) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(loadError.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onLoadMore, enabled = !loadingMore, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("重试加载")
                }
            }
        } else if (bookmarks.isEmpty() && !hasMore && !loadingMore && loadError == null) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (query.isBlank()) Icons.Outlined.BookmarkBorder else Icons.Outlined.Search,
                    null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (query.isBlank()) "还没有收藏" else "没有匹配的收藏备注", style = MaterialTheme.typography.titleMedium)
                Text(if (query.isBlank()) "点击消息选择「收藏」，以后可在这里回到原文。" else "可修改搜索词，搜索范围仅限已保存的收藏备注。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Text(if (query.isBlank()) "已加载 ${bookmarks.size} 条收藏${if (hasMore) " · 还有更早记录" else ""}"
                else "已找到 ${bookmarks.size} 条收藏${if (hasMore) " · 还有更多匹配" else ""}",
                Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                items(bookmarks, key = { it.id }) { b ->
                    val preview = bookmarkPreviews[b.messageId] ?: "…"
                    val editingNote = editingBookmarkId == b.id
                    var noteExpanded by rememberSaveable(b.id) { mutableStateOf(false) }
                    var noteHasVisualOverflow by remember(b.id, b.note) { mutableStateOf(false) }
                    val savedAt = remember(b.createdAt) {
                        Instant.ofEpochMilli(b.createdAt).atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("yyyy/M/d HH:mm", Locale.getDefault()))
                    }
                    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = MaterialTheme.shapes.small, onClick = { onJump(b.messageId) },
                            enabled = !editingNote && locatingId == null && b.messageId !in busyIds,
                            modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.surface) {
                            Column(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 12.dp, vertical = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("收藏于 $savedAt", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(preview, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                if (b.note.isNotBlank()) {
                                    Text(
                                        b.note,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = if (noteExpanded) Int.MAX_VALUE else 2,
                                        overflow = TextOverflow.Ellipsis,
                                        onTextLayout = { noteHasVisualOverflow = it.hasVisualOverflow },
                                    )
                                    if (noteHasVisualOverflow || noteExpanded) {
                                        TextButton(onClick = { noteExpanded = !noteExpanded }, modifier = Modifier.heightIn(min = 40.dp)) {
                                            Text(if (noteExpanded) "收起备注" else "展开备注")
                                        }
                                    }
                                }
                                if (locatingId == b.messageId) Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                    Text("正在定位原文…", Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        if (!editingNote) {
                            IconButton(
                                onClick = { editingBookmarkId = b.id },
                                enabled = b.messageId !in busyIds && locatingId != b.messageId,
                                modifier = Modifier.semantics { contentDescription = "编辑收藏备注" },
                            ) { Icon(Icons.Outlined.Edit, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        IconButton(onClick = { onRemove(b.messageId) }, enabled = !editingNote && b.messageId !in busyIds && locatingId != b.messageId) {
                            if (b.messageId in busyIds) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Outlined.BookmarkRemove, "取消收藏", tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
    bookmarks.firstOrNull { it.id == editingBookmarkId }?.let { bookmark ->
        val draft = bookmarkNoteDrafts[bookmark.id] ?: bookmark.note
        val saving = bookmark.id in bookmarkNoteSavingIds
        Dialog(onDismissRequest = { if (!saving) editingBookmarkId = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Surface(Modifier.fillMaxWidth().systemBarsPadding().imePadding().padding(16.dp), shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("收藏备注", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = { editingBookmarkId = null }, enabled = !saving) {
                            Icon(Icons.Outlined.Close, "取消编辑收藏备注")
                        }
                    }
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = draft, onValueChange = { onNoteDraftChange(bookmark.id, it) },
                            modifier = Modifier.fillMaxWidth(), enabled = !saving,
                            inputModifier = Modifier.semantics { contentDescription = "收藏备注" },
                            placeholder = { Text("写下这条收藏的提示") }, minLines = 3, maxLines = 8,
                            supportingText = { Text("${draft.length}/2000") })
                        bookmarkNoteErrors[bookmark.id]?.let { Text(it, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall) }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { onNoteDraftChange(bookmark.id, "") }, enabled = !saving) { Text("清除备注") }
                        com.mojing.app.ui.common.MoJingButton(onClick = {
                            onSaveNote(bookmark.id, draft) { saved -> if (saved) editingBookmarkId = null }
                        }, enabled = !saving, modifier = Modifier.semantics { contentDescription = "保存备注" }) {
                            Text(if (saving) "保存中…" else "保存备注")
                        }
                    }
                }
            }
        }
    }
}
