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
import com.mojing.app.data.local.AutoImageMetadata
import com.mojing.app.data.local.entity.MessageAttachmentEntity
import com.mojing.app.data.local.entity.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MessageAutoImageRetryTest {
    @get:Rule val rule = createComposeRule()

    private fun imageMessage(state: String = AutoImageMetadata.STATE_FAILED) = MessageEntity(
        id = 11, sessionId = 2, characterId = 3, parentMessageId = 7,
        speakerType = "character", includeInContext = false,
        content = "配图生成失败",
        structuredContentJson = AutoImageMetadata.create("原配图描述", state, "original-attempt"),
    )

    @Test fun explicitRetryDispatchesTheOriginalMessage() {
        val message = imageMessage()
        val actions = mutableListOf<MessageAction>()
        rule.setContent { MaterialTheme { MessageBubble(message = message, onAction = actions::add) } }
        rule.onNodeWithContentDescription("重试配图").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(listOf(MessageAction.RetryAutoImage(message)), actions) }
    }

    @Test fun readOnlyAndInheritedMessagesHaveNoRetryAction() {
        val readOnly = mutableStateOf(true)
        val ownBranch = mutableStateOf(true)
        rule.setContent {
            MaterialTheme { MessageBubble(message = imageMessage(), readOnly = readOnly.value, canRetryAutoImage = ownBranch.value) }
        }
        rule.onAllNodesWithContentDescription("重试配图").assertCountEquals(0)
        rule.runOnIdle { readOnly.value = false; ownBranch.value = false }
        rule.onAllNodesWithContentDescription("重试配图").assertCountEquals(0)
        rule.runOnIdle { ownBranch.value = true }
        rule.onNodeWithContentDescription("重试配图").assertIsEnabled()
    }

    @Test fun activeGenerationDisablesRetryUntilOwnerSettles() {
        val busy = mutableStateOf(true)
        rule.setContent { MaterialTheme { MessageBubble(message = imageMessage(), isGenerating = busy.value) } }
        rule.onNodeWithContentDescription("重试配图").assertIsNotEnabled()
        rule.runOnIdle { busy.value = false }
        rule.onNodeWithContentDescription("重试配图").assertIsEnabled()
    }

    @Test fun lineBlockUsesActualBranchAndReadOnlyGatesForSingleAndSwipeMessages() {
        val branch = mutableStateOf("other")
        val readOnly = mutableStateOf(false)
        val message = imageMessage()
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
        rule.onAllNodesWithContentDescription("重试配图").assertCountEquals(0)
        rule.runOnIdle { branch.value = "main" }
        rule.onNodeWithContentDescription("重试配图").assertIsEnabled()
        rule.runOnIdle { readOnly.value = true }
        rule.onAllNodesWithContentDescription("重试配图").assertCountEquals(0)
        rule.runOnIdle {
            readOnly.value = false
            branch.value = "other"
            line.value = ChatDisplayLine("swipe", "swipe-group", listOf(message, message.copy(id = 12)), 0)
        }
        rule.onAllNodesWithContentDescription("重试配图").assertCountEquals(0)
        rule.runOnIdle { branch.value = "main" }
        rule.onAllNodesWithContentDescription("重试配图").onFirst().assertIsEnabled()
        rule.runOnIdle { readOnly.value = true }
        rule.onAllNodesWithContentDescription("重试配图").assertCountEquals(0)
    }

    @Test fun malformedImportedRowsHaveNoUnusableAction() {
        val message = mutableStateOf(imageMessage().copy(characterId = null))
        rule.setContent { MaterialTheme { MessageBubble(message = message.value) } }
        val malformed = listOf(
            imageMessage().copy(parentMessageId = null),
            imageMessage().copy(includeInContext = true),
            imageMessage().copy(speakerType = "user"),
            imageMessage().copy(structuredContentJson = AutoImageMetadata.create("原提示", AutoImageMetadata.STATE_FAILED, "")),
        )
        rule.onAllNodesWithContentDescription("重试配图").assertCountEquals(0)
        malformed.forEach { row ->
            rule.runOnIdle { message.value = row }
            rule.onAllNodesWithContentDescription("重试配图").assertCountEquals(0)
        }
    }

    @Test fun runningCompletedLegacyAndAttachedMessagesCannotRetry() {
        val message = mutableStateOf(imageMessage(AutoImageMetadata.STATE_RUNNING))
        val attachments = mutableStateOf(emptyList<MessageAttachmentEntity>())
        rule.setContent { MaterialTheme { MessageBubble(message = message.value, attachments = attachments.value) } }
        rule.onAllNodesWithContentDescription("重试配图").assertCountEquals(0)
        rule.runOnIdle { message.value = imageMessage(AutoImageMetadata.STATE_COMPLETE) }
        rule.onAllNodesWithContentDescription("重试配图").assertCountEquals(0)
        rule.runOnIdle { message.value = imageMessage().copy(structuredContentJson = """{"derived_media_version":1,"derived_media_kind":"image"}""") }
        rule.onAllNodesWithContentDescription("重试配图").assertCountEquals(0)
        rule.runOnIdle {
            message.value = imageMessage()
            attachments.value = listOf(MessageAttachmentEntity(messageId = 11, fileName = "existing-image", mimeType = "image/png", storagePath = "/missing/owned-image.png"))
        }
        rule.onAllNodesWithContentDescription("重试配图").assertCountEquals(0)
    }
}
