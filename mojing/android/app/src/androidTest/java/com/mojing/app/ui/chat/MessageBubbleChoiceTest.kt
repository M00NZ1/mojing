package com.mojing.app.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mojing.app.data.local.entity.MessageAttachmentEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.domain.engine.StructuredParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class MessageBubbleChoiceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun markdownChoicesAreSeparatedFromStoryAndReadOnlyInHistory() {
        var selectedChoice: String? = null
        val message = MessageEntity(
            sessionId = 1L,
            speakerType = "character",
            content = """
                风雨越来越大，你必须马上决定。

                ### 可选行动
                1. 进入山洞
                2. 沿原路返回
            """.trimIndent(),
        )

        composeRule.setContent {
            MaterialTheme {
                MessageBubble(
                    message = message,
                    onAction = { action ->
                        if (action is MessageAction.SelectChoice) selectedChoice = action.choice
                    },
                )
            }
        }

        composeRule.onNodeWithText("风雨越来越大，你必须马上决定。").assertIsDisplayed()
        composeRule.onAllNodesWithText("1. 进入山洞").assertCountEquals(0)
        composeRule.onNodeWithText("▸ 进入山洞").assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertNull(selectedChoice)
        }
    }

    @Test
    fun currentNarratorChoicesAreOwnedByBottomRow() {
        var selectedChoice: String? = null
        val message = MessageEntity(
            sessionId = 1L,
            speakerType = "narrator",
            content = "<NARRATION>钟声响起。</NARRATION>" +
                "<CHOICES><OPTION>走向钟楼</OPTION><OPTION>留在广场</OPTION></CHOICES>",
        )

        composeRule.setContent {
            MaterialTheme {
                MessageBubble(
                    message = message,
                    isCurrentChoiceMessage = true,
                    onAction = { action ->
                        if (action is MessageAction.SelectChoice) selectedChoice = action.choice
                    },
                )
            }
        }

        composeRule.onAllNodesWithText("▸ 走向钟楼").assertCountEquals(0)
        composeRule.runOnIdle {
            assertNull(selectedChoice)
        }
    }

    @Test
    fun inlineSuggestionDispatchesOnlyAnExplicitSelection() {
        var selectedChoice: String? = null
        val reply = StructuredParser.parse(
            """
                她把手放在门把上，回头看你。

                ### 可选行动（任选其一）：
                > 1. 推门进入
                > （二）先观察四周

                请选择你接下来要做的事。
            """.trimIndent(),
        )

        composeRule.setContent {
            MaterialTheme {
                if (shouldShowRoundChoices(isImeOpen = false, choices = reply.choices, isGenerating = false)) {
                    RoundChoicesRow(
                        choices = reply.choices,
                        onSelect = { selectedChoice = it },
                    )
                }
            }
        }

        composeRule.onNodeWithText("1.   推门进入").assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals("推门进入", selectedChoice)
        }
    }

    @Test
    fun tappingMessageImageOpensAndClosesFullScreenPreview() {
        val message = MessageEntity(
            id = 9L,
            sessionId = 1L,
            speakerType = "user",
            content = "",
        )
        val attachment = MessageAttachmentEntity(
            id = 11L,
            messageId = message.id,
            fileName = "图片附件",
            mimeType = "image/png",
            storagePath = "/missing/preview.png",
        )

        composeRule.setContent {
            MaterialTheme {
                MessageBubble(message = message, attachments = listOf(attachment))
            }
        }

        composeRule.onNodeWithContentDescription("图片附件").performClick()
        composeRule.onNodeWithTag("image_preview").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("关闭图片预览").performClick()
        composeRule.onAllNodesWithTag("image_preview").assertCountEquals(0)
        composeRule.onAllNodesWithText("引用回复").assertCountEquals(0)
    }

    @Test
    fun characterFallbackAndMessageMenuUseStoryFacingLabels() {
        val message = MessageEntity(
            id = 12L,
            sessionId = 1L,
            speakerType = "character",
            content = "晚上好。",
        )

        composeRule.setContent {
            MaterialTheme {
                MessageBubble(
                    message = message,
                    senderLabel = "林默",
                    showSenderHeader = true,
                )
            }
        }

        composeRule.onNodeWithText("林").assertIsDisplayed()
        composeRule.onNodeWithText("晚上好。").performClick()
        composeRule.onNodeWithText("引用回复").assertIsDisplayed()
        composeRule.onNodeWithText("从此处分支").assertIsDisplayed()
        composeRule.onAllNodesWithText("↩️ 引用回复").assertCountEquals(0)
    }
}
