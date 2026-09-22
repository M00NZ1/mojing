package com.mojing.app.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mojing.app.ui.common.MoJingButton
import com.mojing.app.ui.common.MoJingTextField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionRenameSheet(
    initialTitle: String,
    saving: Boolean,
    error: String?,
    onEdit: () -> Unit,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    heading: String = "重命名对话",
    fieldLabel: String = "对话名称",
) {
    var draft by rememberSaveable { mutableStateOf(initialTitle.take(100)) }
    val currentSaving by rememberUpdatedState(saving)
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !currentSaving },
    )
    ModalBottomSheet(
        onDismissRequest = { if (!saving) onDismiss() },
        sheetState = sheetState,
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(heading, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "在故事集和对话页中使用同一个名称",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss, enabled = !saving) {
                    Icon(Icons.Default.Close, contentDescription = "关闭重命名")
                }
            }
            MoJingTextField(
                value = draft,
                onValueChange = { draft = it.replace('\n', ' ').take(100); onEdit() },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(fieldLabel) },
                singleLine = true,
                enabled = !saving,
                supportingText = { Text("最多 100 字") },
                isError = error != null,
            )
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") }
                Spacer(Modifier.width(8.dp))
                MoJingButton(onClick = { onSave(draft.trim()) }, enabled = !saving && draft.isNotBlank()) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (saving) "保存中" else "保存名称", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
