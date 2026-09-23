package com.mojing.app.ui.chat.contents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StoryContentsSheet(
    visible: Boolean,
    sessionId: Long,
    branchId: String,
    onOpenMessage: (Long) -> Boolean,
    onDismiss: () -> Unit,
    novelTitle: String = "", busy: Boolean = false,
    saving: Boolean = false, saveError: String? = null,
    onEditStart: () -> Unit = {},
    onRenameNovel: (String, () -> Unit) -> Unit = { _, _ -> },
    onNextChapter: (String, String) -> Boolean = { _, _ -> false },
    onRenameChapter: (Long, String, () -> Unit) -> Unit = { _, _, _ -> },
    onExport: () -> Unit = {},
    viewModel: StoryContentsViewModel = hiltViewModel(),
) {
    if (!visible) return
    LaunchedEffect(sessionId, branchId) { viewModel.load(sessionId, branchId) }
    val state by viewModel.state.collectAsState()
    var editingNovel by remember { mutableStateOf(false) }
    var creatingChapter by remember { mutableStateOf(false) }
    var editingChapter by remember { mutableStateOf<StoryContentsEntry?>(null) }
    var title by remember { mutableStateOf("") }
    var direction by remember { mutableStateOf("") }
    val controlsBusy = busy || saving || state.isLoading || state.refreshingId != null
    val currentSaving by rememberUpdatedState(saving)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !currentSaving })
    if (editingNovel || creatingChapter || editingChapter != null) {
        fun closeEditor() {
            editingNovel = false
            creatingChapter = false
            editingChapter = null
        }
        Dialog(onDismissRequest = { if (!saving) closeEditor() },
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxWidth().widthIn(max = 560.dp).padding(horizontal = 16.dp)
                .heightIn(max = 560.dp).imePadding(),
                shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                Column {
                    Text(if (creatingChapter) "生成下一章" else if (editingNovel) "小说标题" else "章节名称",
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                        style = MaterialTheme.typography.titleLarge)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        com.mojing.app.ui.common.MoJingTextField(value = title,
                            onValueChange = { title = it.take(100) }, modifier = Modifier.fillMaxWidth(),
                            singleLine = true, enabled = !saving,
                            label = { Text(if (creatingChapter) "章节名（可由模型生成）" else "名称") })
                        if (creatingChapter) com.mojing.app.ui.common.MoJingTextField(
                            value = direction, onValueChange = { direction = it.take(4000) },
                            modifier = Modifier.fillMaxWidth(), enabled = !saving,
                            label = { Text("剧情走向（可选）") }, minLines = 2, maxLines = 5)
                        saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(enabled = !saving, onClick = ::closeEditor) { Text("取消") }
                        com.mojing.app.ui.common.MoJingButton(
                            enabled = !controlsBusy && (creatingChapter || title.isNotBlank()),
                            onClick = {
                                when {
                                    creatingChapter -> if (onNextChapter(title, direction)) { creatingChapter = false; onDismiss() }
                                    editingNovel -> onRenameNovel(title) { editingNovel = false }
                                    else -> editingChapter?.let { entry -> onRenameChapter(entry.messageId, title) {
                                        editingChapter = null
                                        viewModel.refreshEntry(entry.messageId)
                                    } }
                                }
                            },
                        ) { Text(if (saving) "保存中…" else if (creatingChapter) "开始生成" else "保存") }
                    }
                }
            }
        }
    }
    ModalBottomSheet(sheetState = sheetState, onDismissRequest = { if (!saving) onDismiss() },
        dragHandle = null, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("小说目录", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = onDismiss, enabled = !saving) { Icon(Icons.Default.Close, "关闭小说目录") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (state.refreshingId != null) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.refreshFailedId?.let { messageId ->
                    TextButton(onClick = { viewModel.refreshEntry(messageId) }) {
                        Text("名称已保存，点击重试刷新目录", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp)) {
                item(key = "novel-title") {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(novelTitle.ifBlank { "未命名小说" }, style = MaterialTheme.typography.headlineSmall,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(if (state.hasMore) "已加载 ${state.entries.size} 条目录 · 最近内容在前" else "${state.entries.size} 条目录 · 最近内容在前",
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(enabled = !controlsBusy, onClick = { onEditStart(); title = novelTitle; editingNovel = true }) {
                            Icon(Icons.Outlined.Edit, "编辑小说标题")
                        }
                    }
                }
            when {
                state.isLoading -> item { Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                state.error != null && state.entries.isEmpty() -> item { Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.error!!, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = viewModel::retry) { Text("重试") }
                } }
                state.entries.isEmpty() -> item { Text("当前故事线还没有章节，点击下方生成开篇。", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else -> {
                    items(state.entries, key = { it.messageId }) { entry ->
                        ListItem(
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !saving) {
                                if (onOpenMessage(entry.messageId)) onDismiss()
                            },
                            trailingContent = { IconButton(enabled = !controlsBusy, onClick = { onEditStart(); title = entry.title; editingChapter = entry }) { Icon(Icons.Outlined.Edit, "修改章节名称：${entry.title}") } },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                            headlineContent = { Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Column {
                                    Text(entry.dateLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (entry.incomplete) Text("未完成", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    if (entry.preview.isNotBlank()) Text(entry.preview, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            },
                        )
                        HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    if (state.hasMore) item(key = "more") {
                        TextButton(onClick = viewModel::loadMore, enabled = !state.isLoadingMore, modifier = Modifier.fillMaxWidth()) {
                            if (state.isLoadingMore) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Text("加载更早章节")
                        }
                    }
                    state.error?.let { message -> item(key = "more-error") { TextButton(onClick = viewModel::loadMore, modifier = Modifier.fillMaxWidth()) { Text(message) } } }
                }
            }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                com.mojing.app.ui.common.MoJingButton(enabled = !controlsBusy, onClick = { title = ""; direction = ""; creatingChapter = true }) {
                    Text(when { state.entries.firstOrNull()?.incomplete == true -> "继续未完成章节"; state.entries.isEmpty() -> "生成开篇"; else -> "生成下一章" })
                }
                TextButton(enabled = !controlsBusy && state.entries.isNotEmpty(), onClick = onExport) { Text("导出小说 TXT") }
            }
        }
    }
}
