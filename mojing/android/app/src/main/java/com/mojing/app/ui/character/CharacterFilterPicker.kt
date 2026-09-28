package com.mojing.app.ui.character

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mojing.app.data.local.dao.EncyclopediaFilterOption
import com.mojing.app.ui.common.ModelPickerHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** A bounded search panel; the selected world is kept independently of the visible page. */
@Composable
internal fun CharacterFilterPicker(
    selectedId: Long?,
    loadPage: suspend (String, EncyclopediaFilterOption?) -> CharacterFilterPage,
    onSelect: (EncyclopediaFilterOption?) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var requestedPage by remember { mutableIntStateOf(0) }
    var displayedPage by remember { mutableIntStateOf(0) }
    var cursors by remember { mutableStateOf<List<EncyclopediaFilterOption?>>(listOf(null)) }
    var rows by remember { mutableStateOf<List<EncyclopediaFilterOption>>(emptyList()) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var readError by remember { mutableStateOf(false) }
    var retryVersion by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()

    LaunchedEffect(query, requestedPage, retryVersion) {
        val requestQuery = query
        val requestPage = requestedPage
        val requestVersion = retryVersion
        val cursor = cursors.getOrNull(requestPage)
        loading = true
        readError = false
        try {
            if (requestQuery.isNotBlank()) delay(200)
            val result = loadPage(requestQuery, cursor)
            if (query != requestQuery || requestedPage != requestPage || retryVersion != requestVersion) return@LaunchedEffect
            rows = result.rows
            hasMore = result.hasMore
            displayedPage = requestPage
            listState.scrollToItem(0)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (query == requestQuery && requestedPage == requestPage && retryVersion == requestVersion) readError = true
        } finally {
            if (query == requestQuery && requestedPage == requestPage && retryVersion == requestVersion) loading = false
        }
    }

    val showAll = displayedPage == 0 && (query.isBlank() || "全部角色".contains(query.trim(), ignoreCase = true))
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth().widthIn(max = 560.dp).padding(horizontal = 16.dp)
                .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.78f).imePadding(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    ModelPickerHeader("按百科筛选", query, { value ->
                        query = value
                        requestedPage = 0
                        displayedPage = 0
                        cursors = listOf(null)
                        rows = emptyList()
                        hasMore = false
                    }, onDismiss, searchLabel = "百科")
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (loading && rows.isEmpty()) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("正在查找百科…", Modifier.fillMaxWidth().padding(20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (!showAll && rows.isEmpty() && !readError) {
                    Text("没有匹配的百科", Modifier.fillMaxWidth().padding(20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), state = listState) {
                        if (showAll) item(key = "all") {
                            FilterOptionRow("全部角色", selectedId == null, !loading && !readError) {
                                onSelect(null)
                            }
                        }
                        items(rows, key = { it.id }) { option ->
                            FilterOptionRow(option.name.ifBlank { "百科 ${option.id}" },
                                selectedId == option.id, !loading && !readError) { onSelect(option) }
                        }
                    }
                }
                if (readError) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("百科读取失败，当前筛选仍保留", Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { retryVersion += 1 }) { Text("重试") }
                }
                if (rows.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { requestedPage = displayedPage - 1 },
                        enabled = displayedPage > 0 && !loading && !readError) { Text("上一页") }
                    Text("第 ${displayedPage + 1} 页 · 本页 ${rows.size} 个",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = {
                        cursors = cursors.take(displayedPage + 1) + rows.last()
                        requestedPage = displayedPage + 1
                    }, enabled = hasMore && !loading && !readError) { Text("下一页") }
                }
            }
        }
    }
}

@Composable
private fun FilterOptionRow(title: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled,
            role = Role.RadioButton, onClick = onClick),
        headlineContent = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        trailingContent = { RadioButton(selected = selected, onClick = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
}
