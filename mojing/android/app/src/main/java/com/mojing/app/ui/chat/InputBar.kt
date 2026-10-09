package com.mojing.app.ui.chat

import androidx.compose.ui.text.style.TextOverflow

import com.mojing.app.ui.common.MoJingIcon as Icon
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.KeyboardHide
import androidx.compose.material.icons.outlined.TheaterComedy
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.ExpandLess
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextAlign
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
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            modifier = Modifier.weight(1f),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            border = BorderStroke(1.dp, if (inputFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(4.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp, max = 128.dp)
                        .focusRequester(inputFocusRequester)
                        .semantics { contentDescription = "消息输入" }
                        .onFocusChanged { focusState ->
                            inputFocused = focusState.isFocused
                            if (focusState.isFocused) keyboard?.show()
                        },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { innerTextField ->
                        Box(Modifier.padding(vertical = 8.dp, horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
                            if (value.text.isEmpty()) Text("写下你的回应…",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            innerTextField()
                        }
                    },
                    minLines = 1,
                    maxLines = 5,
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
            }
        }
                FilledIconButton(
                    onClick = if (isGenerating) onStop else onSend,
                    enabled = isGenerating || (!isAddingAttachment && (value.text.isNotBlank() || pendingAttachmentCount > 0)),
                    modifier = Modifier.size(48.dp),
                    shape = androidx.compose.foundation.shape.CircleShape,
                ) {
                    if (isGenerating) Icon(Icons.Outlined.Stop, "停止")
                    else Icon(Icons.AutoMirrored.Outlined.Send, "发送")
                }
            }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { dismissKeyboard(); onAttachImageClick() },
                modifier = Modifier.size(48.dp),
                enabled = !isGenerating && !isAddingAttachment) {
                Icon(Icons.Outlined.Image, "添加图片", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(Modifier.weight(1f)) { modelSelector?.invoke() }
            IconButton(onClick = {
                // Clearing focus collapses a selected range. Keep the insertion
                // target for tools while committing any active IME composition.
                val toolInput = value.copy(composition = null)
                dismissKeyboard()
                if (!toolInput.selection.collapsed) onValueChange(toolInput)
                showActionSheet = true
            },
                modifier = Modifier.size(48.dp),
                enabled = !isGenerating && !isAddingAttachment) {
                Icon(Icons.Outlined.Add, "更多输入工具", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (isImeOpen) {
                IconButton(onClick = ::dismissKeyboard, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.KeyboardHide, "收起键盘")
                }
            }
        }
    }

    if (showActionSheet && !isGenerating && !isAddingAttachment) {
        ModalBottomSheet(
            scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.42f),
            onDismissRequest = { showActionSheet = false }, dragHandle = null,
            containerColor = MaterialTheme.colorScheme.surface,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.75f)) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("输入工具", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { showActionSheet = false }) { Icon(Icons.Outlined.Close, "关闭输入工具") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp)) {
                    item {
                        val tools = listOf(
                            InputTool("语音输入", Icons.Outlined.Mic) {
                                showActionSheet = false
                                onVoiceClick()
                            },
                            InputTool("表情", Icons.Outlined.EmojiEmotions) {
                                showActionSheet = false
                                onOpenEmoji()
                            },
                            InputTool("添加图片", Icons.Outlined.Image) {
                                showActionSheet = false
                                onAttachImageClick()
                            },
                            InputTool("试听朗读", Icons.AutoMirrored.Outlined.VolumeUp,
                                enabled = value.text.isNotBlank()) {
                                showActionSheet = false
                                onPreviewSpeak()
                            },
                            InputTool("生成旁白", Icons.Outlined.TheaterComedy) {
                                showNarratorDialog = true
                                showActionSheet = false
                            },
                            InputTool("生成配图", Icons.Outlined.Image) {
                                showActionSheet = false
                                onImageGenClick()
                            },
                        )
                        BoxWithConstraints(Modifier.fillMaxWidth().padding(16.dp)) {
                            val columns = if (maxWidth / LocalDensity.current.fontScale < 300.dp) 2 else 3
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                tools.chunked(columns).forEach { row ->
                                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        row.forEach { tool ->
                                            InputToolTile(tool, Modifier.weight(1f).fillMaxHeight())
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item {
                        ChatToolRow("快捷词", "角色、时间、场景", Icons.Outlined.DataObject,
                            trailingIcon = if (showMacros) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore) {
                            showMacros = !showMacros
                        }
                    }
                    if (showMacros) {
                        items(ChatMacroDefinitions.ALL) { macro ->
                            ChatToolRow(macro.label, macro.description, Icons.Outlined.DataObject,
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

private data class InputTool(
    val title: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

@Composable
private fun InputToolTile(tool: InputTool, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = tool.onClick, enabled = tool.enabled,
        shape = MaterialTheme.shapes.small, color = colors.surfaceContainerLow,
        modifier = modifier) {
        Column(Modifier.heightIn(min = 80.dp).padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
            Icon(tool.icon, null, Modifier.size(24.dp),
                tint = if (tool.enabled) colors.primary else colors.onSurface.copy(alpha = 0.38f))
            Text(tool.title, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
                color = if (tool.enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f))
            if (!tool.enabled) Text("先输入文字", style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
        }
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
