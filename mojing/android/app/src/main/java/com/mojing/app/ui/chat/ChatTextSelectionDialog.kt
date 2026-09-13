package com.mojing.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatTextSelectionDialog(text: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(modifier = Modifier.fillMaxSize(), topBar = {
            TopAppBar(title = { Text("选择文字") }, navigationIcon = {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭") }
            }, actions = {
                TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("复制正文") }
            })
        }) { padding ->
            SelectionContainer(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
                Text(text, modifier = Modifier.padding(20.dp), style = LocalChatDensityMetrics.current.bodyTextStyle(),
                    color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
