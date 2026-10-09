package com.mojing.app.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import com.mojing.app.data.local.AutoVoiceMetadata
import com.mojing.app.data.local.entity.MessageAttachmentEntity
import com.mojing.app.data.local.entity.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MessageAutoVoiceRetryTest {
    @get:Rule val rule = createComposeRule()

    private fun voiceMessage(state: String = AutoVoiceMetadata.STATE_FAILED) = MessageEntity(
        id = 11, sessionId = 2, characterId = 3, parentMessageId = 7,
        speakerType = "character", includeInContext = false,
        content = "配音生成失败",
        structuredContentJson = AutoVoiceMetadata.create("原配音描述", state, "original-attempt"),
    )

    @Test fun explicitRetryDispatchesTheOriginalMessage() {
        val message = voiceMessage()
        val actions = mutableListOf<MessageAction>()
        rule.setContent { MaterialTheme { MessageBubble(message = message, onAction = actions::add) } }
        rule.onNodeWithContentDescription("重试配音").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(listOf(MessageAction.RetryAutoVoice(message)), actions) }
    }

    @Test fun readOnlyAndInheritedMessagesHaveNoRetryAction() {
        val readOnly = mutableStateOf(true)
        val ownBranch = mutableStateOf(true)
        rule.setContent {
            MaterialTheme { MessageBubble(message = voiceMessage(), readOnly = readOnly.value, canRetryAutoImage = ownBranch.value) }
        }
        rule.onAllNodesWithContentDescription("重试配音").assertCountEquals(0)
        rule.runOnIdle { readOnly.value = false; ownBranch.value = false }
        rule.onAllNodesWithContentDescription("重试配音").assertCountEquals(0)
        rule.runOnIdle { ownBranch.value = true }
        rule.onNodeWithContentDescription("重试配音").assertIsEnabled()
    }

    @Test fun activeGenerationDisablesRetryUntilOwnerSettles() {
        val busy = mutableStateOf(true)
        rule.setContent { MaterialTheme { MessageBubble(message = voiceMessage(), isGenerating = busy.value) } }
        rule.onNodeWithContentDescription("重试配音").assertIsNotEnabled()
        rule.runOnIdle { busy.value = false }
        rule.onNodeWithContentDescription("重试配音").assertIsEnabled()
    }

    @Test fun lineBlockUsesActualBranchAndReadOnlyGatesForSingleAndSwipeMessages() {
        val branch = mutableStateOf("other")
        val readOnly = mutableStateOf(false)
        val message = voiceMessage()
        val line = mutableStateOf(ChatDisplayLine("single", null, listOf(message), 0))
        rule.setContent {
            MaterialTheme {
                MessageLineBlock(
                    line = line.value, messageAttachments = emptyMap(), avatarPath = "", avatarColor = "",
                    userAvatarImagePath = "", userAvatarColor = "", userDisplayName = "", bookmarkedMessageIds = emptySet(),
                    senderLabel = "角色", showSenderHeader = false, timeText = "", onAction = {}, onSelectSwipeVersion = { _, _, _ -> },
                    currentBranchId = branch.value, readOnly = readOnly.value,
                )
            }
        }
        rule.onAllNodesWithContentDescription("重试配音").assertCountEquals(0)
        rule.runOnIdle { branch.value = "main" }
        rule.onNodeWithContentDescription("重试配音").assertIsEnabled()
        rule.runOnIdle { readOnly.value = true }
        rule.onAllNodesWithContentDescription("重试配音").assertCountEquals(0)
        rule.runOnIdle {
            readOnly.value = false
            branch.value = "other"
            line.value = ChatDisplayLine("swipe", "swipe-group", listOf(message, message.copy(id = 12)), 0)
        }
        rule.onAllNodesWithContentDescription("重试配音").assertCountEquals(0)
        rule.runOnIdle { branch.value = "main" }
        rule.onAllNodesWithContentDescription("重试配音").onFirst().assertIsEnabled()
        rule.runOnIdle { readOnly.value = true }
        rule.onAllNodesWithContentDescription("重试配音").assertCountEquals(0)
    }

    @Test fun malformedImportedRowsHaveNoUnusableAction() {
        val message = mutableStateOf(voiceMessage().copy(characterId = null))
        rule.setContent { MaterialTheme { MessageBubble(message = message.value) } }
        val malformed = listOf(
            voiceMessage().copy(parentMessageId = null),
            voiceMessage().copy(includeInContext = true),
            voiceMessage().copy(speakerType = "user"),
            voiceMessage().copy(structuredContentJson = AutoVoiceMetadata.create("原文", AutoVoiceMetadata.STATE_FAILED, "")),
        )
        rule.onAllNodesWithContentDescription("重试配音").assertCountEquals(0)
        malformed.forEach { row ->
            rule.runOnIdle { message.value = row }
            rule.onAllNodesWithContentDescription("重试配音").assertCountEquals(0)
        }
    }

    @Test fun runningCompletedLegacyAndAttachedMessagesCannotRetry() {
        val message = mutableStateOf(voiceMessage(AutoVoiceMetadata.STATE_RUNNING))
        val attachments = mutableStateOf(emptyList<MessageAttachmentEntity>())
        rule.setContent { MaterialTheme { MessageBubble(message = message.value, attachments = attachments.value) } }
        rule.onAllNodesWithContentDescription("重试配音").assertCountEquals(0)
        rule.runOnIdle { message.value = voiceMessage(AutoVoiceMetadata.STATE_COMPLETE) }
        rule.onAllNodesWithContentDescription("重试配音").assertCountEquals(0)
        rule.runOnIdle { message.value = voiceMessage().copy(structuredContentJson = """{"derived_media_version":1,"derived_media_kind":"voice"}""") }
        rule.onAllNodesWithContentDescription("重试配音").assertCountEquals(0)
        rule.runOnIdle {
            message.value = voiceMessage()
            attachments.value = listOf(MessageAttachmentEntity(messageId = 11, fileName = "existing-image", mimeType = "image/png", storagePath = "/missing/owned-image.png"))
        }
        rule.onAllNodesWithContentDescription("重试配音").assertCountEquals(0)
    }

    @Test fun orderedAttachmentsExposeOnePlayActionAndReadOnlyHasNoFakeAction() {
        val readOnly = mutableStateOf(false)
        val message = voiceMessage(AutoVoiceMetadata.STATE_COMPLETE).copy(content = "")
        val actions = mutableListOf<MessageAction>()
        val parts = (1L..3L).map { MessageAttachmentEntity(id = it, messageId = message.id, assetType = "voice", fileName = "part-$it.wav", mimeType = "audio/wav", storagePath = "/owned/part-$it.wav") }
        rule.setContent { MaterialTheme { MessageBubble(message = message, attachments = parts, readOnly = readOnly.value, onAction = actions::add) } }
        rule.onAllNodesWithContentDescription("播放语音附件").assertCountEquals(1)
        rule.onNodeWithContentDescription("播放语音附件").performClick()
        rule.runOnIdle { assertEquals(listOf(MessageAction.PlayVoiceAttachments(message)), actions); readOnly.value = true }
        rule.onAllNodesWithContentDescription("播放语音附件").assertCountEquals(0)
    }
    @Test fun readerUsesSameRetryAndPlayActions() {
        val message = mutableStateOf(voiceMessage())
        val parts = mutableStateOf(emptyList<MessageAttachmentEntity>())
        val actions = mutableListOf<MessageAction>()
        rule.setContent { MaterialTheme { ReaderMessage(message.value, parts.value, onAction = actions::add, canRetryMedia = true, canPlayMedia = true, onImageClick = {}) } }
        rule.onNodeWithContentDescription("重试配音").performClick()
        rule.runOnIdle {
            assertEquals(listOf(MessageAction.RetryAutoVoice(message.value)), actions)
            message.value = message.value.copy(content = "", structuredContentJson = AutoVoiceMetadata.create("原文", AutoVoiceMetadata.STATE_COMPLETE, "nonce"))
            parts.value = listOf(MessageAttachmentEntity(messageId = 11, assetType = "voice", mimeType = "audio/wav", storagePath = "/owned/voice.wav"))
        }
        rule.onNodeWithContentDescription("播放语音附件").performClick()
        rule.runOnIdle { assertEquals(MessageAction.PlayVoiceAttachments(message.value), actions.last()) }
    }
}
