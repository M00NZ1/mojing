package com.mojing.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.saveable.rememberSaveable
import com.mojing.app.ui.theme.SystemBarAppearance
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.mojing.app.data.SecureStorage
import com.mojing.app.ui.navigation.NavGraph
import com.mojing.app.ui.navigation.ExternalNavigationRequest
import com.mojing.app.ui.navigation.ExternalNavigationContract
import com.mojing.app.ui.navigation.parseExternalNavigationTarget
import com.mojing.app.ui.splash.InkBrandSplashOverlay
import com.mojing.app.ui.theme.AppThemes
import com.mojing.app.ui.theme.MoJingTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var secureStorage: SecureStorage

    private var externalNavigationRequest by mutableStateOf<ExternalNavigationRequest?>(null)
    private var nextExternalNavigationRequestId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        queueExternalNavigation(intent)

        val themeMode = AppThemes.normalize(secureStorage.themeMode)
        val initialFontScale = secureStorage.uiFontScale

        setContent {
            var currentTheme by remember { mutableStateOf(themeMode) }
            var currentFontScale by remember { mutableFloatStateOf(initialFontScale) }
            var showInkSplash by rememberSaveable { mutableStateOf(true) }
            BackHandler(enabled = showInkSplash) {
                showInkSplash = false
            }
            MoJingTheme(themeMode = currentTheme, contentFontScale = currentFontScale) {
                SystemBarAppearance(window, MaterialTheme.colorScheme.background, showInkSplash)
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) {
                        NavGraph(
                            externalNavigationRequest = externalNavigationRequest,
                            onExternalNavigationConsumed = ::consumeExternalNavigationRequest,
                            onThemeChanged = { newTheme ->
                                val n = AppThemes.normalize(newTheme)
                                secureStorage.themeMode = n
                                currentTheme = n
                            },
                            onFontScaleChanged = { scale ->
                                currentFontScale = scale
                            },
                        )
                        if (showInkSplash) {
                            InkBrandSplashOverlay(onDismiss = { showInkSplash = false })
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        queueExternalNavigation(intent)
    }

    private fun queueExternalNavigation(intent: Intent?) {
        val deepLinkHost = intent?.data
            ?.takeIf { it.scheme.equals("mojing", ignoreCase = true) }
            ?.host
        val sessionId = intent
            ?.takeIf { it.hasExtra(ExternalNavigationContract.EXTRA_SESSION_ID) }
            ?.getLongExtra(ExternalNavigationContract.EXTRA_SESSION_ID, -1L)
        val target = parseExternalNavigationTarget(
            deepLinkHost = deepLinkHost,
            navigateTo = intent?.getStringExtra(ExternalNavigationContract.EXTRA_NAVIGATE_TO),
            sessionId = sessionId,
        ) ?: return
        nextExternalNavigationRequestId += 1L
        externalNavigationRequest = ExternalNavigationRequest(nextExternalNavigationRequestId, target)
    }

    private fun consumeExternalNavigationRequest(requestId: Long) {
        if (externalNavigationRequest?.id != requestId) return
        externalNavigationRequest = null
        intent?.apply {
            data = null
            removeExtra(ExternalNavigationContract.EXTRA_NAVIGATE_TO)
            removeExtra(ExternalNavigationContract.EXTRA_SESSION_ID)
        }
    }

}
