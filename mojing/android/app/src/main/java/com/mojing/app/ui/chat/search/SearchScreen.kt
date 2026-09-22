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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.mojing.app.ui.common.MoJingTextField as OutlinedTextField
import androidx.compose.foundation.border
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            OutlinedTextField(state.query, vm::setQuery, Modifier.weight(1f).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp)), shape = RoundedCornerShape(20.dp), singleLine = true, placeholder = { Text("搜索消息") }, leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = { if (state.query.isNotEmpty()) IconButton({ vm.setQuery("") }) { Icon(Icons.Default.Close, "清除") } }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit() }))
            IconButton(onClick = { submit() }, enabled = !state.searching && state.query.isNotBlank()) { Icon(Icons.Default.Search, "搜索") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = state.exactMatch, onClick = {
                val repeatSearch = state.completedQuery.isNotBlank()
                vm.setExact(!state.exactMatch)
                if (repeatSearch) submit()
            }, label = { Text("精确匹配") })
            Text(if (state.query.isBlank()) "搜索历史" else "当前故事线", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.query.isBlank() && state.history.isEmpty()) {
            SearchEmptyState("查找对话中的内容", "输入角色名、剧情关键词或一段原文，搜索当前故事线。")
        } else if (state.query.isBlank()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.End) { Text("清空", Modifier.clickable { vm.clearHistory(sessionId) }.padding(8.dp), color = MaterialTheme.colorScheme.primary) }
            LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp), state = listState) { items(state.history, key = { it }) { query -> Row(Modifier.fillMaxWidth().padding(start = 16.dp)) { Text(query, Modifier.weight(1f).clickable { vm.setQuery(query); submit() }.padding(horizontal = 8.dp, vertical = 16.dp), style = MaterialTheme.typography.bodyLarge); IconButton({ vm.removeHistory(sessionId, query) }) { Icon(Icons.Default.Close, "删除历史") } } } }
        } else {
            if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.error != null) Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.error, color = MaterialTheme.colorScheme.onErrorContainer)
                    TextButton(enabled = !state.searching, onClick = {
                        if (state.hits.isEmpty()) submit() else vm.loadOlder(sessionId, branchId)
                    }) { Text(if (state.hits.isEmpty()) "重新搜索" else "重试加载更早结果") }
                }
            }
            state.totalMatches?.let { Text("共 $it 条匹配消息", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (state.counting) Text("已加载 ${state.hits.size} 条 · 正在统计总数…", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
            if (state.countError != null) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(state.countError, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { vm.countMatches(sessionId, branchId) }) { Text("重新统计") }
            }
            if (!state.searching && state.error == null && state.hits.isEmpty()) {
                if (state.totalMatches == 0) SearchEmptyState("没有找到匹配消息", "试试更短的关键词，或关闭精确匹配。")
                else if (state.completedQuery.isBlank()) SearchEmptyState("准备搜索", "点击搜索按钮或键盘上的搜索键查看结果。")
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.hits, key = { it.message.id }) { hit -> SearchResultCard(hit, state.completedQuery) { focus.clearFocus(); vm.openHit(sessionId, branchId, hit.message.id) } }
                if (state.hasOlder && state.error == null) item {
                    TextButton(enabled = !state.searching, onClick = { vm.loadOlder(sessionId, branchId) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.searching) "正在加载…" else "加载更早结果")
                    }
                }
            }
        }
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

@Composable private fun SearchResultCard(hit: SearchHit, query: String, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(formatDate(hit.message.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(when (hit.message.speakerType) { "user" -> "我"; "narrator" -> "旁白"; else -> "角色" }, style = MaterialTheme.typography.labelLarge)
            HighlightedText(hit.snippet, query)
        }
    }
}

@Composable private fun SearchContextScreen(state: SearchState, vm: SearchViewModel, sessionId: Long, branchId: String) {
    val index = state.hits.indexOfFirst { it.message.id == state.selectedMessageId }
    val current = (index + 1).coerceAtLeast(1)
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
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = vm::closeHit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回搜索结果") }
                OutlinedTextField(value = readerQuery, onValueChange = { readerQuery = it }, modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(28.dp), singleLine = true, placeholder = { Text("搜索消息") },
                    trailingIcon = { IconButton(onClick = { readerQuery = "" }) { Icon(Icons.Default.Close, "清除关键词") } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit() }))
            }
            if (state.searching && state.contextMessages.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!state.searching && state.contextMessages.isEmpty()) {
                Text(state.error ?: "找不到这条消息", Modifier.padding(20.dp), color = if (state.error == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                if (state.error != null) Text("重试", Modifier.clickable { state.selectedMessageId?.let { vm.openHit(sessionId, branchId, it) } }.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.primary)
            }
            if (state.error != null && state.contextMessages.isNotEmpty()) {
                TextButton(onClick = { vm.navigateHit(sessionId, branchId, 1) }) { Text(state.error) }
            }
            val listState = rememberLazyListState()
            var positionedId by remember(state.selectedMessageId, state.completedQuery) { mutableStateOf<Long?>(null) }
            LaunchedEffect(state.contextMessages, state.selectedMessageId) {
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
                }
            }
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 80.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 3.dp) {
                IconButton(enabled = index > 0 && !state.searching, onClick = { vm.navigateHit(sessionId, branchId, -1) }) { Icon(Icons.Default.KeyboardArrowUp, "上一个") }
            }
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 3.dp) {
                IconButton(enabled = index >= 0 && (index < state.hits.lastIndex || state.hasOlder) && !state.searching, onClick = { vm.navigateHit(sessionId, branchId, 1) }) { Icon(Icons.Default.KeyboardArrowDown, "下一个") }
            }
        }
        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$current / ${state.totalMatches?.toString() ?: "${state.hits.size}+"}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
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
