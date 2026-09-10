package com.mojing.app.ui.encyclopedia

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mojing.app.data.local.dao.EncyclopediaEntryOption
import com.mojing.app.ui.common.MoJingTextField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

@Composable
internal fun EncyclopediaEntryPicker(encyclopediaId: Long, selectedId: Long?,
    loadPage: suspend (String, Long) -> List<EncyclopediaEntryOption>,
    onDismiss: () -> Unit, onSelect: (EncyclopediaEntryOption) -> Unit) {
    var query by remember(encyclopediaId) { mutableStateOf("") }
    var cursors by remember(encyclopediaId) { mutableStateOf(listOf(0L)) }
    var retry by remember { mutableIntStateOf(0) }
    val cursor = cursors.last()
    var rows by remember(encyclopediaId, query, cursor, retry) { mutableStateOf<List<EncyclopediaEntryOption>?>(null) }
    var error by remember(encyclopediaId, query, cursor, retry) { mutableStateOf<String?>(null) }
    LaunchedEffect(encyclopediaId, query, cursor, retry) {
        try {
            val page = loadPage(query, cursor)
            coroutineContext.ensureActive()
            rows = page
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "资料读取失败，请重试" }
    }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("选择条目") },
        text = { Column(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MoJingTextField(query, { query = it; cursors = listOf(0L) }, singleLine = true,
                label = { Text("搜索条目名称") }, modifier = Modifier.fillMaxWidth())
            when {
                error != null -> { Text(error.orEmpty(), color = MaterialTheme.colorScheme.error); TextButton({ retry++ }) { Text("重试") } }
                rows == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                rows!!.isEmpty() -> Text("没有匹配条目")
                else -> LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth().testTag("entry-options")) {
                    items(rows!!.take(50), key = { it.id }) { entry ->
                        TextButton({ onSelect(entry) }, modifier = Modifier.fillMaxWidth().testTag("entry-option:${entry.id}").semantics { selected = entry.id == selectedId }) {
                            Column(Modifier.weight(1f)) {
                                Text(entry.title.ifBlank { "未命名条目" }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(ENTRY_TYPE_LABELS[entry.entryType] ?: "其他", style = MaterialTheme.typography.labelSmall)
                            }
                            if (entry.id == selectedId) Text("已选", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton({ cursors = cursors.dropLast(1) }, enabled = cursors.size > 1) { Text("上一页") }
                Text("第 ${cursors.size} 页", style = MaterialTheme.typography.bodySmall)
                TextButton({ rows?.getOrNull(49)?.let { cursors = cursors + it.id } }, enabled = error == null && (rows?.size ?: 0) > 50) { Text("下一页") }
            }
        } },
        confirmButton = { TextButton(onDismiss) { Text("关闭") } },
    )
}
