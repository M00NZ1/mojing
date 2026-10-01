package com.mojing.app.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

private val LocalMoJingIconSize = compositionLocalOf { 20.dp }

/** Visual icon dimensions are independent from IconButton's 48dp touch target. */
@Composable
fun MoJingIcon(imageVector: ImageVector, contentDescription: String?,
    modifier: Modifier = Modifier.size(LocalMoJingIconSize.current), tint: Color = LocalContentColor.current) {
    Icon(imageVector, contentDescription, modifier, tint)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoJingTopAppBar(title: @Composable () -> Unit, modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {}, actions: @Composable RowScope.() -> Unit = {},
    expandedHeight: androidx.compose.ui.unit.Dp = 52.dp,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(), scrollBehavior: TopAppBarScrollBehavior? = null) {
    CompositionLocalProvider(LocalMoJingIconSize provides 22.dp) {
        TopAppBar(title, modifier, navigationIcon, actions, expandedHeight, windowInsets, colors, scrollBehavior)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoJingCenterAlignedTopAppBar(title: @Composable () -> Unit, modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {}, actions: @Composable RowScope.() -> Unit = {},
    expandedHeight: androidx.compose.ui.unit.Dp = 52.dp,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
    colors: TopAppBarColors = TopAppBarDefaults.centerAlignedTopAppBarColors(), scrollBehavior: TopAppBarScrollBehavior? = null) {
    CompositionLocalProvider(LocalMoJingIconSize provides 22.dp) {
        CenterAlignedTopAppBar(title, modifier, navigationIcon, actions, expandedHeight, windowInsets, colors, scrollBehavior)
    }
}
