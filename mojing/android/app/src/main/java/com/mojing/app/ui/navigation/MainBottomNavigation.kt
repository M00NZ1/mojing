package com.mojing.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
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
        MoJingNavItem(
            selected = selected == MainNavTab.Session,
            onClick = { requestNavigation { navController.returnToSessionHome() } },
            icon = Icons.AutoMirrored.Filled.Chat, label = "对话",
        )
        MoJingNavItem(
            selected = selected == MainNavTab.Create,
            onClick = { requestNavigation { navController.returnToCreationHub() } },
            icon = Icons.Default.Create, label = "创作",
        )
        MoJingNavItem(
            selected = selected == MainNavTab.Settings,
            onClick = { requestNavigation { navController.navigateToMainTab(Routes.SETTINGS) } },
            icon = Icons.Default.Settings, label = "设置",
        )
    }
}

@Composable
internal fun RowScope.MoJingNavItem(selected: Boolean, onClick: () -> Unit, icon: ImageVector, label: String) {
    val palette = MaterialTheme.colorScheme
    val background by animateColorAsState(if (selected) palette.primary else androidx.compose.ui.graphics.Color.Transparent, label = "tabSurface")
    val foreground by animateColorAsState(if (selected) palette.onPrimary else palette.onSurfaceVariant, label = "tabInk")
    Row(
        Modifier.weight(1f).padding(3.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
            .background(background).selectable(selected, onClick = onClick, role = Role.Tab)
            .heightIn(min = 48.dp).padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
    ) {
        Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(22.dp))
        Text(label, color = foreground, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

internal const val SETTINGS_MODEL_REQUEST = "settings_model_request"

internal fun NavHostController.navigateToModelSettings() {
    navigateToMainTab(Routes.SETTINGS)
    currentBackStackEntry?.savedStateHandle?.set(SETTINGS_MODEL_REQUEST, true)
}
