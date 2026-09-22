package com.mojing.app.ui.chat

import com.mojing.app.ui.common.MoJingTextField as OutlinedTextField
import com.mojing.app.ui.common.MoJingButton as Button
import com.mojing.app.ui.common.MoJingTonalButton as FilledTonalButton

import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
    modelSelector: (@Composable () -> Unit)? = null,
    narratorGuidance: String = "",
    onNarratorGuidanceChange: (String) -> Unit = {},
) {
    var showActionSheet by remember { mutableStateOf(false) }
    var showMacros by remember { mutableStateOf(false) }
    var showNarratorDialog by remember { mutableStateOf(false) }
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
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp, max = 144.dp)
                        .focusRequester(inputFocusRequester)
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) keyboard?.show()
                        },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { innerTextField ->
                        Box(Modifier.padding(vertical = 11.dp, horizontal = 2.dp), contentAlignment = Alignment.CenterStart) {
                            if (value.text.isEmpty()) Text("输入消息…",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            innerTextField()
                        }
                    },
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
                FilledIconButton(
                    onClick = if (isGenerating) onStop else onSend,
                    enabled = isGenerating || (!isAddingAttachment && (value.text.isNotBlank() || pendingAttachmentCount > 0)),
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    if (isGenerating) Icon(Icons.Default.Stop, "停止")
                    else Icon(Icons.AutoMirrored.Filled.Send, "发送")
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { dismissKeyboard(); showActionSheet = true },
                enabled = !isGenerating && !isAddingAttachment) {
                Icon(Icons.Default.Add, "更多输入工具", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(Modifier.weight(1f)) { modelSelector?.invoke() }
            if (isImeOpen) {
                IconButton(onClick = ::dismissKeyboard) { Icon(Icons.Default.KeyboardHide, "收起键盘") }
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
                        headlineContent = { Text("添加图片") },
                        leadingContent = { Icon(Icons.Default.AttachFile, null) },
                        modifier = Modifier.clickable { showActionSheet = false; onAttachImageClick() },
                    )
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
        NarratorRequestDialog(
            guidance = narratorGuidance,
            onGuidanceChange = onNarratorGuidanceChange,
            onDismiss = {
                dismissKeyboard()
                showNarratorDialog = false
            },
            onGenerate = { guidance ->
                dismissKeyboard()
                showNarratorDialog = false
                onRequestNarrator(guidance)
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
