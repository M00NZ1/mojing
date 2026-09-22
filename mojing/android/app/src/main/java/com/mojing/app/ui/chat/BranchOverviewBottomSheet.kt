package com.mojing.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mojing.app.data.local.entity.SessionBranchEntity

/**
 * 与 Web 分支树/列表信息对等：本机以底表列出各分支及分叉消息摘要（Android 用 BottomSheet，非画布图）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BranchOverviewBottomSheet(
    visible: Boolean,
    branches: List<SessionBranchEntity>,
    sourcePreviews: Map<Long, String>,
    currentBranchId: String = "main",
    onSelectBranch: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val storyBranches = remember(branches) { branches.filterNot { it.branchId == "main" } }
    val labelsById = remember(branches) {
        buildMap {
            put("main", "主线剧情")
            branches.forEach { branch ->
                put(branch.branchId, storyLineDisplayLabel(branch.branchId, branch.label))
            }
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(query) { listState.scrollToItem(0) }
    val filtered = remember(storyBranches, labelsById, sourcePreviews, query) {
        val term = query.trim()
        storyBranches.filter { branch ->
            term.isEmpty() || storyLineDisplayLabel(branch.branchId, branch.label).contains(term, ignoreCase = true) ||
                storyLineParentLabel(branch.parentBranchId, labelsById).contains(term, ignoreCase = true) ||
                sourcePreviews[branch.sourceMessageId].orEmpty().contains(term, ignoreCase = true)
        }
    }
    val showMain = query.isBlank() || "主线剧情 最初的故事线".contains(query.trim(), ignoreCase = true)
    ModalBottomSheet(onDismissRequest = onDismiss, dragHandle = null,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("故事线", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                    Icon(Icons.Default.Search, if (searching) "收起故事线搜索" else "搜索故事线")
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭故事线") }
            }
            if (searching) com.mojing.app.ui.common.MoJingTextField(
                value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, placeholder = { Text("搜索名称、来源或分叉内容") },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "清除搜索") } },
            )
            Text(
                if (query.isBlank()) "${storyBranches.size + 1} 条 · 选择一条继续阅读与创作"
                else "找到 ${filtered.size + if (showMain) 1 else 0} 条故事线",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
            if (showMain) item(key = "main") {
                val isCurrent = currentBranchId == "main"
                ListItem(
                    modifier = Modifier.selectable(
                        selected = isCurrent,
                        role = Role.RadioButton,
                        onClick = {
                            if (!isCurrent) onSelectBranch("main")
                            onDismiss()
                        },
                    ),
                    headlineContent = {
                        Text("主线剧情", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Text(
                            if (isCurrent) "当前正在阅读 · 最初的故事线" else "最初的故事线",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    leadingContent = {
                        Icon(
                            if (isCurrent) Icons.Default.CheckCircle else Icons.Default.AccountTree,
                            contentDescription = null,
                            tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = if (isCurrent) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        } else {
                            Color.Transparent
                        },
                    ),
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (query.isNotBlank() && filtered.isEmpty() && !showMain) item(key = "no-match") {
                Column(Modifier.fillMaxWidth().padding(24.dp)) {
                    Text("没有匹配的故事线", style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { query = "" }) { Text("显示全部故事线") }
                }
            }
            if (storyBranches.isEmpty() && query.isBlank()) {
                item(key = "empty") {
                    Text(
                        "还没有其他故事线。你可以从对话中的任意消息创建新的走向。",
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(filtered, key = { it.branchId }) { branch ->
                val isCurrent = branch.branchId == currentBranchId
                val parentLabel = storyLineParentLabel(branch.parentBranchId, labelsById)
                val preview = sourcePreviews[branch.sourceMessageId]?.trim()?.takeIf { it.isNotEmpty() }
                ListItem(
                    modifier = Modifier.selectable(
                        selected = isCurrent,
                        role = Role.RadioButton,
                        onClick = {
                            if (!isCurrent) onSelectBranch(branch.branchId)
                            onDismiss()
                        },
                    ),
                    headlineContent = {
                        Text(
                            storyLineDisplayLabel(branch.branchId, branch.label),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    supportingContent = {
                        Column {
                            Text(
                                if (isCurrent) "当前故事线 · 来自「$parentLabel」" else "来自「$parentLabel」",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                preview?.let { "从「$it」处分出" } ?: "分叉位置已不可用",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    leadingContent = {
                        Icon(
                            if (isCurrent) Icons.Default.CheckCircle else Icons.Default.AccountTree,
                            contentDescription = null,
                            tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = if (isCurrent) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        } else {
                            Color.Transparent
                        },
                    ),
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}
