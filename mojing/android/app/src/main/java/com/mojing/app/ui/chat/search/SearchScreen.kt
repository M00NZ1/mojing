package com.mojing.app.ui.chat.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.filled.History
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.mojing.app.ui.common.MoJingTextField as OutlinedTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import com.mojing.app.ui.chat.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.ui.chat.ChatMessageTextFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SearchScreen(
    sessionId: Long,
    branchId: String = "main",
    onBack: () -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val billing: com.mojing.app.ui.settings.usage.BillingDisplayViewModel = hiltViewModel()
    val currency by billing.state.collectAsStateWithLifecycle()
    val resultListState = rememberLazyListState()
    LaunchedEffect(sessionId, branchId) { viewModel.initialize(sessionId, branchId) }
    BackHandler { if (state.selectedMessageId != null) viewModel.closeHit() else onBack() }
    CompositionLocalProvider(LocalBillingCurrencyState provides currency,
        LocalReplyUsageLookup provides remember(billing) { { id -> billing.observeRecord(id) } }) {
    Surface(Modifier.fillMaxSize().systemBarsPadding()) {
        if (state.selectedMessageId != null) SearchContextScreen(state, viewModel, sessionId, branchId)
        else SearchResultsScreen(state, viewModel, sessionId, branchId, onBack, resultListState)
    }
    }
}

@Composable private fun SearchResultsScreen(state: SearchState, vm: SearchViewModel, sessionId: Long, branchId: String, onBack: () -> Unit, listState: androidx.compose.foundation.lazy.LazyListState) {
    val focus = LocalFocusManager.current
    fun submit() { focus.clearFocus(); vm.search(sessionId, branchId) }
    LaunchedEffect(state.completedQuery) { if (state.completedQuery.isNotBlank()) listState.scrollToItem(0) }
    Column(Modifier.fillMaxSize().imePadding()) {
        SearchQueryToolbar(state.query, vm::setQuery, onBack, ::submit, !state.searching)
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = state.exactMatch, onClick = {
                val repeatSearch = state.completedQuery.isNotBlank()
                vm.setExact(!state.exactMatch)
                if (repeatSearch) submit()
            }, label = { Text("精确匹配") })
            Text(if (state.query.isBlank()) "搜索历史" else "当前故事线", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.indexing) {
            SearchIndexingState()
        } else if (state.query.isBlank() && state.history.isEmpty()) {
            SearchEmptyState("查找对话中的内容", "输入角色名、剧情关键词或一段原文，搜索当前故事线。")
        } else if (state.query.isBlank()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { vm.clearHistory(sessionId) }) { Text("清空历史") }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState) {
                items(state.history, key = { it }) { query ->
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.History, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(query, Modifier.weight(1f).clickable { vm.setQuery(query); submit() }
                            .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 14.dp),
                            style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        IconButton({ vm.removeHistory(sessionId, query) }) { Icon(Icons.Default.Close, "删除历史：$query") }
                    }
                    HorizontalDivider(Modifier.padding(start = 52.dp, end = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        } else {
            if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.error != null) Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.error, color = MaterialTheme.colorScheme.onErrorContainer)
                    TextButton(enabled = !state.searching, onClick = {
                        when (state.failedPage) {
                            SearchPageDirection.NEWER -> vm.loadNewer(sessionId, branchId)
                            SearchPageDirection.OLDER -> vm.loadOlder(sessionId, branchId)
                            null -> submit()
                        }
                    }) { Text(when (state.failedPage) {
                        SearchPageDirection.NEWER -> "重试加载较新结果"
                        SearchPageDirection.OLDER -> "重试加载更早结果"
                        null -> "重新搜索"
                    }) }
                }
            }
            state.totalMatches?.let { Text("共 $it 条匹配消息", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (state.counting) Text("已显示 ${state.hits.size} 条 · 正在统计总数…", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
            if (state.hasNewer && state.hits.isNotEmpty()) Text("当前显示第 ${state.firstHitOffset + 1}–${state.firstHitOffset + state.hits.size} 条", Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.countError != null) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(state.countError, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { vm.countMatches(sessionId, branchId) }) { Text("重新统计") }
            }
            if (!state.searching && state.error == null && state.hits.isEmpty()) {
                if (state.totalMatches == 0) SearchEmptyState("没有找到匹配消息", "试试更短的关键词，或关闭精确匹配。")
                else if (state.completedQuery.isBlank()) SearchEmptyState("准备搜索", "点击搜索按钮或键盘上的搜索键查看结果。")
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                if (state.hasNewer && state.error == null) item(key = "load_newer") {
                    TextButton(enabled = !state.searching, onClick = { vm.loadNewer(sessionId, branchId) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.searching) "正在加载…" else "加载较新结果")
                    }
                }
                items(state.hits, key = { it.message.id }) { hit -> SearchResultCard(hit, state.completedQuery, !state.searching) { focus.clearFocus(); vm.openHit(sessionId, branchId, hit.message.id) } }
                if (state.hasOlder && state.error == null) item(key = "load_older") {
                    TextButton(enabled = !state.searching, onClick = { vm.loadOlder(sessionId, branchId) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.searching) "正在加载…" else "加载更早结果")
                    }
                }
            }
        }
    }
}

