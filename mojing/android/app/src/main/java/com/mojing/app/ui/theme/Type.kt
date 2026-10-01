package com.mojing.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

// UI is always sans serif; reader-specific user font settings remain separate.
private fun uiText(size: Int, leading: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = FontFamily.SansSerif, fontSize = size.sp, lineHeight = leading.sp, fontWeight = weight)

val AppTypography = Typography(
    displayLarge = uiText(32, 42, FontWeight.SemiBold),
    displayMedium = uiText(32, 42, FontWeight.SemiBold),
    displaySmall = uiText(32, 42, FontWeight.SemiBold),
    headlineLarge = uiText(24, 32, FontWeight.SemiBold),
    headlineMedium = uiText(20, 28, FontWeight.SemiBold),
    headlineSmall = uiText(18, 26, FontWeight.SemiBold),
    titleLarge = uiText(18, 26, FontWeight.SemiBold),
    titleMedium = uiText(16, 24, FontWeight.SemiBold),
    titleSmall = uiText(16, 24, FontWeight.SemiBold),
    bodyLarge = uiText(16, 26),
    bodyMedium = uiText(15, 24),
    bodySmall = uiText(14, 21),
    labelLarge = uiText(15, 20, FontWeight.Medium),
    labelMedium = uiText(13, 18, FontWeight.Medium),
    labelSmall = uiText(12, 18),
)
