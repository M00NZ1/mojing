package com.mojing.app.ui.chat.drawer

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.mojing.app.data.local.entity.MessageBookmarkEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BookmarkWindowRestoreTest {
    @get:Rule val rule=createComposeRule()
    private fun rows()=(140L downTo 21L).map { MessageBookmarkEntity(id=it,sessionId=9,messageId=it,note="备注$it",createdAt=it) }
    @Test fun deepAnchorWaitsForReadyDelayedRowsAndFailedRead() {
        val ready=mutableStateOf(true);val loaded=mutableStateOf(true);val error=mutableStateOf<String?>(null);var restoring=false
        val tester=StateRestorationTester(rule)
        tester.setContent { MaterialTheme { BookmarksTab(bookmarks=if(!restoring || loaded.value) rows() else emptyList(),bookmarkPreviews=rows().associate { it.messageId to "原文${it.id}" },
            onJump={},onRemove={},onLoadMore={},loaded=!restoring || loaded.value,sessionReady=!restoring || ready.value,sessionId=9,loadError=error.value) } }
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("备注30"))
        val before=rule.onNodeWithText("备注30").fetchSemanticsNode().boundsInRoot.top
        rule.runOnIdle { ready.value=false;loaded.value=false };rule.waitForIdle();restoring=true
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("备注30").assertDoesNotExist()
        rule.runOnIdle { ready.value=true;error.value="收藏读取失败，请重试" }
        rule.onNodeWithText("重试加载").performClick()
        rule.onNodeWithText("备注30").assertDoesNotExist()
        rule.runOnIdle { loaded.value=true;error.value=null }
        rule.onNodeWithText("备注30").assertIsDisplayed()
        assertEquals(before,rule.onNodeWithText("备注30").fetchSemanticsNode().boundsInRoot.top,2f)
    }
    @Test fun newQueryAndRecentResetStartAtFirstRow() {
        val query=mutableStateOf("");val newer=mutableStateOf(true)
        rule.setContent { MaterialTheme { BookmarksTab(bookmarks=rows(),bookmarkPreviews=rows().associate { it.messageId to "原文${it.id}" },
            onJump={},onRemove={},onLoadMore={},query=query.value,onQueryChange={query.value=it},hasNewer=newer.value,onResetWindow={newer.value=false},sessionId=9) } }
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("备注30"))
        rule.onNodeWithText("回到最近收藏").performClick()
        rule.onNodeWithText("备注140").assertIsDisplayed()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("备注30"))
        rule.runOnIdle { query.value="新查询" }
        rule.onNodeWithText("备注140").assertIsDisplayed()
    }
    @Test fun sessionChangeStartsAtFirstRow() {
        val sid=mutableStateOf(9L)
        rule.setContent { MaterialTheme { BookmarksTab(bookmarks=rows(),bookmarkPreviews=rows().associate { it.messageId to "原文${it.id}" },onJump={},onRemove={},onLoadMore={},sessionId=sid.value) } }
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("备注30"))
        rule.runOnIdle { sid.value=10L }
        rule.onNodeWithText("备注140").assertIsDisplayed()
    }
    @Test fun typingNewQueryKeepsInputFocus() {
        val query=mutableStateOf("")
        rule.setContent { MaterialTheme { BookmarksTab(bookmarks=rows(),bookmarkPreviews=emptyMap(),onJump={},onRemove={},onLoadMore={},query=query.value,onQueryChange={query.value=it},sessionId=9) } }
        val input=rule.onNodeWithContentDescription("搜索收藏备注")
        input.performClick();input.performTextInput("线")
        input.assertIsFocused();input.performTextInput("索")
        input.assertIsFocused();rule.runOnIdle { assertEquals("线索",query.value) }
    }
}
