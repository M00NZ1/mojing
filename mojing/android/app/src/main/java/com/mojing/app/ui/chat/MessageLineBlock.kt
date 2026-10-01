package com.mojing.app.ui.chat

import com.mojing.app.ui.common.MoJingFilterChip as FilterChip
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mojing.app.data.local.entity.MessageAttachmentEntity
import com.mojing.app.data.local.entity.contextSelectionKey
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageLineBlock(
    line: ChatDisplayLine,
    messageAttachments: Map<Long, List<MessageAttachmentEntity>>,
    avatarPath: String,
    avatarColor: String,
    cardImagePath: String = "",
    userAvatarImagePath: String,
    userAvatarColor: String,
    userDisplayName: String,
    bookmarkedMessageIds: Set<Long>,
    excludedContextKeys: Set<String> = emptySet(),
    currentChoiceMessageId: Long? = null,
    canContinueReply: Boolean = false,
    canRegenerate: Boolean = false,
    isGenerating: Boolean = false,
    isSavingImages: Boolean = false,
    senderLabel: String,
    showSenderHeader: Boolean,
    timeText: String,
    onAction: (MessageAction) -> Unit,
    onSelectSwipeVersion: (swipeGroupId: String, messageId: Long, onResult: (Boolean) -> Unit) -> Unit,
    currentBranchId: String = "main",
    branchAnchors: List<BranchAnchor> = emptyList(),
    canReturnToMain: Boolean = false,
    readOnly: Boolean = false,
) {
    val d = LocalChatDensityMetrics.current
    val headerMsg = line.selectedMessage()
    val isUser = headerMsg.speakerType == "user"
    val labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
    val timeColor = MaterialTheme.colorScheme.onSurfaceVariant
    val clusterInlineHeader = line.variants.size == 1 &&
        (headerMsg.speakerType == "character" || headerMsg.speakerType == "user")
    Column(modifier = Modifier.fillMaxWidth()) {
        if (!clusterInlineHeader) {
            if (showSenderHeader) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = d.rowHorizontal, vertical = 0.dp),
                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!isUser) {
                        Text(senderLabel, modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.labelLarge, color = labelColor,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(timeText, style = MaterialTheme.typography.labelSmall,
                        color = timeColor, maxLines = 1)
                    if (isUser) {
                        Spacer(Modifier.width(8.dp))
                        Text(senderLabel, modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.labelLarge, color = labelColor,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(2.dp))
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = d.rowHorizontal, vertical = 0.dp),
                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
                ) {
                    Text(timeText, style = MaterialTheme.typography.labelSmall, color = timeColor)
                }
                Spacer(Modifier.height(1.dp))
            }
        }
        MessageBranchBar(
            branchAnchors = branchAnchors,
            canReturnToMain = canReturnToMain,
            currentBranchId = currentBranchId,
            onAction = onAction,
        )
        if (line.variants.size > 1 && line.swipeGroupId != null) {
            val lastIdx = line.variants.lastIndex
            val initial = line.selectedIndex.coerceIn(0, lastIdx)
            val pagerState = rememberPagerState(
                initialPage = initial,
                pageCount = { line.variants.size }
            )
            val latestLine by rememberUpdatedState(line)
            var selectionPending by remember(line.stableKey, currentBranchId) { mutableStateOf(false) }
            val latestSelectionCallback by rememberUpdatedState(onSelectSwipeVersion)
            val selectionLocked by rememberUpdatedState(isGenerating || readOnly)
            LaunchedEffect(line.selectedIndex, line.stableKey, isGenerating) {
                val target = line.selectedIndex.coerceIn(0, lastIdx)
                if (pagerState.currentPage != target) {
                    pagerState.scrollToPage(target)
                }
            }
            LaunchedEffect(line.stableKey, line.swipeGroupId, currentBranchId, pagerState) {
                snapshotFlow { pagerState.settledPage }
                    .distinctUntilChanged()
                    .collect { page ->
                        if (selectionLocked) return@collect
                        val currentLine = latestLine
                        val gid = currentLine.swipeGroupId ?: return@collect
                        val mid = currentLine.variants.getOrNull(page)?.id ?: return@collect
                        val expectedId = currentLine.selectedMessage().id
                        if (mid != expectedId) {
                            selectionPending = true
                            try {
                                val saved = suspendCancellableCoroutine<Boolean> { continuation ->
                                    latestSelectionCallback(gid, mid) { success ->
                                        if (continuation.isActive) continuation.resume(success)
                                    }
                                }
                                if (!saved) {
                                    val restored = latestLine
                                    pagerState.scrollToPage(restored.selectedIndex.coerceIn(0, restored.variants.lastIndex))
                                }
                            } finally {
                                selectionPending = false
                            }
                        }
                    }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth(),
                userScrollEnabled = !isGenerating && !selectionPending,
                verticalAlignment = Alignment.Top
            ) { page ->
                val msg = line.variants[page]
                MessageBubble(
                    readOnly = readOnly,
                    message = msg,
                    attachments = messageAttachments[msg.id].orEmpty(),
                    avatarPath = avatarPath,
                    avatarColor = avatarColor,
                    cardImagePath = cardImagePath,
                    userAvatarImagePath = userAvatarImagePath,
                    userAvatarColor = userAvatarColor,
                    userDisplayName = userDisplayName,
                    isBookmarked = bookmarkedMessageIds.contains(msg.id),
                    isContextExcluded = msg.contextSelectionKey() in excludedContextKeys,
                    canToggleContext = msg.id == headerMsg.id && msg.includeInContext,
                    canContinueReply = canContinueReply && msg.id == headerMsg.id,
                    canRegenerate = canRegenerate && msg.id == headerMsg.id,
                    isGenerating = isGenerating,
                    isSavingImages = isSavingImages,
                    isCurrentChoiceMessage = msg.id == currentChoiceMessageId,
                    onAction = onAction,
                    clusterInlineHeader = clusterInlineHeader,
                    senderLabel = senderLabel,
                    showSenderHeader = showSenderHeader,
                    timeText = timeText,
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = d.pagerLabelTop, bottom = d.pagerLabelBottom),
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    if (selectionPending) "正在切换回复…" else "${pagerState.settledPage + 1} / ${line.variants.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        } else {
            val msg = line.variants.firstOrNull() ?: return
            MessageBubble(
                    readOnly = readOnly,
                message = msg,
                attachments = messageAttachments[msg.id].orEmpty(),
                avatarPath = avatarPath,
                avatarColor = avatarColor,
                cardImagePath = cardImagePath,
                userAvatarImagePath = userAvatarImagePath,
                userAvatarColor = userAvatarColor,
                userDisplayName = userDisplayName,
                isBookmarked = bookmarkedMessageIds.contains(msg.id),
                isContextExcluded = msg.contextSelectionKey() in excludedContextKeys,
                canToggleContext = msg.includeInContext,
                canContinueReply = canContinueReply,
                canRegenerate = canRegenerate,
                isGenerating = isGenerating,
                isSavingImages = isSavingImages,
                isCurrentChoiceMessage = msg.id == currentChoiceMessageId,
                onAction = onAction,
                clusterInlineHeader = clusterInlineHeader,
                senderLabel = senderLabel,
                showSenderHeader = showSenderHeader,
                timeText = timeText,
            )
        }
        if (!readOnly && headerMsg.contextSelectionKey() in excludedContextKeys) {
            Text(
                "不参与后续上下文",
                modifier = Modifier.padding(horizontal = d.rowHorizontal, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (canContinueReply && !isGenerating) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = d.rowHorizontal),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { onAction(MessageAction.ContinueReply(headerMsg)) }) {
                    Text("继续生成回复")
                }
            }
        }
    }
}

@Composable
private fun MessageBranchBar(
    branchAnchors: List<BranchAnchor>,
    canReturnToMain: Boolean,
    currentBranchId: String,
    onAction: (MessageAction) -> Unit,
) {
    if (!canReturnToMain && branchAnchors.isEmpty()) return
    val d = LocalChatDensityMetrics.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = d.rowHorizontal, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (canReturnToMain) {
            FilterChip(
                selected = currentBranchId == "main",
                onClick = { onAction(MessageAction.SwitchToBranch("main")) },
                label = { Text("主线") },
            )
        }
        branchAnchors.forEach { anchor ->
            FilterChip(
                selected = currentBranchId == anchor.branchId,
                onClick = { onAction(MessageAction.SwitchToBranch(anchor.branchId)) },
                label = { Text(anchor.label, maxLines = 1) },
            )
        }
    }
}
