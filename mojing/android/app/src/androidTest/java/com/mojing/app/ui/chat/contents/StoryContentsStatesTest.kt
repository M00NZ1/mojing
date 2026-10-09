package com.mojing.app.ui.chat.contents

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.mojing.app.data.local.dao.MessageDao
import com.mojing.app.data.local.dao.StoryContentsMessageProjection
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import org.junit.Rule
import org.junit.Test

/** Isolated directory states; no production database, preferences or model client. */
class StoryContentsStatesTest {
    @get:Rule val rule = createComposeRule()
    private val rows = listOf(StoryContentsMessageProjection(30, "narrator", "main", 0,
        """{"chapter_number":30,"chapter_title":"灯塔来信"}""", "原文片段"))
    private fun dao(read: (Array<out Any?>) -> Any?): MessageDao = Proxy.newProxyInstance(
        MessageDao::class.java.classLoader, arrayOf(MessageDao::class.java),
    ) { _, method, args -> when(method.name) {
        "getVisibleStoryContentsBefore" -> read(args.orEmpty())
        "getStoryChapterTail" -> null
        "toString" -> "IsolatedContentsDao"
        "hashCode" -> 30
        "equals" -> false
        else -> error("unexpected directory read: ${method.name}")
    } } as MessageDao

    @Test fun loadingCanCloseAndLateReadDoesNotReopen() {
        var pending: Continuation<List<StoryContentsMessageProjection>>? = null
        val vm = StoryContentsViewModel(dao { args ->
            @Suppress("UNCHECKED_CAST")
            val continuation = args.last() as Continuation<List<StoryContentsMessageProjection>>
            pending = continuation
            COROUTINE_SUSPENDED
        })
        val visible = mutableStateOf(true)
        rule.setContent { MaterialTheme { StoryContentsSheet(visible.value, 7, "main",
            onOpenMessage = { _, _ -> false }, onDismiss = { visible.value = false }, viewModel = vm) } }
        rule.waitUntil { pending != null }
        rule.onNode(hasProgressBarRangeInfo(androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
        rule.onNodeWithContentDescription("关闭小说目录").performClick()
        rule.runOnIdle { pending!!.resume(rows) }
        rule.onNodeWithContentDescription("关闭小说目录").assertDoesNotExist()
    }

    @Test fun catalogAndChapterFailuresRetryInPlace() {
        var reads = 0
        val vm = StoryContentsViewModel(dao { if (++reads == 1) error("synthetic read failure") else rows })
        val visible = mutableStateOf(true)
        var opens = 0
        rule.setContent { MaterialTheme { StoryContentsSheet(visible.value, 7, "main",
            onOpenMessage = { id, done -> check(id == 30L); done(++opens > 1); true },
            onDismiss = { visible.value = false }, viewModel = vm) } }
        rule.onNodeWithText("目录加载失败，请重试").assertIsDisplayed()
        rule.onNodeWithText("重试").performClick()
        rule.onNodeWithText("灯塔来信").assertIsDisplayed().performClick()
        rule.onNodeWithText("章节原文不可用或加载失败").assertIsDisplayed()
        rule.onNodeWithText("重试").performClick()
        rule.onNodeWithContentDescription("关闭小说目录").assertDoesNotExist()
        rule.runOnIdle { check(reads == 2 && opens == 2) }
    }

    @Test fun emptyDirectoryCanClose() {
        val vm = StoryContentsViewModel(dao { emptyList<StoryContentsMessageProjection>() })
        val visible = mutableStateOf(true)
        rule.setContent { MaterialTheme { StoryContentsSheet(visible.value, 7, "main",
            onOpenMessage = { _, _ -> false }, onDismiss = { visible.value = false }, viewModel = vm) } }
        rule.onNodeWithText("当前故事线还没有章节，点击下方生成开篇。").assertIsDisplayed()
        rule.onNodeWithContentDescription("关闭小说目录").performClick()
        rule.onNodeWithContentDescription("关闭小说目录").assertDoesNotExist()
    }
}
