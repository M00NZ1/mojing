package com.mojing.app.ui.chat.drawer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mojing.app.data.local.dao.NewSessionCharacterOption
import com.mojing.app.ui.chat.AddParticipantPage
import com.mojing.app.ui.common.MoJingTextField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable
internal fun AddParticipantDialog(
    loadPage: suspend (String, NewSessionCharacterOption?) -> AddParticipantPage,
    isSubmitting: Boolean,
    submitError: String?,
    onDismiss: () -> Unit,
    onSelect: (Long) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var requestedPage by remember { mutableIntStateOf(0) }
    var displayedPage by remember { mutableIntStateOf(0) }
    var cursors by remember { mutableStateOf<List<NewSessionCharacterOption?>>(listOf(null)) }
    var rows by remember { mutableStateOf<List<NewSessionCharacterOption>>(emptyList()) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var readError by remember { mutableStateOf(false) }
    var retryVersion by remember { mutableIntStateOf(0) }

    LaunchedEffect(query, requestedPage, retryVersion) {
        loading = true
        readError = false
        try {
            if (query.isNotBlank()) delay(200)
            val result = loadPage(query, cursors.getOrNull(requestedPage))
            rows = result.rows
            hasMore = result.hasMore
            displayedPage = requestedPage
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            readError = true
        } finally {
            loading = false
        }
    }

    AlertDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        title = { Text("添加参与角色") },
        text = {
            Column {
                MoJingTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        requestedPage = 0
                        displayedPage = 0
                        cursors = listOf(null)
                        rows = emptyList()
                        hasMore = false
                    },
                    label = { Text("搜索角色") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isSubmitting,
                )
                if (loading && rows.isEmpty()) {
                    CircularProgressIndicator(Modifier.padding(16.dp))
                } else if (rows.isEmpty() && !readError) {
                    Text("没有可加入的角色", Modifier.padding(vertical = 16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                        items(rows, key = { it.id }) { character ->
                            TextButton(
                                onClick = { onSelect(character.id) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !loading && !readError && !isSubmitting,
                            ) {
                                Text(character.name.ifBlank { "未命名" }, Modifier.weight(1f))
                                if (character.favorite) Text("★")
                            }
                        }
                    }
                }
                if (readError) Row(Modifier.fillMaxWidth()) {
                    Text("角色读取失败", Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { retryVersion++ }) { Text("重试") }
                }
                if (submitError != null) Text(submitError, color = MaterialTheme.colorScheme.error)
                if (isSubmitting) Text("正在添加角色…")
                if (rows.isNotEmpty()) Row(Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = { requestedPage = displayedPage - 1 },
                        enabled = displayedPage > 0 && !loading && !readError && !isSubmitting,
                    ) { Text("上一页") }
                    Text("第 ${displayedPage + 1} 页", Modifier.weight(1f).padding(top = 12.dp),
                        style = MaterialTheme.typography.labelSmall)
                    TextButton(
                        onClick = {
                            cursors = cursors.take(displayedPage + 1) + rows.last()
                            requestedPage = displayedPage + 1
                        },
                        enabled = hasMore && !loading && !readError && !isSubmitting,
                    ) { Text("下一页") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !isSubmitting) { Text("取消") } },
    )
}
