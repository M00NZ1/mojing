package com.mojing.app.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.mojing.app.data.local.dao.EncyclopediaEntryOption
import com.mojing.app.ui.encyclopedia.EncyclopediaEntryPicker
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class EncyclopediaEntryPickerTest {
    @get:Rule val rule = createComposeRule()
    @Test fun pagesSearchFailureRetrySelectionAndCloseStayReachable() {
        var selected: Long? = null
        var closed = false
        var fail = true
        val entries = (1L..130L).map { EncyclopediaEntryOption(it, "条目$it", "person") }
        rule.setContent { MaterialTheme {
            EncyclopediaEntryPicker(1, 1, { query, after ->
                if (query == "条目130" && fail) error("read failed")
                entries.filter { it.id > after && it.title.contains(query) }.take(51)
            }, { closed = true }, { selected = it.id })
        } }
        rule.onNodeWithTag("entry-option:1").assertIsSelected()
        rule.onNodeWithText("下一页").assertIsEnabled().performClick()
        rule.onNodeWithText("第 2 页").assertIsDisplayed()
        rule.onNodeWithTag("entry-option:51").assertIsDisplayed()
        rule.onNodeWithText("搜索条目名称").performTextInput("条目130")
        rule.onNodeWithText("第 1 页").assertIsDisplayed()
        rule.onNodeWithText("资料读取失败，请重试").assertIsDisplayed()
        rule.onNodeWithText("下一页").assertIsNotEnabled()
        rule.runOnIdle { fail = false }
        rule.onNodeWithText("重试").performClick()
        rule.onNodeWithTag("entry-option:130").assertIsDisplayed().performClick()
        rule.onNodeWithText("关闭").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(130L, selected); assertTrue(closed) }
    }

    @Test fun closingCancelsPendingRead() {
        var open by mutableStateOf(true)
        var cancelled = false
        rule.setContent { MaterialTheme {
            if (open) EncyclopediaEntryPicker(1, null, { _, _ ->
                try { awaitCancellation() } finally { cancelled = true }
            }, { open = false }, {})
        } }
        rule.onNodeWithText("关闭").assertIsDisplayed().performClick()
        rule.runOnIdle { assertTrue(cancelled) }
    }
}
