package com.mojing.app.ui.chat

import android.media.MediaPlayer
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mojing.app.data.local.entity.MessageAttachmentEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.domain.engine.StructuredParser
import com.mojing.app.ui.theme.AiBubble
import com.mojing.app.ui.common.avatarImageModel
import com.mojing.app.ui.common.ImagePreviewDialog
import com.mojing.app.ui.theme.NarratorBubble
import com.mojing.app.ui.theme.UserBubble

@Composable
internal fun MessageActionPanelContent(
    message: MessageEntity,
    isBookmarked: Boolean,
    canContinueReply: Boolean,
    canRegenerate: Boolean,
    isGenerating: Boolean,
    imageAttachmentCount: Int,
    isSavingImages: Boolean,
    onDismiss: () -> Unit,
    onAction: (MessageAction) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MessageQuickAction("复制", Icons.Default.ContentCopy, Modifier.weight(1f)) {
            onDismiss(); onAction(MessageAction.Copy(message))
        }
        MessageQuickAction("编辑", Icons.Default.Edit, Modifier.weight(1f), enabled = !isGenerating) {
            onDismiss(); onAction(MessageAction.Edit(message))
        }
        if (canRegenerate) {
            MessageQuickAction("重新生成", Icons.Default.Refresh, Modifier.weight(1f), enabled = !isGenerating) {
                onDismiss(); onAction(MessageAction.Regenerate(message))
            }
        }
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
    DropdownMenuItem(
        modifier = Modifier.fillMaxWidth(),
        text = { Text("引用回复") },
        enabled = !isGenerating,
        onClick = { onAction(MessageAction.Quote(message)); onDismiss() },
        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Reply, null) },
    )
    if (imageAttachmentCount > 0) {
        DropdownMenuItem(
            modifier = Modifier.fillMaxWidth(),
            text = {
                Text(
                    when {
                        isSavingImages -> "保存图片中…"
                        imageAttachmentCount > 1 -> "保存图片（$imageAttachmentCount 张）"
                        else -> "保存图片"
                    }
                )
            },
            enabled = !isSavingImages,
            onClick = { onAction(MessageAction.SaveImages(message)); onDismiss() },
            leadingIcon = { Icon(Icons.Default.Download, null) },
        )
    }
    DropdownMenuItem(
        modifier = Modifier.fillMaxWidth(),
        text = { Text(if (isBookmarked) "取消收藏" else "收藏消息") },
        onClick = { onAction(MessageAction.ToggleBookmark(message)); onDismiss() },
        leadingIcon = {
            Icon(
                if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                null,
            )
        },
    )
    DropdownMenuItem(
        modifier = Modifier.fillMaxWidth(),
        text = { Text("朗读本句") },
        onClick = { onAction(MessageAction.Speak(message)); onDismiss() },
        leadingIcon = { Icon(Icons.AutoMirrored.Filled.VolumeUp, null) },
    )
    HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    if (canContinueReply) {
        DropdownMenuItem(
            modifier = Modifier.fillMaxWidth(),
            text = { Text("继续生成回复") },
            enabled = !isGenerating,
            onClick = { onAction(MessageAction.ContinueReply(message)); onDismiss() },
            leadingIcon = { Icon(Icons.Default.PlayArrow, null) },
        )
    }
    DropdownMenuItem(
        modifier = Modifier.fillMaxWidth(),
        text = { Text("从此处分支") },
        enabled = !isGenerating,
        onClick = { onAction(MessageAction.CreateBranch(message)); onDismiss() },
        leadingIcon = { Icon(Icons.AutoMirrored.Filled.CallSplit, null) },
    )
    HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    DropdownMenuItem(
        modifier = Modifier.fillMaxWidth(),
        text = { Text("撤回", color = MaterialTheme.colorScheme.error) },
        enabled = !isGenerating,
        onClick = { onAction(MessageAction.Recall(message)); onDismiss() },
        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
    )
}

