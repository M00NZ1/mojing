package com.mojing.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun BranchSelector(
    expanded: Boolean,
    onDismiss: () -> Unit,
    branches: List<Pair<String, String>>,
    currentBranch: String,
    onSelect: (String) -> Unit,
    onShowBranchOverview: (() -> Unit)? = null,
) {
    if (!expanded) return
    var query by remember { mutableStateOf("") }
    val choices = remember(branches, query) {
        val needle = query.trim()
        branches.distinctBy { it.first }.filter { (id, label) ->
            needle.isEmpty() || storyLineDisplayLabel(id, label).contains(needle, ignoreCase = true)
        }
    }
    val listState = remember(query, currentBranch) {
        LazyListState(choices.indexOfFirst { it.first == currentBranch }.coerceAtLeast(0))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择故事线", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索故事线") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true, shape = RoundedCornerShape(14.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { onSelect("CREATE_NEW"); onDismiss() }) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("新建")
                    }
                    Spacer(Modifier.weight(1f))
                    if (onShowBranchOverview != null) TextButton(onClick = { onDismiss(); onShowBranchOverview() }) { Text("故事线总览") }
                }
                HorizontalDivider()
                if (choices.isEmpty()) Text("没有匹配的故事线", color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(choices, key = { it.first }) { (id, label) ->
                        val current = id == currentBranch
                        Surface(onClick = { if (!current) onSelect(id); onDismiss() },
                            modifier = Modifier.fillMaxWidth().semantics { selected = current },
                            shape = RoundedCornerShape(12.dp),
                            color = if (current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                            Row(Modifier.heightIn(min = 52.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(storyLineDisplayLabel(id, label), Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (current) Icon(Icons.Default.Check, "当前故事线", Modifier.padding(start = 8.dp).size(20.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
