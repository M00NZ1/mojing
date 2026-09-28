package com.mojing.app.ui.session

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mojing.app.data.local.dao.NewSessionCharacterOption
import com.mojing.app.data.local.dao.NewSessionWorldOption
import com.mojing.app.ui.common.ModelPickerHeader
import com.mojing.app.ui.common.MoJingButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun NewSessionWorldPicker(
    loadPage: suspend (String, NewSessionWorldOption?) -> SessionViewModel.NewSessionWorldPage,
    loadSelection: suspend (NewSessionWorldOption) -> SessionViewModel.NewSessionWorldSelection,
    selectedEncyclopediaId: Long?,
    selectedTemplateId: Long?,
    onSelect: (SessionViewModel.NewSessionWorldSelection) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var requestedPage by remember { mutableIntStateOf(0) }
    var displayedPage by remember { mutableIntStateOf(0) }
    var cursors by remember { mutableStateOf<List<NewSessionWorldOption?>>(listOf(null)) }
    var rows by remember { mutableStateOf<List<NewSessionWorldOption>>(emptyList()) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var readError by remember { mutableStateOf(false) }
    var selectionError by remember { mutableStateOf(false) }
    var selectingId by remember { mutableStateOf<Long?>(null) }
    var retryVersion by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    LaunchedEffect(query, requestedPage, retryVersion) {
        val cursor = cursors.getOrNull(requestedPage)
        loading = true
        readError = false
        selectionError = false
        try {
            if (query.isNotBlank()) delay(200)
            val result = loadPage(query, cursor)
            rows = result.rows
            hasMore = result.hasMore
            displayedPage = requestedPage
            listState.scrollToItem(0)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            readError = true
        } finally {
            loading = false
        }
    }
    val showUnbound = displayedPage == 0 && "不绑定世界".contains(query.trim(), ignoreCase = true)
    PickerDialogFrame("选择世界", query, {
        query = it
        requestedPage = 0
        displayedPage = 0
        cursors = listOf(null)
        rows = emptyList()
        hasMore = false
    }, "世界", onDismiss) {
        if (loading && rows.isEmpty()) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("正在查找世界…", Modifier.fillMaxWidth().padding(20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (!showUnbound && rows.isEmpty() && !readError) {
            Text("没有匹配的世界", Modifier.fillMaxWidth().padding(20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), state = listState) {
                if (showUnbound) item(key = "unbound") {
                    WorldOptionRow("不绑定世界", "从角色开始，自由展开故事",
                        selectedEncyclopediaId == null && selectedTemplateId == null,
                        enabled = selectingId == null && !loading && !readError) {
                        onSelect(SessionViewModel.NewSessionWorldSelection())
                    }
                }
                items(rows, key = { "${it.kind}-${it.id}" }) { world ->
                    WorldOptionRow(
                        world.name,
                        if (world.kind == 0) "世界百科 · ${world.entryCount} 条资料" else "旧世界资料",
                        if (world.kind == 0) selectedEncyclopediaId == world.id else selectedTemplateId == world.id,
                        enabled = selectingId == null && !loading && !readError,
                    ) {
                        selectingId = world.id
                        selectionError = false
                        scope.launch {
                            try {
                                onSelect(loadSelection(world))
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                selectionError = true
                            } finally {
                                selectingId = null
                            }
                        }
                    }
                }
            }
        }
        if (readError || selectionError) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(if (readError) "世界读取失败，当前选择仍保留" else "世界已变化，请重新查询",
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { retryVersion += 1 }) { Text("重试") }
        }
        if (selectingId != null) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (rows.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = { requestedPage = displayedPage - 1 },
                enabled = displayedPage > 0 && !loading && !readError && selectingId == null) { Text("上一页") }
            Text("第 ${displayedPage + 1} 页 · 本页 ${rows.size} 个",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = {
                cursors = cursors.take(displayedPage + 1) + rows.last()
                requestedPage = displayedPage + 1
            }, enabled = hasMore && !loading && !readError && selectingId == null) { Text("下一页") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NewSessionCharacterPicker(
    encyclopediaId: Long?,
    loadPage: suspend (Long?, String, NewSessionCharacterOption?) -> SessionViewModel.NewSessionCharacterPage,
    selectedIds: Set<Long>,
    onSelectionChange: (Set<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var requestedPage by remember { mutableIntStateOf(0) }
    var displayedPage by remember { mutableIntStateOf(0) }
    var cursors by remember { mutableStateOf<List<NewSessionCharacterOption?>>(listOf(null)) }
    var rows by remember { mutableStateOf<List<NewSessionCharacterOption>>(emptyList()) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var readError by remember { mutableStateOf(false) }
    var retryVersion by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    LaunchedEffect(query, requestedPage, encyclopediaId, retryVersion) {
        val cursor = cursors.getOrNull(requestedPage)
        loading = true
        readError = false
        try {
            if (query.isNotBlank()) delay(200)
            val result = loadPage(encyclopediaId, query, cursor)
            rows = result.rows
            hasMore = result.hasMore
            displayedPage = requestedPage
            listState.scrollToItem(0)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            readError = true
        } finally {
            loading = false
        }
    }
    val matchIds = remember(rows) { rows.mapTo(mutableSetOf()) { it.id } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().widthIn(max = 560.dp).padding(horizontal = 16.dp)
            .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.78f).imePadding(),
            shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    ModelPickerHeader("参与角色 · ${selectedIds.size} 人", query, {
                        query = it
                        requestedPage = 0
                        displayedPage = 0
                        cursors = listOf(null)
                        rows = emptyList()
                        hasMore = false
                    }, onDismiss,
                        searchLabel = "角色")
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (loading && rows.isEmpty()) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("正在查找角色…", Modifier.fillMaxWidth().padding(20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (rows.isEmpty() && !readError) {
                    Text("没有匹配的角色", Modifier.fillMaxWidth().padding(20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), state = listState) {
                        items(rows, key = { it.id }) { character ->
                            val selected = character.id in selectedIds
                            ListItem(
                                modifier = Modifier.fillMaxWidth().toggleable(value = selected,
                                    role = Role.Checkbox, onValueChange = { checked ->
                                        onSelectionChange(if (checked) selectedIds + character.id else selectedIds - character.id)
                                    }),
                                headlineContent = { Text(character.name.ifBlank { "未命名" },
                                    maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                trailingContent = { Checkbox(checked = selected, onCheckedChange = null) },
                                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                            )
                            HorizontalDivider(Modifier.padding(horizontal = 20.dp),
                                color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
                if (readError) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("角色读取失败，当前选择仍保留", Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { retryVersion += 1 }) { Text("重试") }
                }
                if (rows.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    TextButton(onClick = { requestedPage = displayedPage - 1 },
                        enabled = displayedPage > 0 && !loading && !readError) { Text("上一页") }
                    Text("第 ${displayedPage + 1} 页 · 本页 ${rows.size} 人",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = {
                        cursors = cursors.take(displayedPage + 1) + rows.last()
                        requestedPage = displayedPage + 1
                    }, enabled = hasMore && !loading && !readError) { Text("下一页") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    TextButton(enabled = rows.isNotEmpty() && !loading && !readError, onClick = {
                        onSelectionChange(if (matchIds.all { it in selectedIds }) selectedIds - matchIds
                            else selectedIds + matchIds)
                    }) {
                        Text(if (matchIds.all { it in selectedIds }) "取消选中本页" else "全选本页")
                    }
                    Spacer(Modifier.weight(1f))
                    MoJingButton(onClick = onDismiss) { Text("完成") }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickerDialogFrame(
    title: String,
    query: String,
    onQueryChange: (String) -> Unit,
    searchLabel: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().widthIn(max = 560.dp).padding(horizontal = 16.dp)
            .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.78f).imePadding(),
            shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    ModelPickerHeader(title, query, onQueryChange, onDismiss, searchLabel)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                content()
            }
        }
    }
}

@Composable
private fun WorldOptionRow(title: String, detail: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.fillMaxWidth().selectable(selected = selected,
            enabled = enabled, role = Role.RadioButton, onClick = onClick),
        headlineContent = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(detail, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        trailingContent = { RadioButton(selected = selected, onClick = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
}
