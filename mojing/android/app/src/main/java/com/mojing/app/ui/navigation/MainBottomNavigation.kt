package com.mojing.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.mojing.app.ui.common.ImeHideAwareNavigationBar

internal enum class MainNavTab {
    Session,
    Create,
    Settings,
}

private val mainRootRoutes = setOf(
    Routes.SESSION_LIST,
    Routes.CREATION_HUB,
    Routes.CHARACTER_LIST,
    Routes.ENCYCLOPEDIA_LIST,
    Routes.WORKBENCH,
    Routes.STORY_SIMULATION,
    Routes.SETTINGS,
)

internal fun shouldShowMainBottomNavigation(route: String?): Boolean {
    return route != null && route in mainRootRoutes
}

internal fun routeToMainNavTab(route: String?): MainNavTab? {
    if (route.isNullOrBlank()) return null
    return when {
        route == Routes.SESSION_LIST || route.startsWith("chat/") -> MainNavTab.Session
        route == Routes.CREATION_HUB || route == Routes.CHARACTER_LIST || route.startsWith("characters/") ||
            route == Routes.ENCYCLOPEDIA_LIST || route.startsWith("encyclopedias/") ||
            route == Routes.WORKBENCH || route.startsWith("workbench/") ||
            route == Routes.STORY_SIMULATION -> MainNavTab.Create
        route == Routes.SETTINGS -> MainNavTab.Settings
        else -> null
    }
}

internal fun NavHostController.navigateToMainTab(route: String) {
    navigate(route) {
        launchSingleTop = true
        restoreState = true
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
    }
}

/** 外部入口必须确定到达目标，不恢复上一次主分区保存的子页面。 */
internal fun NavHostController.navigateToExternalRoot(route: String) {
    navigate(route) {
        launchSingleTop = true
        popUpTo(graph.findStartDestination().id)
    }
}

/** 页面内入口只保留一个栈顶实例，避免快速重复点击后需要连续返回。 */
internal fun NavHostController.navigateSingleTop(route: String) {
    navigate(route) {
        launchSingleTop = true
    }
}

internal fun NavHostController.returnToSessionHome() {
    if (!popBackStack(Routes.SESSION_LIST, inclusive = false)) {
        navigateToMainTab(Routes.SESSION_LIST)
    }
}

internal fun NavHostController.returnToCreationHub() {
    if (!popBackStack(Routes.CREATION_HUB, inclusive = false)) {
        navigateToMainTab(Routes.CREATION_HUB)
    }
}

@Composable
fun MainAppBottomNavigation(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    onNavigateRequest: ((() -> Unit) -> Unit)? = null,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    if (!shouldShowMainBottomNavigation(currentRoute)) return
    val selected = routeToMainNavTab(currentRoute)
    fun requestNavigation(action: () -> Unit) {
        onNavigateRequest?.invoke(action) ?: action()
    }
    ImeHideAwareNavigationBar(modifier) {
        NavigationBarItem(
            selected = selected == MainNavTab.Session,
            onClick = { requestNavigation { navController.returnToSessionHome() } },
            icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null) },
            label = { Text("对话") },
        )
        NavigationBarItem(
            selected = selected == MainNavTab.Create,
            onClick = { requestNavigation { navController.returnToCreationHub() } },
            icon = { Icon(Icons.Default.Create, contentDescription = null) },
            label = { Text("创作") },
        )
        NavigationBarItem(
            selected = selected == MainNavTab.Settings,
            onClick = { requestNavigation { navController.navigateToMainTab(Routes.SETTINGS) } },
            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
            label = { Text("设置") },
        )
    }
}
