package com.mojing.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The caller owns the draft; closing this sheet does not clear it. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ChatPromptSheet(
    title: String, onDismiss: () -> Unit,
    editor: @Composable ColumnScope.() -> Unit,
    actions: @Composable ColumnScope.() -> Unit,
    dismissEnabled: Boolean = true,
) {
    val canDismiss by rememberUpdatedState(dismissEnabled)
    ModalBottomSheet(onDismissRequest = { if (canDismiss) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden || canDismiss }),
        sheetMaxWidth = 640.dp, dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = { WindowInsets.safeDrawing }) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()
                .fillMaxHeight(if (WindowInsets.isImeVisible || maxHeight < 620.dp) 0.95f else 0.72f)
                .imePadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = onDismiss, enabled = dismissEnabled) { Icon(Icons.Default.Close, "关闭$title") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), content = editor)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp), content = actions)
                }
            }
        }
    }
}
