package com.mojing.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import com.mojing.app.ui.theme.MoJingTheme
import com.mojing.app.ui.theme.SystemBarAppearance
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SystemBarAppearanceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun splashExitAndThemeChangesUpdateBothSystemBars() {
        val theme = mutableStateOf("light")
        val splash = mutableStateOf(true)
        rule.setContent {
            MoJingTheme(themeMode = theme.value) {
                SystemBarAppearance(rule.activity.window, MaterialTheme.colorScheme.background, splash.value)
            }
        }
        fun assertDarkIcons(expected: Boolean) = rule.runOnIdle {
            val window = rule.activity.window
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            assertEquals(expected, controller.isAppearanceLightStatusBars)
            assertEquals(expected, controller.isAppearanceLightNavigationBars)
        }
        assertDarkIcons(false)
        rule.runOnIdle { splash.value = false }
        assertDarkIcons(true)
        for (mode in listOf("dark", "midnight", "rose", "ocean", "mint", "blush", "sky", "light")) {
            rule.runOnIdle { theme.value = mode }
            assertDarkIcons(mode in setOf("light", "blush", "sky"))
        }
    }
}
