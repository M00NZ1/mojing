package com.mojing.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text("故事线", style = MaterialTheme.typography.titleLarge)
            Text(
                "${storyBranches.size + 1} 条 · 选择一条继续阅读与创作",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "main") {
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
            }
            if (storyBranches.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "还没有其他故事线。你可以从对话中的任意消息创建新的走向。",
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(storyBranches, key = { it.branchId }) { branch ->
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
                            maxLines = 1,
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
            }
        }
    }
}
