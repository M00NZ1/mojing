package com.mojing.app.ui.encyclopedia

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mojing.app.ui.common.MoJingOutlinedButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EntrySourcePreview(loading: Boolean, content: String?, error: String?, onClose: () -> Unit, onRetry: () -> Unit,
    sourceIndex: Int = 0, sourceCount: Int = 1, onSourceChange: (Int) -> Unit = {},
    onOpenConversation: (() -> Unit)? = null) {
    ModalBottomSheet(onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("对话原文", style = MaterialTheme.typography.titleLarge)
            if (sourceCount > 1) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    TextButton(onClick = { onSourceChange(sourceIndex - 1) }, enabled = !loading && sourceIndex > 0) { Text("上一条") }
                    Text("${sourceIndex + 1} / $sourceCount", style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { onSourceChange(sourceIndex + 1) }, enabled = !loading && sourceIndex < sourceCount - 1) { Text("下一条") }
                }
            }
            key(sourceIndex) { Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                when {
                    loading -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在读取原文…") }
                    error != null -> Text(error, color = MaterialTheme.colorScheme.error)
                    else -> SelectionContainer { Text(content?.takeIf { it.isNotBlank() } ?: "这条消息没有文字内容。",
                        style = MaterialTheme.typography.bodyLarge) }
                }
            } }
            Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                if (onOpenConversation != null) MoJingOutlinedButton(onClick = onOpenConversation, modifier = Modifier.fillMaxWidth()) { Text("进入来源故事线") }
                if (error != null) MoJingOutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("重新读取") }
                TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("返回编辑") }
            }
        }
    }
}
