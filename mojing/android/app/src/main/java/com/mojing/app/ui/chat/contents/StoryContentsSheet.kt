package com.mojing.app.ui.chat.contents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    val controlsBusy = busy || saving || state.refreshingId != null
    val currentSaving by rememberUpdatedState(saving)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !currentSaving })
    if (editingNovel || creatingChapter || editingChapter != null) AlertDialog(
        onDismissRequest = { if (!saving) { editingNovel = false; creatingChapter = false; editingChapter = null } },
        title = { Text(if (creatingChapter) "生成下一章" else if (editingNovel) "小说标题" else "章节名称") },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            com.mojing.app.ui.common.MoJingTextField(value = title, onValueChange = { title = it.take(100) }, singleLine = true,
                enabled = !saving,
                label = { Text(if (creatingChapter) "章节名（可由模型生成）" else "名称") })
            if (creatingChapter) com.mojing.app.ui.common.MoJingTextField(value = direction, onValueChange = { direction = it.take(4000) },
                label = { Text("剧情走向（可选）") }, minLines = 2, maxLines = 5)
            if (!creatingChapter) saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = !controlsBusy && (creatingChapter || title.isNotBlank()), onClick = {
            when {
                creatingChapter -> if (onNextChapter(title, direction)) { creatingChapter = false; onDismiss() }
                editingNovel -> onRenameNovel(title) { editingNovel = false }
                else -> editingChapter?.let { entry -> onRenameChapter(entry.messageId, title) {
                    editingChapter = null
                    viewModel.refreshEntry(entry.messageId)
                } }
            }
        }) { Text(if (saving) "保存中…" else if (creatingChapter) "开始生成" else "保存") } },
        dismissButton = { TextButton(enabled = !saving, onClick = { editingNovel = false; creatingChapter = false; editingChapter = null }) { Text("取消") } },
    )
    ModalBottomSheet(sheetState = sheetState, onDismissRequest = { if (!saving) onDismiss() },
        dragHandle = null, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("小说目录", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onDismiss, enabled = !saving) { Icon(Icons.Default.Close, "关闭小说目录") }
            }
            TextButton(enabled = !controlsBusy, onClick = { onEditStart(); title = novelTitle; editingNovel = true }) {
                Text(novelTitle.ifBlank { "设置小说标题" }, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(enabled = !controlsBusy, onClick = { title = ""; direction = ""; creatingChapter = true }) { Text(if (state.entries.firstOrNull()?.incomplete == true) "继续未完成章节" else "生成下一章") }
                TextButton(enabled = !controlsBusy && state.entries.isNotEmpty(), onClick = onExport) { Text("导出小说 TXT") }
            }
            Spacer(Modifier.height(12.dp))
            if (state.refreshingId != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.refreshFailedId?.let { messageId ->
                TextButton(onClick = { viewModel.refreshEntry(messageId) }) {
                    Text("名称已保存，点击重试刷新目录", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        when {
            state.isLoading -> Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.error != null && state.entries.isEmpty() -> Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(state.error!!, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::retry) { Text("重试") }
            }
            state.entries.isEmpty() -> Text("当前故事线还没有可识别的章节", Modifier.padding(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
                items(state.entries, key = { it.messageId }) { entry ->
                    ListItem(
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !saving) {
                            if (onOpenMessage(entry.messageId)) onDismiss()
                        },
                        trailingContent = { TextButton(enabled = !controlsBusy, onClick = { onEditStart(); title = entry.title; editingChapter = entry }) { Text("命名") } },
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
}
