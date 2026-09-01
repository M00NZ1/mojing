package com.mojing.app.ui.chat

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mojing.app.data.local.entity.MessageAttachmentEntity
import kotlinx.coroutines.flow.distinctUntilChanged

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
    currentChoiceMessageId: Long? = null,
    canContinueReply: Boolean = false,
    canRegenerate: Boolean = false,
    isGenerating: Boolean = false,
    isSavingImages: Boolean = false,
    senderLabel: String,
    showSenderHeader: Boolean,
    timeText: String,
    onAction: (MessageAction) -> Unit,
    onSelectSwipeVersion: (swipeGroupId: String, messageId: Long) -> Unit,
    currentBranchId: String = "main",
    branchAnchors: List<BranchAnchor> = emptyList(),
    canReturnToMain: Boolean = false,
) {
    val d = LocalChatDensityMetrics.current
    val headerMsg = line.selectedMessage()
    val isUser = headerMsg.speakerType == "user"
    val labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
    val timeColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
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
                        Text(senderLabel, style = MaterialTheme.typography.labelLarge, color = labelColor)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(timeText, style = MaterialTheme.typography.labelSmall, color = timeColor)
                    if (isUser) {
                        Spacer(Modifier.width(8.dp))
                        Text(senderLabel, style = MaterialTheme.typography.labelLarge, color = labelColor)
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
                    Text(timeText, style = MaterialTheme.typography.labelSmall, color = timeColor.copy(alpha = 0.45f))
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
            LaunchedEffect(line.selectedIndex, line.stableKey, isGenerating) {
                val target = line.selectedIndex.coerceIn(0, lastIdx)
                if (pagerState.currentPage != target) {
                    pagerState.scrollToPage(target)
                }
            }
            LaunchedEffect(line.stableKey, line.swipeGroupId, pagerState) {
                snapshotFlow { pagerState.settledPage }
                    .distinctUntilChanged()
                    .collect { page ->
                        val gid = line.swipeGroupId ?: return@collect
                        val safePage = page.coerceIn(0, lastIdx)
                        val mid = line.variants.getOrNull(safePage)?.id ?: return@collect
                        val selIdx = line.selectedIndex.coerceIn(0, lastIdx)
                        val expectedId = line.variants[selIdx].id
                        if (mid != expectedId) {
                            onSelectSwipeVersion(gid, mid)
                        }
                    }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth(),
                userScrollEnabled = !isGenerating,
                verticalAlignment = Alignment.Top
            ) { page ->
                val msg = line.variants[page]
                MessageBubble(
                    message = msg,
                    attachments = messageAttachments[msg.id].orEmpty(),
                    avatarPath = avatarPath,
                    avatarColor = avatarColor,
                    cardImagePath = cardImagePath,
                    userAvatarImagePath = userAvatarImagePath,
                    userAvatarColor = userAvatarColor,
                    userDisplayName = userDisplayName,
                    isBookmarked = bookmarkedMessageIds.contains(msg.id),
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
                    "${pagerState.settledPage + 1} / ${line.variants.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        } else {
            val msg = line.variants.firstOrNull() ?: return
            MessageBubble(
                message = msg,
                attachments = messageAttachments[msg.id].orEmpty(),
                avatarPath = avatarPath,
                avatarColor = avatarColor,
                cardImagePath = cardImagePath,
                userAvatarImagePath = userAvatarImagePath,
                userAvatarColor = userAvatarColor,
                userDisplayName = userDisplayName,
                isBookmarked = bookmarkedMessageIds.contains(msg.id),
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
