package com.mojing.app.ui.encyclopedia

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mojing.app.ui.common.MoJingOutlinedButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EntrySourcePreview(
    loading: Boolean,
    content: String?,
    error: String?,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    sourceIndex: Int = 0,
    sourceCount: Int = 1,
    onSourceChange: (Int) -> Unit = {},
    onOpenConversation: (() -> Unit)? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("对话原文", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "关闭原文") }
            }
            HorizontalDivider()
            key(sourceIndex) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when {
                        loading -> {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("正在读取原文…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        error != null -> {
                            Text("原文读取失败", style = MaterialTheme.typography.titleSmall)
                            Text(error, color = MaterialTheme.colorScheme.error)
                            MoJingOutlinedButton(onClick = onRetry) { Text("重新读取") }
                        }
                        else -> SelectionContainer {
                            Text(
                                content?.takeIf { it.isNotBlank() } ?: "这条消息没有文字内容。",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }
            if (sourceCount > 1 || onOpenConversation != null) {
            HorizontalDivider()
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (sourceCount > 1) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { onSourceChange(sourceIndex - 1) }, enabled = !loading && sourceIndex > 0) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "上一条原文")
                            }
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                Text("${sourceIndex + 1} / $sourceCount", style = MaterialTheme.typography.labelLarge)
                            }
                            IconButton(onClick = { onSourceChange(sourceIndex + 1) }, enabled = !loading && sourceIndex < sourceCount - 1) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "下一条原文")
                            }
                        }
                    }
                    if (onOpenConversation != null) {
                        MoJingOutlinedButton(onClick = onOpenConversation, modifier = Modifier.fillMaxWidth()) {
                            Text("进入来源故事线")
                        }
                    }
                }
            }
            }
        }
    }
}
