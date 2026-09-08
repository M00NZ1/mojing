package com.mojing.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.mojing.app.ui.common.hideImeKeyboard

/** Inserts [insertion] at the selected range and places the cursor after it. */
fun insertTextAtSelection(text: String, selection: TextRange, insertion: String): TextFieldValue {
    val start = selection.start.coerceIn(0, text.length)
    val end = selection.end.coerceIn(0, text.length)
    val rangeStart = minOf(start, end)
    val rangeEnd = maxOf(start, end)
    val updatedText = buildString(text.length - (rangeEnd - rangeStart) + insertion.length) {
        append(text, 0, rangeStart)
        append(insertion)
        append(text, rangeEnd, text.length)
    }
    val cursor = rangeStart + insertion.length
    return TextFieldValue(text = updatedText, selection = TextRange(cursor))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InputBar(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isGenerating: Boolean,
    isAddingAttachment: Boolean = false,
    generationModeLabel: String? = null,
    onRequestNarrator: (String) -> Unit,
    onInsertMacro: (String) -> Unit,
    pendingAttachmentCount: Int = 0,
    onClearPendingAttachments: () -> Unit,
    isListening: Boolean = false,
    isImeOpen: Boolean = false,
    onVoiceClick: () -> Unit,
    onImageGenClick: () -> Unit,
    onAttachImageClick: () -> Unit,
    onOpenEmoji: () -> Unit,
    /** 朗读当前输入框文字（不发送） */
    onPreviewSpeak: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showActionSheet by remember { mutableStateOf(false) }
    var showMacros by remember { mutableStateOf(false) }
    var showNarratorDialog by remember { mutableStateOf(false) }
    var narratorGuidance by remember { mutableStateOf("") }
    var restoreInputFocus by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    fun dismissKeyboard() {
        hideImeKeyboard(keyboard, focusManager)
    }

    // Generation can start from another entry point (for example regenerate). Close
    // transient action UI immediately so stale actions cannot fire during a run.
    LaunchedEffect(isGenerating, isAddingAttachment) {
        if (isGenerating || isAddingAttachment) {
            showActionSheet = false
            showNarratorDialog = false
            narratorGuidance = ""
            restoreInputFocus = false
        }
    }

    LaunchedEffect(showActionSheet, restoreInputFocus) {
        if (!showActionSheet && restoreInputFocus) {
            inputFocusRequester.requestFocus()
            keyboard?.show()
            restoreInputFocus = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        if (isAddingAttachment) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    "正在把所选图片保存到本机…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (pendingAttachmentCount > 0) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "已选 $pendingAttachmentCount 张图片",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                TextButton(
                    onClick = onClearPendingAttachments,
                    enabled = !isGenerating && !isAddingAttachment,
                ) { Text("清除") }
            }
        }
        if (isListening) {
            Text(
                "正在听取语音…",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        generationModeLabel?.takeIf { it.isNotBlank() }?.let { label ->
            Text(
                "正在生成：$label",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        ) {
            Column(Modifier.padding(6.dp)) {
                TextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp, max = 128.dp)
                        .focusRequester(inputFocusRequester)
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) keyboard?.show()
                        },
                    placeholder = { Text("输入消息…") },
                    shape = RoundedCornerShape(16.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    minLines = 1,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (!isGenerating && !isAddingAttachment &&
                                (value.text.isNotBlank() || pendingAttachmentCount > 0)
                            ) {
                                onSend()
                            }
                        },
                    ),
                )
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                ) {
                    val useCompactSendAction = maxWidth < 320.dp
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(0.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (!isGenerating) {
                                IconButton(onClick = {
                                    dismissKeyboard()
                                    onAttachImageClick()
                                }, enabled = !isAddingAttachment, modifier = Modifier.size(48.dp)) {
                                    Icon(
                                        Icons.Default.AttachFile,
                                        "添加图片",
                                        modifier = Modifier.size(22.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = {
                                    dismissKeyboard()
                                    showActionSheet = true
                                }, enabled = !isAddingAttachment, modifier = Modifier.size(48.dp)) {
                                    Icon(
                                        Icons.Default.Add,
                                        "更多输入工具",
                                        modifier = Modifier.size(22.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (isImeOpen) {
                                IconButton(
                                    onClick = ::dismissKeyboard,
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Icon(
                                        Icons.Default.KeyboardHide,
                                        "收起键盘",
                                        modifier = Modifier.size(22.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isGenerating) {
                                FilledTonalButton(
                                    onClick = onStop,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) {
                                    Icon(Icons.Default.Stop, "停止", modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("停止")
                                }
                            } else {
                                val sendEnabled = !isAddingAttachment &&
                                    (value.text.isNotBlank() || pendingAttachmentCount > 0)
                                if (useCompactSendAction) {
                                    FilledIconButton(
                                        onClick = onSend,
                                        enabled = sendEnabled,
                                        modifier = Modifier.size(48.dp),
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.Send, "发送", modifier = Modifier.size(20.dp))
                                    }
                                } else {
                                    Button(
                                        onClick = onSend,
                                        enabled = sendEnabled,
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.Send, "发送", modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("发送")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showActionSheet && !isGenerating && !isAddingAttachment) {
        ModalBottomSheet(onDismissRequest = { showActionSheet = false }) {
            LazyColumn(
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .heightIn(max = 560.dp),
            ) {
                item {
                    Text(
                        "输入工具",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                    )
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChatToolTile("语音输入", "转成文字", Icons.Default.Mic, Modifier.weight(1f)) {
                            showActionSheet = false
                            onVoiceClick()
                        }
                        ChatToolTile("表情", "插入到消息", Icons.Default.EmojiEmotions, Modifier.weight(1f)) {
                            showActionSheet = false
                            onOpenEmoji()
                        }
                    }
                }
                item {
                    Text("创作", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChatToolTile("生成旁白", "场景与剧情", Icons.Default.TheaterComedy, Modifier.weight(1f)) {
                            showNarratorDialog = true
                            showActionSheet = false
                        }
                        ChatToolTile("生成配图", "描述画面", Icons.Default.Image, Modifier.weight(1f)) {
                            showActionSheet = false
                            onImageGenClick()
                        }
                    }
                }
                item {
                    ListItem(
                        headlineContent = { Text("试听朗读") },
                        supportingContent = { Text(if (value.text.isBlank()) "输入文字后可试听" else "朗读输入框内容") },
                        leadingContent = { Icon(Icons.AutoMirrored.Filled.VolumeUp, null) },
                        modifier = Modifier.clickable(enabled = value.text.isNotBlank()) {
                            showActionSheet = false
                            onPreviewSpeak()
                        },
                    )
                    ListItem(
                        headlineContent = { Text("快捷词") },
                        supportingContent = { Text("角色名、时间与场景变量") },
                        leadingContent = { Icon(Icons.Default.DataObject, null) },
                        trailingContent = { Icon(if (showMacros) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null) },
                        modifier = Modifier.clickable { showMacros = !showMacros },
                    )
                }
                if (showMacros) {
                    items(ChatMacroDefinitions.ALL) { m ->
                        ListItem(
                            headlineContent = { Text(m.label) },
                            supportingContent = { Text(m.description) },
                            leadingContent = { Icon(Icons.Default.DataObject, contentDescription = null) },
                            modifier = Modifier.clickable(enabled = !isGenerating && !isAddingAttachment) {
                                onInsertMacro(m.macro)
                                restoreInputFocus = true
                                showActionSheet = false
                            },
                        )
                    }
                }
            }
        }
    }
    if (showNarratorDialog) {
        AlertDialog(
            onDismissRequest = {
                dismissKeyboard()
                showNarratorDialog = false
            },
            title = { Text("生成旁白") },
            text = {
                OutlinedTextField(
                    value = narratorGuidance,
                    onValueChange = { narratorGuidance = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("剧情方向（可选）") },
                    placeholder = { Text("例如：推进到夜晚，并让众人发现屋外的异响") },
                    minLines = 3,
                    maxLines = 6,
                )
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        dismissKeyboard()
                        showNarratorDialog = false
                        narratorGuidance = ""
                        onRequestNarrator("")
                    }) { Text("自动生成") }
                    TextButton(onClick = {
                        val guidance = narratorGuidance.trim()
                        dismissKeyboard()
                        showNarratorDialog = false
                        narratorGuidance = ""
                        onRequestNarrator(guidance)
                    }) { Text("按我的方向生成") }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    dismissKeyboard()
                    showNarratorDialog = false
                    narratorGuidance = ""
                }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ChatToolTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 100.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
