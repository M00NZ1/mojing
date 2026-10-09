package com.mojing.app.ui.chat.contents

import com.mojing.app.ui.common.MoJingIcon as Icon
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.mojing.app.data.ChapterInputDraft

// Only the bounded directory projection is saved. The existing write owner rechecks
// the live message and its source branch before committing a restored editor.
private val ContentsEntrySaver = listSaver<StoryContentsEntry?, Any>(
    save = { entry -> entry?.let {
        listOf(it.messageId, it.title, it.dateLabel, it.preview, it.chapterNumber ?: 0,
            it.incomplete, it.canForkChapter, it.sourceBranchId)
    }.orEmpty() },
    restore = { values -> if (values.isEmpty()) null else StoryContentsEntry(
        messageId = values[0] as Long, title = values[1] as String,
        dateLabel = values[2] as String, preview = values[3] as String,
        chapterNumber = (values[4] as Int).takeIf { it > 0 },
        incomplete = values[5] as Boolean, canForkChapter = values[6] as Boolean,
        sourceBranchId = values[7] as String,
    ) },
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StoryContentsSheet(
    visible: Boolean,
    sessionId: Long,
    branchId: String,
    onOpenMessage: (Long, (Boolean) -> Unit) -> Boolean,
    onDismiss: () -> Unit,
    novelTitle: String = "", busy: Boolean = false,
    saving: Boolean = false, saveError: String? = null,
    onEditStart: () -> Unit = {},
    onRenameNovel: (String, () -> Unit) -> Unit = { _, _ -> },
    onNextChapter: (String, String) -> Boolean = { _, _ -> false },
    onForkChapter: (Long, String, String, String) -> Boolean = { _, _, _, _ -> false },
    onLoadForkInput: (String, Long) -> ChapterInputDraft = { _, _ -> ChapterInputDraft() },
    onSaveForkInput: (String, Long, String, String, Boolean) -> Boolean = { _, _, _, _, _ -> false },
    onLoadChapterInput: () -> ChapterInputDraft = { ChapterInputDraft() },
    onSaveChapterInput: (String, String, Boolean) -> Boolean = { _, _, _ -> true },
    onRenameChapter: (Long, String, String, String, () -> Unit) -> Unit = { _, _, _, _, _ -> },
    onExport: () -> Unit = {},
    viewModel: StoryContentsViewModel = hiltViewModel(),
) {
    if (!visible) return
    LaunchedEffect(sessionId, branchId) { viewModel.load(sessionId, branchId) }
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()
    LaunchedEffect(state.query) { listState.scrollToItem(0) }
    var editingNovel by rememberSaveable(sessionId, branchId) { mutableStateOf(false) }
    var creatingChapter by remember(sessionId, branchId) { mutableStateOf(false) }
    var forkingChapter by remember(sessionId, branchId) { mutableStateOf<StoryContentsEntry?>(null) }
    var editingChapter by rememberSaveable(sessionId, branchId, stateSaver = ContentsEntrySaver) { mutableStateOf<StoryContentsEntry?>(null) }
    var title by rememberSaveable(sessionId, branchId) { mutableStateOf("") }
    var direction by remember(sessionId, branchId) { mutableStateOf("") }
    var chapterInputError by remember(sessionId, branchId) { mutableStateOf<String?>(null) }
    var locatingMessageId by remember(sessionId, branchId) { mutableStateOf<Long?>(null) }
    var failedOpenMessageId by remember(sessionId, branchId) { mutableStateOf<Long?>(null) }
    var openMessageError by remember(sessionId, branchId) { mutableStateOf<String?>(null) }
    // A late history result must not close a reopened sheet or another story line.
    var active by remember(sessionId, branchId) { mutableStateOf(true) }
    DisposableEffect(sessionId, branchId) { onDispose { active = false } }
    fun openChapter(messageId: Long) {
        if (locatingMessageId != null) return
        locatingMessageId = messageId
        failedOpenMessageId = null
        openMessageError = null
        if (!onOpenMessage(messageId, result@{ opened ->
                if (!active || locatingMessageId != messageId) return@result
                locatingMessageId = null
                if (opened) onDismiss()
                else {
                    failedOpenMessageId = messageId
                    openMessageError = "章节原文不可用或加载失败"
                }
            })) {
            locatingMessageId = null
            failedOpenMessageId = messageId
            openMessageError = "当前正在生成或加载历史，请稍后重试"
        }
    }
    val controlsBusy = busy || saving || !state.catalogLoaded || state.isLoading || state.refreshingId != null || locatingMessageId != null
    val chapterActionLabel = when {
        state.canResumeChapter -> "继续未完成章节"
        state.latestEntry == null -> "生成开篇"
        else -> "生成下一章"
    }
    val contentsStatus = when {
        state.isLoading -> if (state.query.isNotBlank()) "正在查找…" else "正在读取目录…"
        state.error != null && state.entries.isEmpty() -> "目录暂时无法读取"
        state.query.isNotBlank() -> if (state.hasMore) "已找到 ${state.entries.size} 项 · 可继续加载" else "找到 ${state.entries.size} 项"
        state.hasMore -> "已加载 ${state.entries.size} 条目录 · 最近内容在前"
        else -> "${state.entries.size} 条目录 · 最近内容在前"
    }
    val currentSaving by rememberUpdatedState(saving)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !currentSaving })
    if (editingNovel || creatingChapter || editingChapter != null || forkingChapter != null) {
        val composingChapter = creatingChapter || forkingChapter != null
        fun saveInput(synchronous: Boolean): Boolean = forkingChapter?.let {
            onSaveForkInput(branchId, it.messageId, title, direction, synchronous)
        } ?: onSaveChapterInput(title, direction, synchronous)
        fun closeEditor() {
            if (composingChapter && !saveInput(true)) {
                chapterInputError = "章节输入未能保存到本机，请检查存储空间后重试"
                return
            }
            editingNovel = false
            creatingChapter = false
            forkingChapter = null
            editingChapter = null
        }
        Dialog(onDismissRequest = { if (!saving) closeEditor() },
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxWidth().widthIn(max = 560.dp).padding(horizontal = 16.dp)
                .heightIn(max = 560.dp).imePadding(),
                shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                Column {
                    Text(if (forkingChapter != null) "另线续写" else if (creatingChapter) chapterActionLabel else if (editingNovel) "小说标题" else "章节名称",
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                        style = MaterialTheme.typography.titleLarge)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (composingChapter) Text(
                            if (forkingChapter != null) "从「${forkingChapter!!.title}」创建独立故事线并补完本章。承接此前剧情，原线与后续章节保留。" else
                            if (state.canResumeChapter) "承接本章草稿生成完整正文。" else
                                "承接当前故事线生成新章，已有章节保留原文。",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        editingChapter?.let { entry -> Text(
                            if (entry.sourceBranchId != branchId) "此章继承自其他故事线。修改会更新原章，以及所有引用此章的故事线。"
                            else "修改会更新本章原文标题，以及所有引用此章的故事线。",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        com.mojing.app.ui.common.MoJingTextField(value = title,
                            onValueChange = {
                                title = it.take(100)
                                if (composingChapter) {
                                    chapterInputError = null
                                    saveInput(false)
                                }
                            }, modifier = Modifier.fillMaxWidth(),
                            singleLine = true, enabled = !saving,
                            label = { Text(if (composingChapter) "章节名（可由模型生成）" else "名称") })
                        if (composingChapter) com.mojing.app.ui.common.MoJingTextField(
                            value = direction, onValueChange = {
                                direction = it.take(4000)
                                chapterInputError = null
                                saveInput(false)
                            },
                            modifier = Modifier.fillMaxWidth(), enabled = !saving,
                            label = { Text("剧情走向（可选）") }, minLines = 2, maxLines = 5)
                        saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        chapterInputError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(enabled = !saving, onClick = ::closeEditor) { Text("取消") }
                        com.mojing.app.ui.common.MoJingButton(
                            enabled = !controlsBusy && (composingChapter || title.isNotBlank()),
                            onClick = {
                                when {
                                    forkingChapter != null -> if (onForkChapter(forkingChapter!!.messageId, branchId, title, direction)) {
                                        forkingChapter = null; onDismiss()
                                    } else chapterInputError = "未能开始另线续写，输入已保留，请稍后重试"
                                    creatingChapter -> if (onNextChapter(title, direction)) { creatingChapter = false; onDismiss() }
                                        else chapterInputError = "未能开始生成；章节输入已保留，请检查对话提示后重试"
                                    editingNovel -> onRenameNovel(title) { editingNovel = false }
                                    else -> editingChapter?.let { entry -> onRenameChapter(entry.messageId, branchId, entry.sourceBranchId, title) {
                                        if (!active) return@onRenameChapter
                                        editingChapter = null
                                        viewModel.refreshEntry(entry.messageId)
                                    } }
                                }
                            },
                        ) { Text(if (saving) "保存中…" else if (forkingChapter != null) "创建并续写" else if (creatingChapter) "开始生成" else if (editingChapter != null) "修改原章名称" else "保存") }
                    }
                }
            }
        }
    }
    ModalBottomSheet(
        scrimColor = androidx.compose.material3.MaterialTheme.colorScheme.scrim.copy(alpha = 0.42f),sheetState = sheetState, onDismissRequest = { if (!saving) onDismiss() },
        dragHandle = null, containerColor = MaterialTheme.colorScheme.surface) {
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = LocalFocusManager.current
        val searchKeyboardOpen = WindowInsets.isImeVisible
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("小说目录", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = onDismiss, enabled = !saving) { Icon(Icons.Outlined.Close, "关闭小说目录") }
                }
                com.mojing.app.ui.common.MoJingTextField(
                    value = state.query, onValueChange = viewModel::updateQuery,
                    placeholder = { Text("章节标题、编号或开头片段") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = if (state.query.isNotEmpty()) {
                        { IconButton(onClick = { viewModel.updateQuery("") }, enabled = !saving && locatingMessageId == null) {
                            Icon(Icons.Outlined.Close, "清除目录搜索")
                        } }
                    } else null,
                    enabled = state.catalogLoaded && !saving && locatingMessageId == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); focus.clearFocus() }),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    inputModifier = Modifier.semantics { contentDescription = "搜索小说目录" },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (state.refreshingId != null) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (locatingMessageId != null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("正在打开章节…", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                failedOpenMessageId?.let { messageId ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(openMessageError.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { openChapter(messageId) }) { Text("重试") }
                    }
                }
                state.refreshFailedId?.let { messageId ->
                    TextButton(onClick = { viewModel.refreshEntry(messageId) }) {
                        Text("名称已保存，点击重试刷新目录", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                if (searchKeyboardOpen) item(key = "search-count") {
                    Text(contentsStatus, Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else item(key = "novel-title") {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(novelTitle.ifBlank { "未命名小说" }, style = MaterialTheme.typography.headlineSmall,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(contentsStatus,
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
                state.entries.isEmpty() -> item { Text(
                    if (state.query.isNotBlank()) "没有匹配的目录，试试其他关键词。" else "当前故事线还没有章节，点击下方生成开篇。",
                    Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else -> {
                    items(state.entries, key = { it.messageId }) { entry ->
                        ListItem(
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !saving && locatingMessageId == null) {
                                openChapter(entry.messageId)
                            },
                            trailingContent = { IconButton(enabled = !controlsBusy, onClick = { onEditStart(); title = entry.title; editingChapter = entry }) { Icon(Icons.Outlined.Edit, "修改章节名称：${entry.title}") } },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                            headlineContent = { Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Column {
                                    Text(entry.dateLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (entry.sourceBranchId != branchId) Text("继承章节", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (entry.incomplete) Text("未完成", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    if (entry.preview.isNotBlank()) Text(entry.preview, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (entry.canForkChapter) TextButton(enabled = !controlsBusy, onClick = {
                                        onEditStart()
                                        val draft = onLoadForkInput(branchId, entry.messageId)
                                        title = draft.title
                                        direction = draft.direction
                                        chapterInputError = null
                                        forkingChapter = entry
                                    }) { Text("另线续写", Modifier.semantics { contentDescription = "另线续写：${entry.title}" }) }
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
            if (!searchKeyboardOpen) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                com.mojing.app.ui.common.MoJingButton(enabled = !controlsBusy, onClick = {
                    val draft = onLoadChapterInput()
                    title = draft.title
                    direction = draft.direction
                    chapterInputError = null
                    creatingChapter = true
                }) {
                    Text(chapterActionLabel)
                }
                TextButton(enabled = !controlsBusy && state.latestEntry != null, onClick = onExport) { Text("导出小说 TXT") }
            }
            }
        }
    }
}
