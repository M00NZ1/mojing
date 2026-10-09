package com.mojing.app.ui.theme

import android.view.Window
import android.view.View
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

@Composable
@Suppress("DEPRECATION")
internal fun SystemBarAppearance(activity: ComponentActivity, background: Color, splashVisible: Boolean) {
    val window = activity.window
    val darkIcons = !splashVisible && background.luminance() > 0.5f
    SideEffect {
        val style = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { !darkIcons }
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        if (Build.VERSION.SDK_INT >= 35) {
            // A recreated Android 15 decor can leave its framework content root
            // fitting the system bars even though the Window is edge-to-edge.
            // Compose owns these insets; only disable the direct framework root,
            // never padding or insets on the app's content views.
            val contentRoot = window.findViewById<View>(android.R.id.content)?.parent as? View
            if (contentRoot?.parent === window.decorView &&
                (contentRoot.fitsSystemWindows || contentRoot.paddingTop != 0 || contentRoot.paddingBottom != 0)) {
                contentRoot.fitsSystemWindows = false
                contentRoot.setPadding(0, 0, 0, 0)
                ViewCompat.requestApplyInsets(window.decorView)
            }
        }
        val barColor = if (splashVisible) Color(0xFF161C20) else background
        applySystemBarAppearance(window, barColor, darkIcons)
    }
}

/** A full-screen Dialog owns a separate Window; Activity appearance does not carry over. */
@Composable
internal fun DialogSystemBarAppearance(background: Color) {
    val view = LocalView.current
    val window = generateSequence(view.parent) { it.parent }
        .filterIsInstance<DialogWindowProvider>().firstOrNull()?.window ?: return
    val darkIcons = background.luminance() > 0.5f
    SideEffect { applySystemBarAppearance(window, background, darkIcons) }
}

@Suppress("DEPRECATION")
private fun applySystemBarAppearance(window: Window, background: Color, darkIcons: Boolean) {
    window.statusBarColor = background.toArgb()
    window.navigationBarColor = background.toArgb()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
    }
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = darkIcons
        isAppearanceLightNavigationBars = darkIcons
    }
}
