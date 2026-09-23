package com.mojing.app.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ModelNamePickerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun searchesLargeCatalogWithoutComposingEveryModel() {
        var selected = ""
        compose.setContent {
            MaterialTheme {
                ModelNamePicker(List(5000) { "model-$it" }, "model-4999", { selected = it }, {})
            }
        }
        assertTrue(compose.onAllNodes(hasText("model-", substring = true)).fetchSemanticsNodes().size < 40)
        compose.onNodeWithText("model-4999").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithContentDescription("搜索模型").performClick()
        compose.onNodeWithTag("model-picker-search").performTextInput("MODEL-100")
        compose.onNodeWithText("model-100").performClick()
        compose.runOnIdle { assertEquals("model-100", selected) }
    }

    @Test fun emptySearchAndCancelLeaveSelectionAlone() {
        var dismissed = false
        var selected = "original"
        compose.setContent {
            MaterialTheme { ModelNamePicker(listOf("original"), selected, { selected = it }, { dismissed = true }) }
        }
        compose.onNodeWithContentDescription("搜索模型").performClick()
        compose.onNodeWithTag("model-picker-search").performTextInput("missing")
        compose.onNodeWithText("没有匹配的模型，试试其他关键词。").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.runOnIdle { assertTrue(dismissed); assertEquals("original", selected) }
    }
}
