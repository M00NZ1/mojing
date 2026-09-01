package com.mojing.app.ui.splash

import android.app.Activity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.mojing.app.R
import com.mojing.app.ui.theme.MoJingTheme
import kotlinx.coroutines.delay

private val InkTop = Color(0xFF050507)
private val InkMid = Color(0xFF0B0B10)
private val InkDeep = Color(0xFF101018)
private val InkFloor = Color(0xFF141210)
private val RicePaper = Color(0xFFEAE6DC)
private val RicePaperMuted = Color(0xFFB8B2A8)
private val SealLine = Color(0xFF6B5344)

@Composable
private fun InkBrandSplashBackdrop(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to InkTop,
                            0.38f to InkMid,
                            0.78f to InkDeep,
                            1f to InkFloor,
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color.Transparent, Color(0x66000000)),
                        center = Offset(w * 0.5f, h * 0.42f),
                        radius = maxOf(w, h) * 0.72f,
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(Color(0x08F5F0E6), Color.Transparent, Color(0x061A1510)),
                        start = Offset(0f, 0f),
                        end = Offset(w, h * 0.35f),
                    ),
                ),
        )
    }
}

@Composable
private fun InkBrandSplashTextBlock(
    titleAlpha: Float,
    lineFrac: Float,
    taglineAlpha: Float,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            modifier = Modifier.alpha(titleAlpha),
            color = RicePaper,
            fontSize = 40.sp,
            fontWeight = FontWeight.W300,
            letterSpacing = 0.35.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        HorizontalDivider(
            modifier = Modifier
                .width((120f * lineFrac).dp)
                .alpha(0.88f),
            thickness = 0.5.dp,
            color = SealLine.copy(alpha = 0.88f),
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.splash_tagline),
            modifier = Modifier
                .alpha(taglineAlpha)
                .fillMaxWidth(),
            color = RicePaperMuted.copy(alpha = 0.92f),
            fontSize = 17.sp,
            fontWeight = FontWeight.W300,
            lineHeight = 30.sp,
            letterSpacing = 0.12.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 冷启动品牌开屏：与 [themes.xml] 中系统 Splash 底色对齐，再以动效展示文案。
 * 点击屏幕可立即结束；自动播放节奏较默认更快。
 */
@Composable
fun InkBrandSplashOverlay(onDismiss: () -> Unit) {
    val view = LocalView.current
    val inspection = LocalInspectionMode.current
    val titleAlpha = remember { Animatable(if (inspection) 1f else 0f) }
    val lineFrac = remember { Animatable(if (inspection) 1f else 0f) }
    val taglineAlpha = remember { Animatable(if (inspection) 1f else 0f) }
    val screenAlpha = remember { Animatable(1f) }
    var dismissToken by remember { mutableIntStateOf(0) }

    LaunchedEffect(dismissToken, inspection) {
        if (inspection) return@LaunchedEffect
        if (dismissToken > 0) {
            titleAlpha.snapTo(1f)
            lineFrac.snapTo(1f)
            taglineAlpha.snapTo(1f)
            screenAlpha.animateTo(0f, tween(260, easing = FastOutSlowInEasing))
            onDismiss()
            return@LaunchedEffect
        }
        val window = (view.context as? Activity)?.window
        if (window != null) {
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
        titleAlpha.animateTo(1f, tween(480, easing = FastOutSlowInEasing))
        delay(120)
        lineFrac.animateTo(1f, tween(560, easing = FastOutSlowInEasing))
        delay(80)
        taglineAlpha.animateTo(1f, tween(560, easing = FastOutSlowInEasing))
        delay(900)
        screenAlpha.animateTo(0f, tween(360, easing = FastOutSlowInEasing))
        onDismiss()
    }

    val tapSkip = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(screenAlpha.value)
            .clickable(
                interactionSource = tapSkip,
                indication = null,
                enabled = !inspection,
            ) {
                if (dismissToken == 0) dismissToken++
            },
    ) {
        InkBrandSplashBackdrop()
        InkBrandSplashTextBlock(
            titleAlpha = titleAlpha.value,
            lineFrac = lineFrac.value,
            taglineAlpha = taglineAlpha.value,
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun InkBrandSplashPreview() {
    MoJingTheme(themeMode = "dark") {
        Box(Modifier.fillMaxSize()) {
            InkBrandSplashOverlay(onDismiss = {})
        }
    }
}
