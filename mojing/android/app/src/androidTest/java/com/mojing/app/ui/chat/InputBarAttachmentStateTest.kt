package com.mojing.app.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class InputBarAttachmentStateTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactComposerKeepsAttachmentAndSendReachableAndClosesToolsOnGeneration() {
        val generating = mutableStateOf(false)
        var attachments = 0
        var sends = 0
        var stops = 0
        var emoji = 0
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.width(280.dp)) {
                    InputBar(
                        value = TextFieldValue("继续这段故事"),
                        onValueChange = {},
                        onSend = { sends++ },
                        onStop = { stops++ },
                        isGenerating = generating.value,
                        isImeOpen = true,
                        onRequestNarrator = {},
                        onInsertMacro = {},
                        onClearPendingAttachments = {},
                        onVoiceClick = {},
                        onImageGenClick = {},
                        onAttachImageClick = { attachments++ },
                        onOpenEmoji = { emoji++ },
                        onPreviewSpeak = {},
                    )
                }
            }
        }
        composeRule.onNodeWithContentDescription("添加图片").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("发送").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("收起键盘").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("更多输入工具").performClick()
        composeRule.onNodeWithText("表情").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("输入工具").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("更多输入工具").performClick()
        composeRule.runOnIdle { generating.value = true }
        composeRule.onNodeWithText("输入工具").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("停止").assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals(1, attachments)
            assertEquals(1, sends)
            assertEquals(1, stops)
            assertEquals(1, emoji)
        }
    }

    @Test
    fun attachmentCopyKeepsDraftEditableButBlocksConflictingActions() {
        var sendCount = 0
        var clearCount = 0

        composeRule.setContent {
            MaterialTheme {
                InputBar(
                    value = TextFieldValue("准备发送"),
                    onValueChange = {},
                    onSend = { sendCount += 1 },
                    onStop = {},
                    isGenerating = false,
                    isAddingAttachment = true,
                    onRequestNarrator = {},
                    onInsertMacro = {},
                    pendingAttachmentCount = 1,
                    onClearPendingAttachments = { clearCount += 1 },
                    onVoiceClick = {},
                    onImageGenClick = {},
                    onAttachImageClick = {},
                    onOpenEmoji = {},
                    onPreviewSpeak = {},
                )
            }
        }

        composeRule.onNodeWithText("正在把所选图片保存到本机…").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("发送").assertIsNotEnabled()
        composeRule.onNodeWithText("清除").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("添加图片").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("更多输入工具")
            .assertIsNotEnabled()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        composeRule.runOnIdle {
            assertEquals(0, sendCount)
            assertEquals(0, clearCount)
        }
    }
}
