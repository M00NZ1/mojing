package com.mojing.app.ui.chat

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mojing.app.ui.navigation.Routes
import com.mojing.app.ui.navigation.chatRouteArguments
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SourceRouteTest {
    @get:Rule val rule = createComposeRule()
    @Test fun sourceArgumentsRoundTripAndBackReturnsToEntry() {
        lateinit var nav: NavHostController
        rule.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = "entry") {
                composable("entry") { Text("百科编辑") }
                composable(Routes.CHAT, arguments = chatRouteArguments()) { Text("对话") }
            }
        }
        val branch = "剧情 / 分支?一&二"
        rule.runOnIdle { nav.navigate(Routes.chatSource(42, 500, branch)) }
        rule.runOnIdle {
            val args = nav.currentBackStackEntry!!.arguments!!
            assertEquals(42L, args.getLong("sessionId"))
            assertEquals(500L, args.getLong("sourceMessageId"))
            assertEquals(branch, args.getString("sourceBranchId"))
            assertTrue(nav.popBackStack())
        }
        rule.runOnIdle {
            assertEquals("entry", nav.currentDestination?.route)
            nav.navigate(Routes.chat(42))
        }
        rule.runOnIdle {
            val args = nav.currentBackStackEntry!!.arguments!!
            assertEquals(0L, args.getLong("sourceMessageId"))
            assertEquals("", args.getString("sourceBranchId"))
        }
    }
}
