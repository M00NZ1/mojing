package com.mojing.app.ui.chat

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.mojing.app.MainActivity
import com.mojing.app.ui.theme.MoJingTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BackgroundChatNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun poppedNavigationEntryKeepsHiltOwnerAndBackgroundWorkUntilCompletion() {
        val id = 9_876_543_210L // Missing session: this test performs no database writes or supplier calls.
        var first: ChatViewModel? = null
        var reopened: ChatViewModel? = null
        val finish = CompletableDeferred<Unit>()
        var complete = false
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                MoJingTheme {
                    val nav = rememberNavController()
                    NavHost(nav, startDestination = "home", enterTransition = { androidx.compose.animation.EnterTransition.None }, exitTransition = { androidx.compose.animation.ExitTransition.None }) {
                        composable("home") { Button(onClick = { nav.navigate("chat/$id") }) { Text("打开测试会话") } }
                        composable("chat/{sessionId}", arguments = listOf(navArgument("sessionId") { type = NavType.LongType })) { entry ->
                            val vm = retainedChatViewModel(entry, id)
                            SideEffect { if (first == null) first = vm else reopened = vm }
                            Column {
                                Button(onClick = {
                                    val job = vm.viewModelScope.launch { finish.await(); complete = true }
                                    RetainedChatSessions.retainGeneration(id, job, activity.applicationContext)
                                }) { Text("开始等待回复") }
                                Button(onClick = { nav.popBackStack() }) { Text("返回列表") }
                            }
                        }
                    }
                }
            }
        }
        try {
            compose.onNodeWithText("打开测试会话").performClick()
            compose.onNodeWithText("开始等待回复").performClick()
            compose.onNodeWithText("返回列表").performClick()
            compose.waitForIdle()
            assertTrue(id in RetainedChatSessions.running.value)
            compose.onNodeWithText("打开测试会话").performClick()
            compose.runOnIdle { assertSame(first, reopened) }
            compose.onNodeWithText("返回列表").performClick()
            finish.complete(Unit)
            compose.waitUntil(10000) { complete && id !in RetainedChatSessions.running.value }
            compose.runOnIdle { assertFalse(RetainedChatSessions.stores.contains(id)) }
        } finally {
            finish.complete(Unit)
        }
    }
}
