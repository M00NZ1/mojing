package com.mojing.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mojing.app.data.local.entity.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MessageActionPanelTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun runningReplyDisablesMutationsAndRestoresActionsWhenStopped() {
        val generating = mutableStateOf(true)
        val message = MessageEntity(id = 42L, sessionId = 7L, speakerType = "character", content = "灯亮了。")
        val actions = mutableListOf<MessageAction>()
        var dismissCount = 0
        composeRule.setContent {
            MaterialTheme {
                Column {
                    MessageActionPanelContent(
                        message = message, isBookmarked = false, canContinueReply = true,
                        canRegenerate = true, isGenerating = generating.value,
                        imageAttachmentCount = 0, isSavingImages = false,
                        onDismiss = { dismissCount++ }, onAction = { actions += it },
                    )
                }
            }
        }
        listOf("编辑", "重新生成", "引用回复", "继续生成回复", "从此处分支", "撤回").forEach {
            composeRule.onNodeWithText(it).assertIsNotEnabled()
        }
        listOf("复制", "收藏消息", "朗读本句").forEach {
            composeRule.onNodeWithText(it).assertIsEnabled()
        }
        composeRule.onNodeWithText("复制").performClick()
        composeRule.runOnIdle { generating.value = false }
        composeRule.onNodeWithText("编辑").assertIsEnabled().performClick()
        composeRule.onNodeWithText("重新生成").assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(MessageAction.Copy(message), MessageAction.Edit(message), MessageAction.Regenerate(message)), actions)
            assertEquals(3, dismissCount)
        }
    }

    @Test
    fun unsupportedReplyActionsStayHiddenAndImageSaveReflectsProgress() {
        composeRule.setContent {
            MaterialTheme {
                Column {
                    MessageActionPanelContent(
                        message = MessageEntity(sessionId = 1L, speakerType = "user", content = "图片"),
                        isBookmarked = true, canContinueReply = false, canRegenerate = false,
                        isGenerating = false, imageAttachmentCount = 2, isSavingImages = true,
                        onDismiss = {}, onAction = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("重新生成").assertDoesNotExist()
        composeRule.onNodeWithText("继续生成回复").assertDoesNotExist()
        composeRule.onNodeWithText("保存图片中…").assertIsNotEnabled()
        composeRule.onNodeWithText("取消收藏").assertIsEnabled()
    }
}
