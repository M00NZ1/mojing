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
import com.mojing.app.ui.common.MoJingTextField

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
) {
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val requestDismiss = { if (hasChanges) confirmDiscard = true else onDismiss() }
    val dirty by rememberUpdatedState(hasChanges)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = {
            if (it == SheetValue.Hidden && dirty) {
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
        contentWindowInsets = { WindowInsets.safeDrawing },
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.95f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("编辑消息", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = requestDismiss) { Icon(Icons.Default.Close, "关闭消息编辑") }
            }
            if (!WindowInsets.isImeVisible) Text(
                if (isUser) "保存到新故事线，并重新生成回复。" else "保存到新故事线，保留原故事线。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MoJingTextField(
                value = content,
                onValueChange = onContentChange,
                modifier = Modifier.fillMaxWidth().weight(1f),
                label = { Text("消息正文") },
                textStyle = MaterialTheme.typography.bodyLarge,
            )
            MoJingButton(onClick = onSave, enabled = canSave, modifier = Modifier.fillMaxWidth()) {
                Text("创建编辑分支")
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
