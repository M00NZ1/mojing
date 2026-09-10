package com.mojing.app.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.mojing.app.ui.encyclopedia.EntryConfidenceSelector
import com.mojing.app.ui.encyclopedia.entryConfidenceLabel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class EntryConfidenceSelectorTest {
    @get:Rule val rule = createComposeRule()
    @Test fun inferredStateIsReadableAndCanBeConfirmed() {
        val value = mutableStateOf("inferred")
        rule.setContent { MaterialTheme {
            EntryConfidenceSelector(value.value, { value.value = it })
        } }
        rule.onNodeWithText("对话推断").assertIsDisplayed().performClick()
        rule.onNodeWithText("已确认").performClick()
        rule.onNodeWithText("已确认").assertIsDisplayed()
        rule.runOnIdle {
            assertEquals("confirmed", value.value)
            assertEquals("对话推断", entryConfidenceLabel("inferred"))
            assertEquals("未标注", entryConfidenceLabel("future-value"))
        }
    }
}
