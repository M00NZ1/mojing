package com.mojing.app.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.mojing.app.ui.common.MoJingLongTextField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MoJingLongTextFieldTest {
    @get:Rule val rule = createComposeRule()

    @Test fun longContentStaysBoundedAndEditsSurviveCollapse() {
        val original = (1..200).joinToString("\n") { "第${it}段：雾港的故事仍在继续。" }
        val value = mutableStateOf(original)
        rule.setContent { MaterialTheme {
            MoJingLongTextField(value.value, { value.value = it }, "人设提示词", "角色背景")
        } }
        val field = rule.onNode(hasSetTextAction())
        val collapsedHeight = field.fetchSemanticsNode().boundsInRoot.height
        rule.onNodeWithText("展开编辑").assertIsDisplayed().performClick()
        val editor = rule.onNodeWithContentDescription("人设提示词")
        val expandedHeight = editor.fetchSemanticsNode().boundsInRoot.height
        assertTrue(expandedHeight > collapsedHeight)
        rule.onNodeWithText("完成").assertIsDisplayed()
        editor.performTextReplacement("修改后的角色背景\n保留新的说话风格")
        rule.onNodeWithText("完成").performClick()
        rule.runOnIdle { assertEquals("修改后的角色背景\n保留新的说话风格", value.value) }
        rule.onNodeWithText("展开编辑").assertIsDisplayed()
    }
}
