package com.mojing.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mojing.app.data.local.AutoVoiceMetadata
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.MessageAttachmentEntity

/** Ordinary chat and reading mode use the same explicit media actions. */
@Composable
internal fun VoiceAttachmentControls(
    message: MessageEntity,
    attachments: List<MessageAttachmentEntity>,
    canRetry: Boolean,
    isGenerating: Boolean,
    onAction: (MessageAction) -> Unit,
    canPlay: Boolean = true,
) {
    val count = attachments.count { it.assetType == "voice" && it.mimeType.startsWith("audio/") && it.storagePath.isNotBlank() }
    if (count > 0 && canPlay) {
        TextButton(onClick = { onAction(MessageAction.PlayVoiceAttachments(message)) },
            modifier = Modifier.semantics { contentDescription = "播放语音附件" }) {
            Icon(Icons.Outlined.PlayArrow, null)
            Spacer(Modifier.width(8.dp))
            Text(if (count == 1) "播放语音" else "播放语音 · $count 段")
        }
    } else if (canRetry && attachments.isEmpty() && message.speakerType == "character" &&
        message.characterId != null && message.parentMessageId != null && !message.includeInContext &&
        AutoVoiceMetadata.parse(message.structuredContentJson)?.retryable == true) {
        TextButton(onClick = { onAction(MessageAction.RetryAutoVoice(message)) }, enabled = !isGenerating,
            modifier = Modifier.semantics { contentDescription = "重试配音" }) {
            Icon(Icons.Outlined.Refresh, null)
            Spacer(Modifier.width(8.dp))
            Text("重试配音")
        }
    }
}