@Composable
private fun MessageQuickAction(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 80.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = color)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MessageBubble(
    message: MessageEntity,
    attachments: List<MessageAttachmentEntity> = emptyList(),
    avatarPath: String = "",
    avatarColor: String = "",
    cardImagePath: String = "",
    userAvatarImagePath: String = "",
    userAvatarColor: String = "",
    userDisplayName: String = "",
    isBookmarked: Boolean = false,
    canContinueReply: Boolean = false,
    canRegenerate: Boolean = false,
    isGenerating: Boolean = false,
    isSavingImages: Boolean = false,
    isCurrentChoiceMessage: Boolean = false,
    onAction: (MessageAction) -> Unit = {},
    /** 单条消息时把昵称/时间放进气泡列，避免与头像行错位（参考常见 IM 布局） */
    clusterInlineHeader: Boolean = false,
    senderLabel: String = "",
    showSenderHeader: Boolean = false,
    timeText: String = "",
) {
    var showMenu by remember(message.id) { mutableStateOf(false) }
    var previewImagePath by remember(message.id) { mutableStateOf<String?>(null) }
    val dismissMenu = { showMenu = false }
    val imageAttachmentCount = attachments.count { attachment ->
        attachment.storagePath.isNotBlank() &&
            (attachment.assetType.equals("image", ignoreCase = true) ||
                attachment.mimeType.startsWith("image/", ignoreCase = true))
    }
    if (showMenu) {
        ModalBottomSheet(onDismissRequest = dismissMenu) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                Text("消息操作", style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                if (isGenerating) {
                    Text("生成中，可复制、收藏、朗读或保存图片", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                }
                MessageActionPanelContent(
                    message, isBookmarked, canContinueReply, canRegenerate, isGenerating,
                    imageAttachmentCount, isSavingImages, dismissMenu, onAction,
                )
            }
        }
    }
    when (message.speakerType) {
        "user" -> UserMessageBubble(
            message,
            attachments,
            userAvatarImagePath,
            userAvatarColor,
            userDisplayName,
            clusterInlineHeader = clusterInlineHeader,
            senderLabel = senderLabel,
            showSenderHeader = showSenderHeader,
            timeText = timeText,
            onClick = { showMenu = true },
            onLongPress = { showMenu = true },
            onImageClick = { previewImagePath = it },
        )
        "character" -> CharacterMessageBubble(
            message,
            attachments,
            avatarPath,
            avatarColor,
            cardImagePath,
            showHistoricalChoices = !isCurrentChoiceMessage,
            clusterInlineHeader = clusterInlineHeader,
            senderLabel = senderLabel,
            showSenderHeader = showSenderHeader,
            timeText = timeText,
            onClick = { showMenu = true },
            onLongPress = { showMenu = true },
            onImageClick = { previewImagePath = it },
        )
        "narrator" -> Box {
            NarratorMessageBubble(
                message,
                showHistoricalChoices = !isCurrentChoiceMessage,
                modifier = Modifier.combinedClickable(onClick = { showMenu = true }, onLongClick = { showMenu = true }),
            )
        }
        else -> UserMessageBubble(
            message,
            attachments,
            userAvatarImagePath,
            userAvatarColor,
            userDisplayName,
            clusterInlineHeader = clusterInlineHeader,
            senderLabel = senderLabel,
            showSenderHeader = showSenderHeader,
            timeText = timeText,
            onClick = { showMenu = true },
            onLongPress = { showMenu = true },
            onImageClick = { previewImagePath = it },
        )
    }
    previewImagePath?.let { path ->
        ImagePreviewDialog(imageUrl = path, onDismiss = { previewImagePath = null })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserMessageBubble(
    message: MessageEntity,
    attachments: List<MessageAttachmentEntity> = emptyList(),
    userAvatarImagePath: String = "",
    userAvatarColor: String = "",
    userDisplayName: String = "",
    clusterInlineHeader: Boolean = false,
    senderLabel: String = "",
    showSenderHeader: Boolean = false,
    timeText: String = "",
    onClick: () -> Unit = {},
    onLongPress: () -> Unit = {},
    onImageClick: (String) -> Unit = {},
    menu: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val initial = remember(userDisplayName) {
        val t = userDisplayName.trim()
        if (t.isNotEmpty()) t.take(1) else "我"
    }
    val d = LocalChatDensityMetrics.current
    val labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
    val timeColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = d.rowHorizontal, vertical = d.rowVertical),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
        verticalAlignment = Alignment.Top,
    ) {
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.Top) {
            Box {
                Column(horizontalAlignment = Alignment.End) {
                    if (clusterInlineHeader) {
                        if (showSenderHeader) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                            ) {
                                Text(timeText, style = MaterialTheme.typography.labelSmall, color = timeColor)
                                Spacer(Modifier.width(8.dp))
                                Text(senderLabel, style = MaterialTheme.typography.labelLarge, color = labelColor)
                            }
                            Spacer(Modifier.height(4.dp))
                        } else {
                            Text(
                                timeText,
                                style = MaterialTheme.typography.labelSmall,
                                color = timeColor.copy(alpha = 0.45f),
                            )
                            Spacer(Modifier.height(2.dp))
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(d.bubbleCornerOuter, d.bubbleCornerOuter, d.bubbleCornerInner, d.bubbleCornerOuter),
                        color = UserBubble,
                        modifier = Modifier
                            .widthIn(max = d.bubbleMaxWidth)
                            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
                    ) {
                        Column(modifier = Modifier.padding(d.bubbleInnerPadding)) {
                            attachments.forEach { att ->
                                if (att.storagePath.isNotBlank()) {
                                    coil.compose.AsyncImage(
                                        model = avatarImageModel(context, att.storagePath),
                                        contentDescription = att.fileName.ifBlank { "图片" },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 220.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { onImageClick(att.storagePath) },
                                        contentScale = ContentScale.Fit,
                                    )
                                    Spacer(Modifier.height(8.dp))
                                }
                            }
                            val showCaption = message.content.isNotBlank() &&
                                (message.content != "[图片]" || attachments.isEmpty())
                            if (showCaption) {
                                Text(
                                    text = ChatMessageTextFormat.forBubbleDisplay(message.content),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = d.bodyTextStyle(),
                                )
                            }
                        }
                    }
                }
                menu()
            }
            Spacer(Modifier.width(8.dp))
            if (userAvatarImagePath.isNotEmpty()) {
                coil.compose.AsyncImage(
                    model = avatarImageModel(context, userAvatarImagePath),
                    contentDescription = "我的头像",
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    color = try {
                        androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(userAvatarColor))
                    } catch (_: Exception) {
                        MaterialTheme.colorScheme.primary
                    },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            initial,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CharacterAttachmentChips(
    attachments: List<MessageAttachmentEntity>,
    onImageClick: (String) -> Unit,
) {
    val context = LocalContext.current
    val playerRef = remember { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(Unit) {
        onDispose { playerRef.value?.release() }
    }
    attachments.forEach { att ->
        if (att.storagePath.isBlank()) return@forEach
        when {
            att.mimeType.startsWith("image/") -> {
                coil.compose.AsyncImage(
                    model = avatarImageModel(context, att.storagePath),
                    contentDescription = att.fileName.ifBlank { "图片" },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onImageClick(att.storagePath) },
                    contentScale = ContentScale.Fit
                )
                Spacer(Modifier.height(8.dp))
            }
            att.mimeType.startsWith("audio/") -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    IconButton(onClick = {
                        runCatching {
                            playerRef.value?.release()
                            playerRef.value = MediaPlayer().apply {
                                setDataSource(att.storagePath)
                                setOnCompletionListener { mp ->
                                    mp.release()
                                    if (playerRef.value === mp) playerRef.value = null
                                }
                                prepare()
                                start()
                            }
                        }
                    }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "播放语音")
                    }
                    Text("语音片段", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CharacterMessageBubble(
    message: MessageEntity,
    attachments: List<MessageAttachmentEntity> = emptyList(),
    avatarPath: String = "",
    avatarColor: String = "",
    cardImagePath: String = "",
    showHistoricalChoices: Boolean = true,
    clusterInlineHeader: Boolean = false,
    senderLabel: String = "",
    showSenderHeader: Boolean = false,
    timeText: String = "",
    onClick: () -> Unit = {},
    onLongPress: () -> Unit = {},
    onImageClick: (String) -> Unit = {},
    menu: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val d = LocalChatDensityMetrics.current
    val labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
    val timeColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    val avatarFallback = senderLabel.trim().firstOrNull()?.toString() ?: "角"
    var showCardPreview by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = d.rowHorizontal, vertical = d.rowVertical),
        verticalAlignment = Alignment.Top,
    ) {
        val avatarClickable = cardImagePath.isNotBlank()
        val avatarModifier = Modifier
            .size(40.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
        if (avatarClickable) {
            Box(
                modifier = avatarModifier.clickable { showCardPreview = true },
            ) {
                if (avatarPath.isNotEmpty()) {
                    coil.compose.AsyncImage(
                        model = avatarImageModel(context, avatarPath),
                        contentDescription = "角色头像",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                } else {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        shape = androidx.compose.foundation.shape.CircleShape,
                        color = try {
                            androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(avatarColor))
                        } catch (_: Exception) {
                            MaterialTheme.colorScheme.primary
                        },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(avatarFallback, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
            }
        } else if (avatarPath.isNotEmpty()) {
            coil.compose.AsyncImage(
                model = avatarImageModel(context, avatarPath),
                contentDescription = "角色头像",
                modifier = avatarModifier,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        } else {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = androidx.compose.foundation.shape.CircleShape,
                color = try {
                    androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(avatarColor))
                } catch (_: Exception) {
                    MaterialTheme.colorScheme.primary
                },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(avatarFallback, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = onClick, onLongClick = onLongPress),
            ) {
                if (clusterInlineHeader) {
                    if (showSenderHeader) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(senderLabel, style = MaterialTheme.typography.labelLarge, color = labelColor)
                            Spacer(Modifier.weight(1f))
                            Text(timeText, style = MaterialTheme.typography.labelSmall, color = timeColor)
                        }
                        Spacer(Modifier.height(4.dp))
                    } else {
                        Text(
                            timeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = timeColor.copy(alpha = 0.45f),
                        )
                        Spacer(Modifier.height(2.dp))
                    }
                }
                if (attachments.isNotEmpty()) {
                    CharacterAttachmentChips(attachments, onImageClick)
                }
                val reply = StructuredParser.parse(message.content)
                val structuredRenderable = StructuredParser.isStructured(message.content) &&
                    (reply.narrations.isNotEmpty() || reply.thoughts.isNotEmpty() ||
                        reply.speeches.isNotEmpty() || reply.choices.isNotEmpty())
                if (structuredRenderable) {
                    reply.narrations.forEach { narration ->
                        if (narration.isBlank()) return@forEach
                        val body = ChatMessageTextFormat.forBubbleDisplay(narration)
                        if (body.isBlank()) return@forEach
                        Surface(
                            shape = RoundedCornerShape(d.bubbleCornerOuter),
                            color = NarratorBubble,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = "🎭 $body",
                                modifier = Modifier.padding(d.bubbleInnerPadding),
                                style = d.bodyMediumItalicStyle(),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                            )
                        }
                        Spacer(modifier = Modifier.size(4.dp))
                    }
                    reply.thoughts.forEach { thought ->
                        if (thought.isBlank()) return@forEach
                        val body = ChatMessageTextFormat.forBubbleDisplay(thought)
                        if (body.isBlank()) return@forEach
                        Text(
                            text = "💭 $body",
                            modifier = Modifier.padding(horizontal = d.bubbleInnerPadding, vertical = 2.dp),
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = (d.bodyFontSp - 1f).coerceAtLeast(12f).sp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                    }
                    reply.speeches.forEach { speech ->
                        if (speech.text.isBlank()) return@forEach
                        val body = ChatMessageTextFormat.forBubbleDisplay(speech.text)
                        if (body.isBlank()) return@forEach
                        Row(modifier = Modifier.padding(top = 4.dp)) {
                            Surface(
                                shape = RoundedCornerShape(d.bubbleCornerInner, d.bubbleCornerOuter, d.bubbleCornerOuter, d.bubbleCornerOuter),
                                color = AiBubble,
                                modifier = Modifier.widthIn(max = d.bubbleMaxWidth),
                            ) {
                                Text(
                                    text = body,
                                    modifier = Modifier.padding(d.bubbleInnerPadding),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = d.bodyTextStyle(),
                                )
                            }
                        }
                    }
                    val plainBody = ChatMessageTextFormat.forBubbleDisplay(reply.plainText)
                    if (plainBody.isNotBlank()) {
                        Row(modifier = Modifier.padding(top = 4.dp)) {
                            Surface(
                                shape = RoundedCornerShape(
                                    d.bubbleCornerInner,
                                    d.bubbleCornerOuter,
                                    d.bubbleCornerOuter,
                                    d.bubbleCornerOuter,
                                ),
                                color = AiBubble,
                                modifier = Modifier.widthIn(max = d.bubbleMaxWidth),
                            ) {
                                Text(
                                    text = plainBody,
                                    modifier = Modifier.padding(d.bubbleInnerPadding),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = d.bodyTextStyle(),
                                )
                            }
                        }
                    }
                    if (showHistoricalChoices) reply.choices.forEach { choice ->
                        if (choice.isBlank()) return@forEach
                        val body = ChatMessageTextFormat.forBubbleDisplay(choice)
                        if (body.isBlank()) return@forEach
                        Surface(
                            shape = RoundedCornerShape(d.bubbleCornerOuter),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                        ) {
                            Text(
                                text = "▸ $body",
                                modifier = Modifier.padding(d.bubbleInnerPadding),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = (d.bodyFontSp - 1f).coerceAtLeast(12f).sp),
                            )
                        }
                    }
                } else {
                    val rawPlain = if (StructuredParser.isStructured(message.content)) {
                        StructuredParser.stripTags(message.content)
                    } else {
                        message.content
                    }
                    val plain = ChatMessageTextFormat.forBubbleDisplay(rawPlain)
                    if (plain.isNotBlank()) {
                        Row {
                            Surface(
                                shape = RoundedCornerShape(d.bubbleCornerInner, d.bubbleCornerOuter, d.bubbleCornerOuter, d.bubbleCornerOuter),
                                color = AiBubble,
                                modifier = Modifier.widthIn(max = d.bubbleMaxWidth),
                            ) {
                                Text(
                                    text = plain,
                                    modifier = Modifier.padding(d.bubbleInnerPadding),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = d.bodyTextStyle(),
                                )
                            }
                        }
                    }
                }
            }
            menu()
        }
    }
    if (showCardPreview && cardImagePath.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { showCardPreview = false },
            title = { Text("竖版封面") },
            text = {
                coil.compose.AsyncImage(
                    model = avatarImageModel(context, cardImagePath),
                    contentDescription = "竖版封面",
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Fit,
                )
            },
            confirmButton = {
                TextButton(onClick = { showCardPreview = false }) { Text("关闭") }
            },
        )
    }
}

@Composable
fun NarratorMessageBubble(
    message: MessageEntity,
    showHistoricalChoices: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val d = LocalChatDensityMetrics.current
    val reply = StructuredParser.parse(message.content)
    val rawBody = if (StructuredParser.isStructured(message.content)) {
        StructuredParser.stripTags(message.content)
    } else {
        message.content
    }
    val body = ChatMessageTextFormat.forBubbleDisplay(rawBody)
    Surface(
        shape = RoundedCornerShape(d.bubbleCornerOuter),
        color = NarratorBubble,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = d.narratorHorizontal, vertical = d.narratorVertical)
    ) {
        Column(modifier = Modifier.padding(d.narratorInnerPadding)) {
            if (body.isNotBlank()) {
                Text(
                    text = "🎭 $body",
                    style = d.bodyMediumItalicStyle(),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                )
            }
            if (showHistoricalChoices) reply.choices.forEach { choice ->
                val label = ChatMessageTextFormat.forBubbleDisplay(choice)
                if (label.isBlank()) return@forEach
                Surface(
                    shape = RoundedCornerShape(d.bubbleCornerOuter),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                ) {
                    Text(
                        text = "▸ $label",
                        modifier = Modifier.padding(d.bubbleInnerPadding),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
