package com.mojing.app.ui.theme

import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat

@Composable
internal fun SystemBarAppearance(window: Window, background: Color, splashVisible: Boolean) {
    val darkIcons = !splashVisible && background.luminance() > 0.5f
    SideEffect {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = darkIcons
            isAppearanceLightNavigationBars = darkIcons
        }
    }
}
