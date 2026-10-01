package com.mojing.app.ui.encyclopedia

import com.mojing.app.ui.common.MoJingIcon as Icon
import androidx.compose.material.icons.outlined.Check
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mojing.app.data.local.dao.EncyclopediaEntryOption
import com.mojing.app.ui.common.SearchBar
import com.mojing.app.ui.common.searchHighlightedLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EncyclopediaEntryPicker(encyclopediaId: Long, selectedId: Long?,
    loadPage: suspend (String, Long) -> List<EncyclopediaEntryOption>,
    onDismiss: () -> Unit, onSelect: (EncyclopediaEntryOption) -> Unit) {
    var query by remember(encyclopediaId) { mutableStateOf("") }
    var cursors by remember(encyclopediaId) { mutableStateOf(listOf(0L)) }
    var retry by remember { mutableIntStateOf(0) }
    val cursor = cursors.last()
    var rows by remember(encyclopediaId, query, cursor, retry) { mutableStateOf<List<EncyclopediaEntryOption>?>(null) }
    var error by remember(encyclopediaId, query, cursor, retry) { mutableStateOf<String?>(null) }
    LaunchedEffect(encyclopediaId, query, cursor, retry) {
        try {
            val page = loadPage(query, cursor)
            coroutineContext.ensureActive()
            rows = page
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "资料读取失败，请重试" }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(encyclopediaId, query, cursor) { listState.scrollToItem(0) }
    ModalBottomSheet(
        scrimColor = androidx.compose.material3.MaterialTheme.colorScheme.scrim.copy(alpha = 0.42f),
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = 640.dp,
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = { WindowInsets.safeDrawing },
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("选择条目", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
            SearchBar(
                query = query,
                onQueryChange = { query = it; cursors = listOf(0L) },
                placeholder = "搜索条目名称",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            rows?.takeIf { error == null }?.let { page ->
                Text("本页 ${page.size.coerceAtMost(50)} 项${if (page.size > 50) " · 还有更多" else ""}",
                    Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when {
                    error != null -> Column(
                        Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { retry++ }) { Text("重试") }
                    }
                    rows == null -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                    rows!!.isEmpty() -> Column(
                        Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(if (query.isBlank()) "暂无可关联的条目" else "没有匹配条目", style = MaterialTheme.typography.titleSmall)
                        if (query.isNotBlank()) TextButton(onClick = { query = ""; cursors = listOf(0L) }) {
                            Text("查看全部条目")
                        }
                    }
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().testTag("entry-options"),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        items(rows!!.take(50), key = { it.id }) { entry ->
                            val isSelected = entry.id == selectedId
                            Surface(
                                onClick = { onSelect(entry) },
                                color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxWidth().testTag("entry-option:${entry.id}")
                                    .semantics { selected = isSelected },
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(searchHighlightedLabel(entry.title.ifBlank { "未命名条目" }, query), style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(ENTRY_TYPE_LABELS[entry.entryType] ?: "其他", style = MaterialTheme.typography.labelMedium,
                                            color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (isSelected) Icon(Icons.Outlined.Check, "已选", Modifier.size(20.dp))
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
            HorizontalDivider()
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { cursors = cursors.dropLast(1) }, enabled = cursors.size > 1) { Text("上一页") }
                    Text("第 ${cursors.size} 页", style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { rows?.getOrNull(49)?.let { cursors = cursors + it.id } },
                        enabled = error == null && (rows?.size ?: 0) > 50) { Text("下一页") }
                }
            }
        }
    }
}
