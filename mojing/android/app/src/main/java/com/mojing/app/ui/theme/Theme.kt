package com.mojing.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    secondary = DarkSecondary,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onBackground = DarkOnBackground,
    onSurface = DarkOnSurface,
    onSurfaceVariant = Color(0xFFB8B8D0),
    error = DarkError,
)

private val LightColorScheme = lightColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    secondary = DarkSecondary,
    background = LightBackground,
    surface = LightSurface,
    surfaceVariant = LightSurfaceVariant,
    onBackground = LightOnBackground,
    onSurface = LightOnSurface,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = Color(0xFF6B6B80),
    error = DarkError,
)

private val MidnightColorScheme = darkColorScheme(
    primary = MidnightPrimary,
    onPrimary = DarkOnPrimary,
    secondary = MidnightSecondary,
    background = MidnightBackground,
    surface = MidnightSurface,
    surfaceVariant = MidnightSurfaceVariant,
    onBackground = DarkOnBackground,
    onSurface = DarkOnSurface,
    onSurfaceVariant = Color(0xFFA8A8C0),
    error = DarkError,
)

private val RoseDarkScheme = darkColorScheme(
    primary = RoseDarkPrimary,
    onPrimary = RoseDarkOnPrimary,
    secondary = RoseDarkSecondary,
    onSecondary = Color(0xFF1A0A18),
    background = RoseDarkBackground,
    onBackground = RoseDarkOnBackground,
    surface = RoseDarkSurface,
    onSurface = RoseDarkOnSurface,
    surfaceVariant = RoseDarkSurfaceVariant,
    onSurfaceVariant = RoseDarkOnSurfaceVariant,
    outline = Color(0xFF6A4A58),
    error = DarkError,
)

private val OceanDarkScheme = darkColorScheme(
    primary = OceanDarkPrimary,
    onPrimary = OceanDarkOnPrimary,
    secondary = OceanDarkSecondary,
    onSecondary = Color(0xFF001820),
    background = OceanDarkBackground,
    onBackground = OceanDarkOnBackground,
    surface = OceanDarkSurface,
    onSurface = OceanDarkOnSurface,
    surfaceVariant = OceanDarkSurfaceVariant,
    onSurfaceVariant = OceanDarkOnSurfaceVariant,
    outline = Color(0xFF4A5F78),
    error = DarkError,
)

private val MintDarkScheme = darkColorScheme(
    primary = MintDarkPrimary,
    onPrimary = MintDarkOnPrimary,
    secondary = MintDarkSecondary,
    onSecondary = Color(0xFF001818),
    background = MintDarkBackground,
    onBackground = MintDarkOnBackground,
    surface = MintDarkSurface,
    onSurface = MintDarkOnSurface,
    surfaceVariant = MintDarkSurfaceVariant,
    onSurfaceVariant = MintDarkOnSurfaceVariant,
    outline = Color(0xFF3D6B5E),
    error = DarkError,
)

private val BlushLightScheme = lightColorScheme(
    primary = BlushLightPrimary,
    onPrimary = BlushLightOnPrimary,
    secondary = BlushLightSecondary,
    onSecondary = Color(0xFFFFFFFF),
    background = BlushLightBackground,
    onBackground = BlushLightOnBackground,
    surface = BlushLightSurface,
    onSurface = BlushLightOnSurface,
    surfaceVariant = BlushLightSurfaceVariant,
    onSurfaceVariant = BlushLightOnSurfaceVariant,
    outline = Color(0xFF8A6672),
    error = DarkError,
)

private val SkyLightScheme = lightColorScheme(
    primary = SkyLightPrimary,
    onPrimary = SkyLightOnPrimary,
    secondary = SkyLightSecondary,
    onSecondary = Color(0xFFFFFFFF),
    background = SkyLightBackground,
    onBackground = SkyLightOnBackground,
    surface = SkyLightSurface,
    onSurface = SkyLightOnSurface,
    surfaceVariant = SkyLightSurfaceVariant,
    onSurfaceVariant = SkyLightOnSurfaceVariant,
    outline = Color(0xFF6B7F95),
    error = DarkError,
)

@Composable
fun MoJingTheme(
    themeMode: String = "dark",
    contentFontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val safeMode = AppThemes.normalize(themeMode)
    val colorScheme = when (safeMode) {
        "light" -> LightColorScheme
        "midnight" -> MidnightColorScheme
        "rose" -> RoseDarkScheme
        "ocean" -> OceanDarkScheme
        "mint" -> MintDarkScheme
        "blush" -> BlushLightScheme
        "sky" -> SkyLightScheme
        else -> DarkColorScheme
    }
    val scale = contentFontScale.coerceIn(0.8f, 1.45f)
    val baseDensity = LocalDensity.current
    val adjustedDensity = remember(baseDensity, scale) {
        Density(
            density = baseDensity.density,
            fontScale = baseDensity.fontScale * scale,
        )
    }
    CompositionLocalProvider(LocalDensity provides adjustedDensity) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            content = content,
        )
    }
}
