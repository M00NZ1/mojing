package com.mojing.app.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.mojing.app.ui.character.SamplingParameterField
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SamplingParameterFieldTest {
    @get:Rule val rule = createComposeRule()

    @Test fun negativeDecimalCanBeTypedAfterClearingAndDoneLeavesField() {
        val value = mutableStateOf("0.0")
        rule.setContent { MaterialTheme {
            SamplingParameterField(value.value, { value.value = it }, "存在惩罚", signed = true)
        } }
        val field = rule.onNode(hasSetTextAction())
        field.performClick().performTextClearance()
        rule.onNodeWithText("请输入数值").assertIsDisplayed()
        field.performTextInput("-")
        rule.runOnIdle { assertEquals("-", value.value) }
        rule.onNodeWithText("请输入有效数字").assertIsDisplayed()
        field.performTextInput("0.")
        rule.runOnIdle { assertEquals("-0.", value.value) }
        field.performTextInput("25")
        rule.runOnIdle { assertEquals("-0.25", value.value) }
        rule.onNodeWithText("请输入有效数字").assertDoesNotExist()
        field.performImeAction()
        field.assertIsNotFocused()
    }

    @Test fun integerErrorClearsAfterValidReplacement() {
        val value = mutableStateOf("1200")
        rule.setContent { MaterialTheme {
            SamplingParameterField(value.value, { value.value = it }, "最大 Token", integer = true)
        } }
        val field = rule.onNode(hasSetTextAction())
        field.performTextReplacement("99999999999999")
        rule.onNodeWithText("请输入正整数").assertIsDisplayed()
        field.performTextReplacement("2048")
        rule.onNodeWithText("请输入正整数").assertDoesNotExist()
        rule.runOnIdle { assertEquals("2048", value.value) }
    }
}
