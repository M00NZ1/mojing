package com.mojing.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Exact light-palette values from ANDROID_VISUAL_STANDARD_SOURCE.md; custom palettes remain selectable. */
object MoJingDesignTokens {
    val primary = Color(0xFF2D5569)
    val primaryPressed = Color(0xFF234555)
    val primaryLight = Color(0xFFEAF1F4)
    val primaryLighter = Color(0xFFF2F6F8)
    val background = Color(0xFFF9FAFA)
    val surface = Color.White
    val surfaceSecondary = Color(0xFFEDF1F5)
    val surfaceHover = Color(0xFFF4F6F7)
    val textPrimary = Color(0xFF212224)
    val textSecondary = Color(0xFF5A5E62)
    val textTertiary = Color(0xFF9DA3AA)
    val textDisabled = Color(0xFFB8BDC2)
    val border = Color(0xFFD4DADE)
    val borderLight = Color(0xFFE5E7E9)
    val borderStrong = Color(0xFFBBC4CB)
    val iconSecondary = Color(0xFF677886)
    val navUnselected = Color(0xFF758895)
    val success = Color(0xFF3D816C)
    val successBackground = Color(0xFFE8F4EF)
    val warning = Color(0xFFA87546)
    val warningBackground = Color(0xFFF8F0E7)
    val danger = Color(0xFFB84D4D)
    // Normal-size danger text must meet 4.5:1 on its light container; keep the standard accent.
    val dangerText = Color(0xFF7D222B)
    val dangerBackground = Color(0xFFFBECEC)
    val dangerBorder = Color(0xFFE7BABA)
    val disabledBackground = Color(0xFFD9DEE1)
    val scrim = Color(0xFF101820).copy(alpha = 0.42f)
    val readerText = Color(0xFF292B2D)
    val generating = Color(0xFF2D6F8A)
    val chipBackground = Color(0xFFF2F5F6)
    val pagePadding = 16.dp
    val cardGap = 12.dp
    val cardRadius = 10.dp
    val panelRadius = 12.dp
    val dialogRadius = 16.dp
    val sheetRadius = 20.dp
    val inputVisualHeight = 44.dp
    val touchTarget = 48.dp
    val topBarHeight = 52.dp
    val bottomNavHeight = 56.dp
    val readerMaxWidth = 680.dp
}
