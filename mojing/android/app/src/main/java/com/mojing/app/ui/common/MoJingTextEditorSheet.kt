package com.mojing.app.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/** The page retains ownership of its draft; dismissal never creates or discards a second copy. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoJingTextEditorSheet(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onDismiss: () -> Unit,
    enabled: Boolean = true,
    suggestions: @Composable (() -> Unit)? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = 720.dp,
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = LocalFocusManager.current
        val finish = { keyboard?.hide(); focus.clearFocus(); onDismiss() }
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = finish) { Text("完成") }
                IconButton(onClick = finish) { Icon(Icons.Outlined.Close, "关闭$title") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            suggestions?.let { Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { it() } }
            MoJingWritingField(value, onValueChange, title,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 4.dp),
                placeholder = placeholder, enabled = enabled)
        }
    }
}
