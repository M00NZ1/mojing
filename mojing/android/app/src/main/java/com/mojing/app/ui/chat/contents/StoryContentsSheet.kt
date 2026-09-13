package com.mojing.app.ui.chat.contents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryContentsSheet(
    visible: Boolean,
    sessionId: Long,
    branchId: String,
    onOpenMessage: (Long) -> Boolean,
    onDismiss: () -> Unit,
    viewModel: StoryContentsViewModel = hiltViewModel(),
) {
    if (!visible) return
    LaunchedEffect(sessionId, branchId) { viewModel.load(sessionId, branchId) }
    val state by viewModel.state.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text("小说目录", style = MaterialTheme.typography.headlineSmall)
            Text("按当前故事线列出可跳转章节", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
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
                        modifier = Modifier.fillMaxWidth().clickable {
                            if (onOpenMessage(entry.messageId)) onDismiss()
                        },
                        leadingContent = { Icon(Icons.Default.MenuBook, contentDescription = null) },
                        headlineContent = { Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Column {
                                Text(entry.dateLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                if (entry.preview.isNotBlank()) Text(entry.preview, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        },
                    )
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
