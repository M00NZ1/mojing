package com.mojing.app.ui.encyclopedia

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mojing.app.ui.common.MoJingIcon

/** Relation rows use real edges, retaining at most one page and no entry bodies. */
@Composable
internal fun EntryRelatedSection(
    state: EntryEditState,
    onManage: () -> Unit,
    onOpen: (Long) -> Unit,
    onRetry: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text("关联内容", style = MaterialTheme.typography.titleMedium)
        if (!state.isPersisted) {
            Text("保存条目后，可以查看和管理人物、地点、势力与其他条目的关系。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            // A full-width action remains reachable on small screens and with large type.
            com.mojing.app.ui.common.MoJingOutlinedButton(
                onClick = onManage, enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("添加或管理关系") }
            if (state.relatedLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("正在读取关联内容…", style = MaterialTheme.typography.bodySmall)
            }
            state.relatedError?.let { error ->
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry, enabled = !state.relatedLoading) { Text("重试关联内容") }
            }
            if (state.relatedLoaded && state.relatedEntries.isEmpty() && state.relatedError == null) {
                Text("暂无条目关系", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.relatedEntries.forEach { edge ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clickable(enabled = enabled) { onOpen(edge.otherEntryId) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(edge.title, style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val type = if (edge.entryType == "faction") "势力 / 组织"
                            else ENTRY_TYPE_LABELS[edge.entryType] ?: edge.entryType
                        val direction = if (edge.fromEntryId == state.persistedEntryId) "本条目 → 对方" else "对方 → 本条目"
                        Text("$type · ${edge.relationType} · $direction", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (edge.label.isNotBlank()) Text(edge.label, style = MaterialTheme.typography.bodySmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    MoJingIcon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "打开关联条目")
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (state.relatedEntries.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    TextButton(onClick = onPrevious, enabled = !state.relatedLoading && state.relatedPageIndex > 0) { Text("上一页") }
                    Text("第 ${state.relatedPageIndex + 1} 页", style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = onNext, enabled = !state.relatedLoading && state.relatedHasNext) { Text("下一页") }
                }
            }
        }
    }
}