@Composable private fun SearchIndexingState() {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("正在整理旧消息的搜索索引", style = MaterialTheme.typography.titleMedium)
        Text(
            "索引完成后会显示完整结果和匹配数量。你也可以返回，或修改关键词后重新搜索。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable private fun SearchEmptyState(title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable private fun SearchResultCard(hit: SearchHit, query: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(when (hit.message.speakerType) { "user" -> "我"; "narrator" -> "旁白"; else -> "角色" },
                    Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                Text(formatDate(hit.message.createdAt), Modifier.padding(start = 12.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HighlightedText(hit.snippet, query)
        }
    }
    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable private fun SearchQueryToolbar(
    query: String, onQuery: (String) -> Unit, onBack: () -> Unit,
    onSearch: () -> Unit, enabled: Boolean,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        OutlinedTextField(query, onQuery, Modifier.weight(1f), shape = RoundedCornerShape(12.dp),
            singleLine = true, placeholder = { Text("搜索消息") },
            trailingIcon = { if (query.isNotEmpty()) IconButton({ onQuery("") }) { Icon(Icons.Default.Close, "清除关键词") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (enabled && query.isNotBlank()) onSearch() }))
        IconButton(onClick = onSearch, enabled = enabled && query.isNotBlank()) { Icon(Icons.Default.Search, "搜索") }
    }
}

@Composable private fun SearchContextScreen(state: SearchState, vm: SearchViewModel, sessionId: Long, branchId: String) {
    val index = state.hits.indexOfFirst { it.message.id == state.selectedMessageId }
    val current = (state.firstHitOffset + index + 1).coerceAtLeast(1)
    var readerQuery by androidx.compose.runtime.saveable.rememberSaveable(state.completedQuery) { mutableStateOf(state.completedQuery) }
    val focus = LocalFocusManager.current
    val submit = {
        if (readerQuery.isNotBlank()) {
            focus.clearFocus()
            vm.setQuery(readerQuery)
            vm.search(sessionId, branchId)
        }
    }
    Box(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.fillMaxSize().padding(bottom = 64.dp)) {
            SearchQueryToolbar(readerQuery, { readerQuery = it }, vm::closeHit, { submit() }, !state.searching)
            if ((state.searching && state.contextMessages.isEmpty()) || state.contextLoadingBefore || state.contextLoadingAfter) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!state.searching && state.contextMessages.isEmpty()) {
                Text(state.error ?: "找不到这条消息", Modifier.padding(20.dp), color = if (state.error == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                if (state.error != null) Text("重试", Modifier.clickable { state.selectedMessageId?.let { vm.openHit(sessionId, branchId, it) } }.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.primary)
            }
            if (state.error != null && state.contextMessages.isNotEmpty()) {
                if (state.contextFailedBefore || state.contextFailedAfter) {
                    TextButton(enabled = !state.contextLoadingBefore && !state.contextLoadingAfter,
                        onClick = { vm.retryContext(sessionId, branchId) }) { Text("${state.error} · 重试") }
                } else {
                    TextButton(onClick = { vm.navigateHit(sessionId, branchId,
                        if (state.failedPage == SearchPageDirection.NEWER) -1 else 1) }) { Text(state.error) }
                }
            }
            if (state.contextMessages.isNotEmpty() && (state.contextBeforeHasMore || state.contextFailedBefore || state.contextLoadingBefore)) {
                TextButton(enabled = !state.contextLoadingBefore && !state.contextLoadingAfter,
                    onClick = { vm.loadMoreContext(sessionId, branchId, SearchContextDirection.BEFORE) },
                    modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.contextLoadingBefore) "正在加载上文…" else "加载更多上文")
                }
            }
            val listState = rememberLazyListState()
            var positionedId by remember(state.selectedMessageId, state.completedQuery) { mutableStateOf<Long?>(null) }
            val targetPresent = state.selectedMessageId != null && state.contextMessages.any { it.id == state.selectedMessageId }
            LaunchedEffect(state.selectedMessageId, targetPresent) {
                val i = state.contextMessages.indexOfFirst { it.id == state.selectedMessageId }
                if (i >= 0) { listState.scrollToItem(i); positionedId = state.selectedMessageId }
            }
            val presentation = state.presentation
            val lines = remember(state.contextMessages, presentation) {
                state.contextMessages.map { ChatDisplayLine("search_${it.id}", null, listOf(it), 0) }
                    .decorateChatLineList(presentation.characters.mapValues { it.value.name }, presentation.userName, presentation.narratorName)
            }
            CompositionLocalProvider(LocalChatDensityMetrics provides ChatDensityMode.fromStorage(presentation.density).toMetrics().copy(
                rowHorizontal = 12.dp, narratorHorizontal = 12.dp, bubbleMaxWidth = 720.dp,
            )) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 128.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(lines, key = { it.line.stableKey }) { meta ->
                        val message = meta.line.selectedMessage()
                        val focused = message.id == state.selectedMessageId
                        val highlight = remember(message.id, state.completedQuery, positionedId) {
                            MessageSearchHighlight(state.completedQuery, focused && positionedId == message.id)
                        }
                        val character = presentation.characters[message.characterId]
                        Column(Modifier.fillMaxWidth()) {
                            if (focused) Surface(modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 6.dp),
                                shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                                Text(formatDate(message.createdAt), Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelMedium)
                            }
                            CompositionLocalProvider(LocalMessageSearchHighlight provides highlight) {
                                MessageLineBlock(line = meta.line, messageAttachments = presentation.attachments,
                                    avatarPath = character?.avatar.orEmpty(), avatarColor = character?.color ?: "#F97316", cardImagePath = character?.card.orEmpty(),
                                    userAvatarImagePath = presentation.userAvatar, userAvatarColor = presentation.userColor, userDisplayName = presentation.userName,
                                    bookmarkedMessageIds = emptySet(), senderLabel = meta.senderLabel, showSenderHeader = meta.showSenderHeader, timeText = meta.timeText,
                                    onAction = {}, onSelectSwipeVersion = { _, _, onResult -> onResult(false) }, readOnly = true)
                            }
                        }
                    }
                    if (state.contextMessages.isNotEmpty() && (state.contextAfterHasMore || state.contextFailedAfter || state.contextLoadingAfter)) item(key = "load_more_context_after") {
                        TextButton(enabled = !state.contextLoadingBefore && !state.contextLoadingAfter,
                            onClick = { vm.loadMoreContext(sessionId, branchId, SearchContextDirection.AFTER) },
                            modifier = Modifier.fillMaxWidth()) {
                            Text(if (state.contextLoadingAfter) "正在加载下文…" else "加载更多下文")
                        }
                    }
                }
            }
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 80.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 3.dp) {
                IconButton(enabled = index >= 0 && (index > 0 || state.hasNewer) && !state.searching, onClick = { vm.navigateHit(sessionId, branchId, -1) }) { Icon(Icons.Default.KeyboardArrowUp, "上一个") }
            }
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 3.dp) {
                IconButton(enabled = index >= 0 && (index < state.hits.lastIndex || state.hasOlder) && !state.searching, onClick = { vm.navigateHit(sessionId, branchId, 1) }) { Icon(Icons.Default.KeyboardArrowDown, "下一个") }
            }
        }
        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 1.dp) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$current / ${state.totalMatches?.toString() ?: "${state.firstHitOffset + state.hits.size}+"}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = vm::closeHit) { Text("以列表显示") }
            }
        }
    }
}

@Composable private fun HighlightedText(text: String, query: String) {
    val annotated = remember(text, query) { highlightedMessageText(text, messageSearchRanges(text, query)) }
    Text(annotated, style = MaterialTheme.typography.bodyMedium)
}

private fun formatDate(epoch: Long): String = if (epoch == 0L) "" else Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm"))
