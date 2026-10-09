package com.mojing.app.ui.chat

import androidx.compose.material.icons.outlined.Person

import com.mojing.app.ui.common.MoJingIcon as Icon
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.mojing.app.data.local.entity.MessageAttachmentEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.AutoImageMetadata
import com.mojing.app.ui.common.avatarImageModel
import com.mojing.app.ui.common.ImagePreviewDialog
import com.mojing.app.domain.billing.CurrencyDisplayState
import com.mojing.app.domain.billing.formatBillingAmount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle

internal val LocalBillingCurrencyState = staticCompositionLocalOf { CurrencyDisplayState() }
internal val LocalReplyUsageLookup = staticCompositionLocalOf<(Long) -> kotlinx.coroutines.flow.Flow<com.mojing.app.data.local.entity.CostRecordEntity?>> {
    { kotlinx.coroutines.flow.flowOf(null) }
}

@Composable
internal fun ReplyUsageCaption(json: String, sessionId: Long, fallbackText: String = "") {
    val recordedDuration = remember(json) { ReplyGenerationMetadata.durationLabel(json) }
    val savedUsage = remember(json) { ReplyGenerationMetadata.usage(json) }
    if (recordedDuration == null && savedUsage == null) return
    val duration = recordedDuration ?: "时长未记录"
    val recordId = remember(json) { runCatching { savedUsage?.get("record_id")?.asLong }.getOrNull() }
    val lookup = LocalReplyUsageLookup.current
    val recordFlow = remember(recordId, lookup) {
        if (recordId != null && recordId > 0) lookup(recordId)
        else kotlinx.coroutines.flow.flowOf(null)
    }
    val liveRecord by recordFlow.collectAsStateWithLifecycle(initialValue = null)
    val usage = remember(savedUsage, liveRecord, sessionId) {
        mergeReplyUsage(savedUsage, liveRecord, sessionId)
    }
    val fallbackTokens = if (usage == null) remember(fallbackText) {
        com.mojing.app.domain.engine.TokenCounter.estimateScaledPrefix(fallbackText)
    } else 0
    val currencyState = LocalBillingCurrencyState.current
    val caption = runCatching {
        if (usage == null) "$duration · 正文约 $fallbackTokens Token · 费用未记录" else {
            val tokens = usage.get("total_tokens").asInt
            val estimated = usage.get("token_source")?.asString == "estimated"
            val cost = if (usage.get("cost_known").asBoolean)
                "≈" + formatBillingAmount(usage.get("cost").asDouble, usage.get("currency").asString, currencyState)
            else "价格待配置"
            "$duration · ${if (estimated) "约 " else ""}${String.format(java.util.Locale.US, "%,d", tokens)} Token · $cost"
        }
    }.getOrDefault(duration)
    Text(caption, modifier = Modifier.fillMaxWidth().padding(horizontal = LocalChatDensityMetrics.current.rowHorizontal, vertical = 6.dp)
        .semantics { contentDescription = "本条回复生成用量" },
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

}

private sealed interface BubbleTextLoad {
    data object Loading : BubbleTextLoad
    data object Failed : BubbleTextLoad
    data class Ready(val text: PreparedBubbleText) : BubbleTextLoad
}

@Composable
private fun rememberBubbleText(raw: String, speakerType: String): Pair<BubbleTextLoad, () -> Unit> {
    val prepare: () -> PreparedBubbleText = {
        when (speakerType) {
            "user" -> prepareUserBubbleText(raw)
            "narrator" -> prepareBubbleText(raw, narrator = true)
            else -> prepareBubbleText(raw, narrator = false)
        }
    }
    if (raw.length <= ChatMessageTextFormat.ASYNC_BODY_CHAR_THRESHOLD) {
        return BubbleTextLoad.Ready(remember(raw, speakerType) { prepare() }) to {}
    }
    val load = remember(raw, speakerType) { mutableStateOf<BubbleTextLoad>(BubbleTextLoad.Loading) }
    var attempt by remember(raw, speakerType) { mutableIntStateOf(0) }
    LaunchedEffect(raw, speakerType, attempt) {
        load.value = BubbleTextLoad.Loading
        try {
            load.value = BubbleTextLoad.Ready(withContext(Dispatchers.Default) {
                prepare()
            })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            load.value = BubbleTextLoad.Failed
        }
    }
    return load.value to { attempt++ }
}

@Composable
private fun BubbleTextPreparationNotice(load: BubbleTextLoad, onRetry: () -> Unit) {
    when (load) {
        BubbleTextLoad.Loading -> Text(
            "正在准备长消息…",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        BubbleTextLoad.Failed -> TextButton(onClick = onRetry) { Text("正文加载失败，重试") }
        is BubbleTextLoad.Ready -> Unit
    }
}

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
    onSelectText: (() -> Unit)? = null,
    isContextExcluded: Boolean = false,
    canToggleContext: Boolean = true,
    showContextAction: Boolean = true,
) {
    if (isGenerating) Text("回复生成中，编辑与生成操作暂不可用",
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MessageQuickAction("复制", Icons.Outlined.ContentCopy, Modifier.weight(1f)) { onDismiss(); onAction(MessageAction.Copy(message)) }
            MessageQuickAction("编辑", Icons.Outlined.Edit, Modifier.weight(1f), !isGenerating) { onDismiss(); onAction(MessageAction.Edit(message)) }
            MessageQuickAction("重新生成", Icons.Outlined.Refresh, Modifier.weight(1f), canRegenerate && !isGenerating) { onDismiss(); onAction(MessageAction.Regenerate(message)) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MessageQuickAction("引用", Icons.AutoMirrored.Outlined.Reply, Modifier.weight(1f)) { onAction(MessageAction.Quote(message)); onDismiss() }
            MessageQuickAction(if (isBookmarked) "已收藏" else "收藏", Icons.Outlined.BookmarkBorder, Modifier.weight(1f)) { onAction(MessageAction.ToggleBookmark(message)); onDismiss() }
            MessageQuickAction("朗读", Icons.AutoMirrored.Outlined.VolumeUp, Modifier.weight(1f)) { onAction(MessageAction.Speak(message)); onDismiss() }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MessageQuickAction("续写", Icons.Outlined.PlayArrow, Modifier.weight(1f), canContinueReply && !isGenerating) { onAction(MessageAction.ContinueReply(message)); onDismiss() }
            MessageQuickAction("分叉故事线", Icons.AutoMirrored.Outlined.CallSplit, Modifier.weight(1f), !isGenerating) { onAction(MessageAction.CreateBranch(message)); onDismiss() }
            MessageQuickAction("撤回", Icons.Outlined.Delete, Modifier.weight(1f), !isGenerating, danger = true) { onAction(MessageAction.Recall(message)); onDismiss() }
        }
    }
    var showAdditional by remember(message.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("长按消息可选择正文", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { showAdditional = !showAdditional }) { Text(if (showAdditional) "收起" else "更多操作") }
    }
    androidx.compose.animation.AnimatedVisibility(showAdditional) {
        Column {
    if (!canContinueReply || !canRegenerate) Text("续写与重新生成需满足当前故事线的回复条件",
        Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (onSelectText != null) MessageActionRow(text = { Text("选择文字") },
        supportingText = "长按消息也可自由选择正文", leadingIcon = { Icon(Icons.Outlined.TextFields, null) },
        onClick = { onDismiss(); onSelectText() })
    if (imageAttachmentCount > 0) MessageActionRow(
        text = { Text(if (isSavingImages) "保存图片中…" else "保存图片（$imageAttachmentCount 张）") },
        enabled = !isSavingImages, leadingIcon = { Icon(Icons.Outlined.Download, null) },
        onClick = { onAction(MessageAction.SaveImages(message)); onDismiss() })
    if (showContextAction) MessageActionRow(
        text = { Text(if (isContextExcluded) "恢复到模型上下文" else "从模型上下文排除") },
        supportingText = if (canToggleContext) "只影响当前故事线的后续回复与自动记忆，原文保留" else "请先切换到采用版本；附属消息不参与上下文",
        enabled = !isGenerating && canToggleContext,
        leadingIcon = { Icon(if (isContextExcluded) Icons.Outlined.AddCircleOutline else Icons.Outlined.RemoveCircleOutline, null) },
        onClick = { onAction(MessageAction.SetContextExcluded(message, !isContextExcluded)); onDismiss() })
        }
    }

}

@Composable
private fun MessageActionRow(
    modifier: Modifier = Modifier,
    text: @Composable () -> Unit,
    leadingIcon: @Composable () -> Unit,
    enabled: Boolean = true,
    supportingText: String? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick, enabled = enabled,
        modifier = modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        shape = MaterialTheme.shapes.small, color = Color.Transparent,
    ) {
        Row(Modifier.heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 10.dp)
            .alpha(if (enabled) 1f else 0.38f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { leadingIcon() }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.bodyLarge) { text() }
                supportingText?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun MessageQuickAction(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 64.dp),
        shape = MaterialTheme.shapes.small,
        color = Color.Transparent,
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val color = if (enabled && danger) MaterialTheme.colorScheme.error else if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = color, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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
    isContextExcluded: Boolean = false,
    canToggleContext: Boolean = true,
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
    readOnly: Boolean = false,
    canRetryAutoImage: Boolean = true,
) {
    var showSelection by remember(message.id) { mutableStateOf(false) }
    var selectedFullText by remember(message.id, message.content, message.speakerType) { mutableStateOf<String?>(null) }
    var selectionError by remember(message.id, message.content) { mutableStateOf(false) }
    var selectionAttempt by remember(message.id, message.content) { mutableIntStateOf(0) }
    val closeSelection = {
        showSelection = false
        selectedFullText = null
        selectionError = false
    }
    LaunchedEffect(showSelection, selectionAttempt, message.content, message.speakerType) {
        if (!showSelection || message.content.length <= ChatMessageTextFormat.ASYNC_BODY_CHAR_THRESHOLD ||
            selectedFullText != null) return@LaunchedEffect
        selectionError = false
        try {
            selectedFullText = withContext(Dispatchers.Default) {
                ChatMessageTextFormat.forClipboard(message.content, message.speakerType)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            selectionError = true
        }
    }
    if (showSelection) {
        val fullText = if (message.content.length <= ChatMessageTextFormat.ASYNC_BODY_CHAR_THRESHOLD) {
            remember(message.content, message.speakerType) {
                ChatMessageTextFormat.forClipboard(message.content, message.speakerType)
            }
        } else selectedFullText
        if (fullText != null) {
            ChatTextSelectionDialog(fullText, onDismiss = closeSelection)
        } else {
            AlertDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                onDismissRequest = closeSelection,
                title = { Text(if (selectionError) "正文准备失败" else "正在准备正文") },
                text = { Text(if (selectionError) "请重试，消息原文仍保留。" else "长消息正在准备，请稍候") },
                confirmButton = {
                    TextButton(onClick = {
                        if (selectionError) selectionAttempt++ else closeSelection()
                    }) { Text(if (selectionError) "重试" else "取消") }
                },
                dismissButton = {
                    if (selectionError) TextButton(onClick = closeSelection) { Text("关闭") }
                },
            )
        }
    }
    var showMenu by remember(message.id) { mutableStateOf(false) }
    var previewImagePath by remember(message.id) { mutableStateOf<String?>(null) }
    val dismissMenu = { showMenu = false }
    val imageAttachmentCount = attachments.count { attachment ->
        attachment.storagePath.isNotBlank() &&
            (attachment.assetType.equals("image", ignoreCase = true) ||
                attachment.mimeType.startsWith("image/", ignoreCase = true))
    }
    if (showMenu) {
        ModalBottomSheet(
            scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.42f),
            onDismissRequest = dismissMenu, dragHandle = null,
            containerColor = Color.Transparent, shape = androidx.compose.ui.graphics.RectangleShape,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(Modifier.fillMaxWidth().heightIn(max = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * 0.88f)
                .verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.End) {
                val preview = remember(message.content, message.speakerType) {
                    ChatMessageTextFormat.actionPreview(message.content, message.speakerType)
                }
                Surface(Modifier.padding(horizontal = 20.dp, vertical = 12.dp).widthIn(max = 340.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = if (message.speakerType == "user") MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(preview, style = MaterialTheme.typography.bodyLarge,
                            maxLines = 4, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics { contentDescription = "所选消息" })
                        Text(timeText, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
                    color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(top = 8.dp, bottom = 12.dp)) {
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("消息操作", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            IconButton(onClick = dismissMenu) { Icon(Icons.Outlined.Close, "关闭消息操作") }
                        }
                        MessageActionPanelContent(
                            message, isBookmarked, canContinueReply, canRegenerate, isGenerating,
                            imageAttachmentCount, isSavingImages, dismissMenu, onAction,
                            onSelectText = { showSelection = true },
                            isContextExcluded = isContextExcluded,
                            canToggleContext = canToggleContext && !readOnly,
                            showContextAction = !readOnly,
                        )
                    }
                }
            }
        }
    }

    Column {
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
            onClick = { if (readOnly) showSelection = true else showMenu = true },
            onLongPress = { showSelection = true },
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
            onClick = { if (readOnly) showSelection = true else showMenu = true },
            onLongPress = { showSelection = true },
            onImageClick = { previewImagePath = it },
        )
        "narrator" -> Box {
            NarratorMessageBubble(
                message,
                showHistoricalChoices = !isCurrentChoiceMessage,
                senderLabel = senderLabel, timeText = timeText,
                showHeader = clusterInlineHeader,
                modifier = Modifier.combinedClickable(onClick = { if (readOnly) showSelection = true else showMenu = true }, onLongClick = { showSelection = true }),
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
            onClick = { if (readOnly) showSelection = true else showMenu = true },
            onLongPress = { showSelection = true },
            onImageClick = { previewImagePath = it },
        )
    }
    if (message.speakerType != "user" && !com.mojing.app.domain.story.NovelChapter.incomplete(message.structuredContentJson))
        ReplyUsageCaption(message.structuredContentJson, message.sessionId, message.content)
    AutoImageMetadata.parse(message.structuredContentJson)?.takeIf {
        canRetryAutoImage && !readOnly && it.retryable &&
            message.speakerType == "character" && message.characterId != null &&
            message.parentMessageId != null && !message.includeInContext &&
            attachments.isEmpty()
    }?.let {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            TextButton(
                onClick = { onAction(MessageAction.RetryAutoImage(message)) },
                enabled = !isGenerating,
                modifier = Modifier.semantics { contentDescription = "重试配图" },
            ) {
                Text("重试配图")
            }
        }
    }
    VoiceAttachmentControls(message, attachments, canRetryAutoImage && !readOnly, isGenerating, onAction, canPlay = !readOnly)
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
    val d = LocalChatDensityMetrics.current
    Box(Modifier.fillMaxWidth().padding(horizontal = d.rowHorizontal, vertical = d.rowVertical),
        contentAlignment = Alignment.TopEnd) {
        Surface(
            shape = RoundedCornerShape(16.dp, 16.dp, 6.dp, 16.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.widthIn(max = minOf(d.bubbleMaxWidth, 320.dp))
                .combinedClickable(onClick = onClick, onLongClick = onLongPress),
        ) {
            Column(Modifier.padding(d.bubbleInnerPadding)) {
                if (clusterInlineHeader) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (showSenderHeader) Text(senderLabel.ifBlank { "你" },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(timeText, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(4.dp))
                }
                            attachments.forEach { att ->
                                if (att.storagePath.isNotBlank()) {
                                    MessageAttachmentImage(att, onImageClick)
                                    Spacer(Modifier.height(8.dp))
                                }
                            }
                            val showCaption = message.content.isNotBlank() &&
                                (message.content != "[图片]" || attachments.isEmpty())
                            if (showCaption) {
                                val (textLoad, retryText) = rememberBubbleText(message.content, speakerType = "user")
                                if (textLoad is BubbleTextLoad.Ready) {
                                    textLoad.text.quote?.let { source ->
                                        Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                            shape = RoundedCornerShape(8.dp)) {
                                            SearchableMessageText("引用 · $source", modifier = Modifier.padding(10.dp),
                                                color = MaterialTheme.colorScheme.onSurface,
                                                style = MaterialTheme.typography.bodySmall,
                                                maxLines = if (LocalMessageSearchHighlight.current.query.isBlank()) 3 else Int.MAX_VALUE)
                                        }
                                        Spacer(Modifier.height(8.dp))
                                    }
                                    SearchableMessageText(
                                        text = textLoad.text.fallbackBody,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        style = d.bodyTextStyle(),
                                    )
                                } else {
                                    BubbleTextPreparationNotice(textLoad, retryText)
                                }
                            }
            }
        }
        menu()
    }
}

@Composable
internal fun CharacterAttachmentChips(
    attachments: List<MessageAttachmentEntity>,
    onImageClick: (String) -> Unit,
) {
    val context = LocalContext.current
    attachments.forEach { att ->
        if (att.storagePath.isBlank()) return@forEach
        when {
            att.mimeType.startsWith("image/") -> {
                MessageAttachmentImage(att, onImageClick)
                Spacer(Modifier.height(8.dp))
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
    val timeColor = MaterialTheme.colorScheme.onSurfaceVariant
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
                            Text(senderLabel, modifier = Modifier.weight(1f, fill = false),
                                style = MaterialTheme.typography.labelLarge, color = labelColor,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.width(8.dp))
                            Text(timeText, style = MaterialTheme.typography.labelSmall,
                                color = timeColor, maxLines = 1)
                        }
                        Spacer(Modifier.height(4.dp))
                    } else {
                        Text(
                            timeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = timeColor,
                        )
                        Spacer(Modifier.height(2.dp))
                    }
                }
                if (attachments.isNotEmpty()) {
                    CharacterAttachmentChips(attachments, onImageClick)
                }
                val (textLoad, retryText) = rememberBubbleText(message.content, speakerType = "character")
                if (textLoad is BubbleTextLoad.Ready) {
                val prepared = textLoad.text
                if (prepared.structuredRenderable) {
                    prepared.narrations.forEach { body ->
                        if (body.isBlank()) return@forEach
                        Surface(
                            shape = RoundedCornerShape(d.bubbleCornerOuter),
                            color = androidx.compose.ui.graphics.Color.Transparent,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            SearchableMessageText(
                    text = body,
                                modifier = Modifier.padding(vertical = 4.dp),
                                style = d.narrationTextStyle(),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                            )
                        }
                        Spacer(modifier = Modifier.size(4.dp))
                    }
                    prepared.thoughts.forEach { body ->
                        if (body.isBlank()) return@forEach
                        SearchableMessageText(
                    text = "内心 · $body",
                            modifier = Modifier.padding(vertical = 2.dp),
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = (d.bodyFontSp - 1f).coerceAtLeast(12f).sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    prepared.speeches.forEach { body ->
                        if (body.isBlank()) return@forEach
                        Row(modifier = Modifier.padding(top = 4.dp)) {
                            Surface(
                                shape = RoundedCornerShape(d.bubbleCornerInner, d.bubbleCornerOuter, d.bubbleCornerOuter, d.bubbleCornerOuter),
                                color = androidx.compose.ui.graphics.Color.Transparent,
                                modifier = Modifier.widthIn(max = d.bubbleMaxWidth),
                            ) {
                                SearchableMessageText(
                    text = body,
                                    modifier = Modifier.padding(vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = d.bodyTextStyle(),
                                )
                            }
                        }
                    }
                    val plainBody = prepared.plainBody
                    if (plainBody.isNotBlank()) {
                        Row(modifier = Modifier.padding(top = 4.dp)) {
                            Surface(
                                shape = RoundedCornerShape(
                                    d.bubbleCornerInner,
                                    d.bubbleCornerOuter,
                                    d.bubbleCornerOuter,
                                    d.bubbleCornerOuter,
                                ),
                                color = androidx.compose.ui.graphics.Color.Transparent,
                                modifier = Modifier.widthIn(max = d.bubbleMaxWidth),
                            ) {
                                SearchableMessageText(
                    text = plainBody,
                                    modifier = Modifier.padding(vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = d.bodyTextStyle(),
                                )
                            }
                        }
                    }
                    if (showHistoricalChoices) prepared.choices.forEach { body ->
                        if (body.isBlank()) return@forEach
                        Surface(
                            shape = RoundedCornerShape(d.bubbleCornerOuter),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                        ) {
                            SearchableMessageText(
                    text = "▸ $body",
                                modifier = Modifier.padding(vertical = 4.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = (d.bodyFontSp - 1f).coerceAtLeast(12f).sp),
                            )
                        }
                    }
                } else {
                    val plain = prepared.fallbackBody
                    if (plain.isNotBlank()) {
                        Row {
                            Surface(
                                shape = RoundedCornerShape(d.bubbleCornerInner, d.bubbleCornerOuter, d.bubbleCornerOuter, d.bubbleCornerOuter),
                                color = androidx.compose.ui.graphics.Color.Transparent,
                                modifier = Modifier.widthIn(max = d.bubbleMaxWidth),
                            ) {
                                SearchableMessageText(
                    text = plain,
                                    modifier = Modifier.padding(vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = d.bodyTextStyle(),
                                )
                            }
                        }
                    }
                }
                } else {
                    BubbleTextPreparationNotice(textLoad, retryText)
                }
            }
            menu()
        }
    }
    if (showCardPreview && cardImagePath.isNotBlank()) {
        AlertDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
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
    senderLabel: String = "叙述者",
    timeText: String = "",
    showHeader: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val d = LocalChatDensityMetrics.current
    val (textLoad, retryText) = rememberBubbleText(message.content, speakerType = "narrator")
    Row(modifier.fillMaxWidth().padding(horizontal = d.rowHorizontal, vertical = d.rowVertical),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        if (showHeader) Surface(Modifier.size(40.dp), shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Box(contentAlignment = Alignment.Center) {
                Icon(androidx.compose.material.icons.Icons.Outlined.Person, "叙述者头像",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(26.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            if (showHeader) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(senderLabel.ifBlank { "叙述者" }, modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(timeText, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                }
                Spacer(Modifier.height(6.dp))
            }
            if (textLoad !is BubbleTextLoad.Ready) {
                BubbleTextPreparationNotice(textLoad, retryText)
                return@Column
            }
            val body = textLoad.text.fallbackBody
            if (body.isNotBlank()) {
                SearchableMessageText(
                    text = body,
                    style = d.narrationTextStyle(),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                )
            }
            if (showHistoricalChoices) textLoad.text.choices.forEach { label ->
                if (label.isBlank()) return@forEach
                Surface(
                    shape = RoundedCornerShape(d.bubbleCornerOuter),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                ) {
                    SearchableMessageText(
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
