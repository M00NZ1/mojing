package com.mojing.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mojing.app.ui.common.MoJingButton
import com.mojing.app.ui.common.MoJingWritingField

@Composable
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
internal fun MessageEditDialog(
    content: String,
    onContentChange: (String) -> Unit,
    isUser: Boolean,
    hasChanges: Boolean,
    canSave: Boolean,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    saving: Boolean = false,
    failure: String? = null,
    committed: Boolean = false,
) {
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val requestDismiss = { if (!saving) { if (hasChanges && !committed) confirmDiscard = true else onDismiss() } }
    val dirty by rememberUpdatedState(hasChanges && !committed)
    val busy by rememberUpdatedState(saving)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = {
            if (it == SheetValue.Hidden && busy) false
            else if (it == SheetValue.Hidden && dirty) {
                confirmDiscard = true
                false
            } else true
        },
    )
    ModalBottomSheet(
        onDismissRequest = requestDismiss,
        sheetState = sheetState,
        sheetMaxWidth = 720.dp,
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = { WindowInsets.safeDrawing },
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.95f).imePadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("编辑消息", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = requestDismiss, enabled = !saving) { Icon(Icons.Default.Close, "关闭消息编辑") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (!WindowInsets.isImeVisible) Text(
                if (isUser) "保存到新故事线，并重新生成回复。" else "保存到新故事线，保留原故事线。",
                modifier = Modifier.padding(horizontal = 20.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MoJingWritingField(
                value = content,
                onValueChange = onContentChange,
                enabled = !saving && !committed,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp),
                label = "消息正文",
                placeholder = "输入消息正文",
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (failure != null) Text(failure, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    MoJingButton(onClick = onSave, enabled = canSave && !saving && !committed, modifier = Modifier.fillMaxWidth()) {
                        Text(if (saving) "正在保存…" else if (committed) "已保存" else "创建编辑分支")
                    }
                }
            }
        }
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("放弃这次编辑？") },
            text = { Text("尚未保存的修改将被放弃。") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("放弃修改") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") } },
        )
    }
}
