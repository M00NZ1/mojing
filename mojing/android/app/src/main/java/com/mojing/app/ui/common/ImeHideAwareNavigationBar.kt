package com.mojing.app.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Density
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.SoftwareKeyboardController

/**
 * 主界面底部导航：键盘（IME）弹起时收起底栏，避免整条 NavigationBar 被顶到键盘上方；
 * 与页面主体上的 [androidx.compose.foundation.layout.imePadding] 配合，表单区仍可滚到可见区域。
 */
@Composable
fun ImeHideAwareNavigationBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    val density = LocalDensity.current
    val imeOpen = isImeKeyboardOpen(density)
    AnimatedVisibility(
        visible = !imeOpen,
        modifier = modifier,
        enter = fadeIn(animationSpec = tween(180)) + expandVertically(
            animationSpec = tween(180),
            expandFrom = Alignment.Bottom
        ),
        exit = fadeOut(animationSpec = tween(140)) + shrinkVertically(
            animationSpec = tween(140),
            shrinkTowards = Alignment.Bottom
        )
    ) {
        NavigationBar(
            modifier = Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp).clip(MaterialTheme.shapes.large),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            windowInsets = WindowInsets(0, 0, 0, 0),
            tonalElevation = 0.dp,
            content = content)
    }
}

@Composable
fun isImeKeyboardOpen(): Boolean {
    val density = LocalDensity.current
    return isImeKeyboardOpen(density)
}

@Composable
private fun isImeKeyboardOpen(density: Density): Boolean =
    WindowInsets.ime.getBottom(density) > WindowInsets.navigationBars.getBottom(density)

fun hideImeKeyboard(
    keyboardController: SoftwareKeyboardController?,
    focusManager: FocusManager,
) {
    keyboardController?.hide()
    focusManager.clearFocus(force = true)
}
