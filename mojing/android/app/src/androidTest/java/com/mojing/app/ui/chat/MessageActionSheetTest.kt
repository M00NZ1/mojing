package com.mojing.app.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.mojing.app.data.local.entity.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MessageActionSheetTest {
    @get:Rule val rule = createComposeRule()

    @Test fun previewAndCloseStayVisibleWhileActionsScroll() {
        val message = MessageEntity(id = 7L, sessionId = 2L, content = "灯塔下的约定，仍然留在雾港。".repeat(18))
        var action: MessageAction? = null
        rule.setContent { MaterialTheme {
            MessageBubble(message, senderLabel = "旅行者", onAction = { action = it })
        } }
        rule.onNodeWithText(message.content).performClick()
        val close = rule.onNodeWithContentDescription("关闭消息操作")
        close.assertIsDisplayed()
        rule.onNodeWithText("旅行者").assertIsDisplayed()
        val preview = rule.onNodeWithContentDescription("所选消息")
        val layouts = mutableListOf<TextLayoutResult>()
        preview.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.single().lineCount <= 2)
        val originalTop = close.fetchSemanticsNode().boundsInRoot.top
        rule.onNodeWithText("撤回").performScrollTo().assertIsDisplayed()
        close.assertIsDisplayed()
        preview.assertIsDisplayed()
        assertEquals(originalTop, close.fetchSemanticsNode().boundsInRoot.top)
        rule.onNodeWithText("撤回").performClick()
        rule.runOnIdle { assertEquals(MessageAction.Recall(message), action) }
        close.assertDoesNotExist()
    }
}
