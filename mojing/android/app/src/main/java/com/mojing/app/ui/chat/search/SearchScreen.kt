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
    val resultListState = rememberLazyListState()
    LaunchedEffect(sessionId, branchId) { viewModel.initialize(sessionId, branchId) }
    BackHandler { if (state.selectedMessageId != null) viewModel.closeHit() else onBack() }
    Surface(Modifier.fillMaxSize().systemBarsPadding()) {
        if (state.selectedMessageId != null) SearchContextScreen(state, viewModel, sessionId, branchId, onBack)
        else SearchResultsScreen(state, viewModel, sessionId, branchId, onBack, resultListState)
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
            IconButton(onClick = { submit() }, enabled = !state.searching) { Icon(Icons.Default.Search, "搜索") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = state.exactMatch, onClick = { vm.setExact(!state.exactMatch) }, label = { Text("精确匹配") })
            Text(if (state.query.isBlank()) "搜索历史" else "当前故事线", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.query.isBlank() && state.history.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.End) { Text("清空", Modifier.clickable { vm.clearHistory(sessionId) }.padding(8.dp), color = MaterialTheme.colorScheme.primary) }
            LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp), state = listState) { items(state.history, key = { it }) { query -> Row(Modifier.fillMaxWidth().padding(start = 16.dp)) { Text(query, Modifier.weight(1f).clickable { vm.setQuery(query); submit() }.padding(horizontal = 8.dp, vertical = 16.dp), style = MaterialTheme.typography.bodyLarge); IconButton({ vm.removeHistory(sessionId, query) }) { Icon(Icons.Default.Close, "删除历史") } } } }
        } else {
            if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.error != null) Text(state.error, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error)
            state.totalMatches?.let { Text("共 $it 条匹配消息", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (!state.searching && state.error == null && state.hits.isEmpty() && state.totalMatches == 0) Text("没有找到匹配消息", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.hits, key = { it.message.id }) { hit -> SearchResultCard(hit, state.completedQuery) { focus.clearFocus(); vm.openHit(sessionId, branchId, hit.message.id) } }
                if (state.hasOlder) item { Text("加载更早结果", Modifier.fillMaxWidth().clickable { vm.loadOlder(sessionId, branchId) }.padding(18.dp), color = MaterialTheme.colorScheme.primary) }
            }
        }
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

@Composable private fun SearchContextScreen(state: SearchState, vm: SearchViewModel, sessionId: Long, branchId: String, onBack: () -> Unit) {
    val index = state.hits.indexOfFirst { it.message.id == state.selectedMessageId }
    val current = (index + 1).coerceAtLeast(1)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = vm::closeHit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回搜索结果") }; Text("消息原文", style = MaterialTheme.typography.titleMedium); Text("  ${formatDate(state.selectedMessageId?.let { id -> state.contextMessages.firstOrNull { it.id == id }?.createdAt ?: 0L } ?: 0L)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (state.searching && state.contextMessages.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!state.searching && state.contextMessages.isEmpty()) {
                Text(state.error ?: "找不到这条消息", Modifier.padding(20.dp), color = if (state.error == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                if (state.error != null) Text("重试", Modifier.clickable { state.selectedMessageId?.let { vm.openHit(sessionId, branchId, it) } }.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.primary)
            }
            if (state.error != null && state.contextMessages.isNotEmpty()) {
                TextButton(onClick = { vm.navigateHit(sessionId, branchId, 1) }) { Text(state.error) }
            }
            val listState = rememberLazyListState()
            LaunchedEffect(state.contextMessages, state.selectedMessageId) { val i = state.contextMessages.indexOfFirst { it.id == state.selectedMessageId }; if (i >= 0) listState.animateScrollToItem(i) }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 64.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { items(state.contextMessages, key = { it.id }) { msg -> ContextMessage(msg, state.completedQuery, msg.id == state.selectedMessageId) } }
        }
        Column(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.large), horizontalAlignment = Alignment.CenterHorizontally) { IconButton(enabled = index > 0 && !state.searching, onClick = { vm.navigateHit(sessionId, branchId, -1) }) { Icon(Icons.Default.KeyboardArrowUp, "上一个") }; Text("$current/${state.totalMatches ?: state.hits.size}", style = MaterialTheme.typography.labelSmall); IconButton(enabled = index >= 0 && (index < state.hits.lastIndex || state.hasOlder) && !state.searching, onClick = { vm.navigateHit(sessionId, branchId, 1) }) { Icon(Icons.Default.KeyboardArrowDown, "下一个") } }
    }
}

@Composable private fun ContextMessage(message: MessageEntity, query: String, focused: Boolean) { Surface(Modifier.fillMaxWidth(), color = if (focused) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) { Column(Modifier.padding(14.dp)) { Text(formatDate(message.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); HighlightedText(ChatMessageTextFormat.visibleBody(message.content, message.speakerType), query) } } }

@Composable private fun HighlightedText(text: String, query: String) { val q = query.trim(); val highlight = MaterialTheme.colorScheme.tertiaryContainer; val foreground = MaterialTheme.colorScheme.onTertiaryContainer; val annotated = remember(text, q, highlight, foreground) { buildAnnotatedString { if (q.isBlank()) append(text) else { var end = 0; Regex(Regex.escape(q), RegexOption.IGNORE_CASE).findAll(text).forEach { m -> append(text.substring(end, m.range.first)); withStyle(SpanStyle(background = highlight, color = foreground)) { append(m.value) }; end = m.range.last + 1 }; append(text.substring(end)) } } }; Text(annotated, style = MaterialTheme.typography.bodyMedium) }

private fun formatDate(epoch: Long): String = if (epoch == 0L) "" else Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm"))
