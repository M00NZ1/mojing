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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
    var inputFocused by remember { mutableStateOf(false) }
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
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            border = BorderStroke(1.dp, if (inputFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(4.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                IconButton(onClick = { dismissKeyboard(); showActionSheet = true },
                    enabled = !isGenerating && !isAddingAttachment) {
                    Icon(Icons.Default.Add, "更多输入工具", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp, max = 144.dp)
                        .focusRequester(inputFocusRequester)
                        .semantics { contentDescription = "消息输入" }
                        .onFocusChanged { focusState ->
                            inputFocused = focusState.isFocused
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
                    shape = RoundedCornerShape(12.dp),
                ) {
                    if (isGenerating) Icon(Icons.Default.Stop, "停止")
                    else Icon(Icons.AutoMirrored.Filled.Send, "发送")
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { modelSelector?.invoke() }
            if (isImeOpen) {
                IconButton(onClick = ::dismissKeyboard) { Icon(Icons.Default.KeyboardHide, "收起键盘") }
            }
        }
    }

    if (showActionSheet && !isGenerating && !isAddingAttachment) {
        ModalBottomSheet(onDismissRequest = { showActionSheet = false }, dragHandle = null,
            containerColor = MaterialTheme.colorScheme.surface,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("输入工具", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { showActionSheet = false }) { Icon(Icons.Default.Close, "关闭输入工具") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                    item {
                        ChatToolRow("语音输入", "转成文字", Icons.Default.Mic) {
                            showActionSheet = false
                            onVoiceClick()
                        }
                        ChatToolRow("表情", "插入到消息", Icons.Default.EmojiEmotions) {
                            showActionSheet = false
                            onOpenEmoji()
                        }
                        ChatToolRow("添加图片", "选择本机图片", Icons.Default.AttachFile) {
                            showActionSheet = false
                            onAttachImageClick()
                        }
                        ChatToolRow("试听朗读", if (value.text.isBlank()) "输入文字后可试听" else "朗读输入框内容",
                            Icons.AutoMirrored.Filled.VolumeUp, enabled = value.text.isNotBlank()) {
                            showActionSheet = false
                            onPreviewSpeak()
                        }
                    }
                    item {
                        Text("创作", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                        ChatToolRow("生成旁白", "场景与剧情", Icons.Default.TheaterComedy) {
                            showNarratorDialog = true
                            showActionSheet = false
                        }
                        ChatToolRow("生成配图", "描述画面", Icons.Default.Image) {
                            showActionSheet = false
                            onImageGenClick()
                        }
                        ChatToolRow("快捷词", "角色名、时间与场景变量", Icons.Default.DataObject,
                            trailingIcon = if (showMacros) Icons.Default.ExpandLess else Icons.Default.ExpandMore) {
                            showMacros = !showMacros
                        }
                    }
                    if (showMacros) {
                        items(ChatMacroDefinitions.ALL) { macro ->
                            ChatToolRow(macro.label, macro.description, Icons.Default.DataObject,
                                modifier = Modifier.padding(start = 20.dp), enabled = !isGenerating && !isAddingAttachment) {
                                onInsertMacro(macro.macro)
                                restoreInputFocus = true
                                showActionSheet = false
                            }
                        }
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
private fun ChatToolRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailingIcon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 64.dp).padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, null, Modifier.size(22.dp), tint = if (enabled) colors.onSurfaceVariant else colors.onSurface.copy(alpha = 0.38f))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall,
                    color = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            if (trailingIcon != null) Icon(trailingIcon, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
        }
        HorizontalDivider(Modifier.padding(start = 58.dp, end = 20.dp), color = colors.outlineVariant)
    }
}
