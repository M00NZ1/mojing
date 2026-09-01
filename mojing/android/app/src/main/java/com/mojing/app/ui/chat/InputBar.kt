package com.mojing.app.ui.chat

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
                    "已选 $pendingAttachmentCount 张图片，发送时一并发出",
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
        OutlinedTextField(
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
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
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
                            onOpenEmoji()
                        }, modifier = Modifier.size(48.dp)) {
                            Icon(
                                Icons.Default.EmojiEmotions,
                                "表情",
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
                    IconButton(
                        onClick = onPreviewSpeak,
                        enabled = value.text.isNotBlank(),
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.VolumeUp,
                            "试听朗读",
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                alpha = if (value.text.isNotBlank()) 0.85f else 0.3f,
                            ),
                        )
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
                            Text(generationModeLabel?.let { "停止$it" } ?: "停止")
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

    if (showActionSheet) {
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
                    ListItem(
                        headlineContent = { Text("语音输入") },
                        supportingContent = { Text("识别结果会填入输入框，由你确认后发送") },
                        leadingContent = { Icon(Icons.Default.Mic, contentDescription = null) },
                        modifier = Modifier.clickable(enabled = !isGenerating && !isAddingAttachment) {
                            onVoiceClick()
                            showActionSheet = false
                        },
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text("生成配图") },
                        supportingContent = { Text("描述画面并生成一张图片，随消息发出") },
                        leadingContent = { Icon(Icons.Default.Image, contentDescription = null) },
                        modifier = Modifier.clickable(enabled = !isGenerating && !isAddingAttachment) {
                            onImageGenClick()
                            showActionSheet = false
                        },
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text("添加图片") },
                        supportingContent = { Text("从相册选择一张图片，发送时作为附件带上") },
                        leadingContent = { Icon(Icons.Default.AttachFile, contentDescription = null) },
                        modifier = Modifier.clickable(enabled = !isGenerating && !isAddingAttachment) {
                            onAttachImageClick()
                            showActionSheet = false
                        },
                    )
                }
                item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
                item {
                    ListItem(
                        headlineContent = { Text("生成旁白") },
                        supportingContent = { Text("根据当前世界与剧情描写场景、气氛或后续发展") },
                        leadingContent = { Icon(Icons.Default.TheaterComedy, contentDescription = null) },
                        modifier = Modifier.clickable(enabled = !isGenerating && !isAddingAttachment) {
                            showNarratorDialog = true
                            showActionSheet = false
                        },
                    )
                }
                item {
                    Text(
                        "快捷词（点一下插入到光标处）",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
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
