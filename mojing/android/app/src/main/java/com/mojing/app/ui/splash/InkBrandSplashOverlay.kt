package com.mojing.app.ui.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mojing.app.R
import com.mojing.app.ui.theme.MoJingTheme
import kotlinx.coroutines.delay

private val InkTop = Color(0xFF050507)
private val InkMid = Color(0xFF101B18)
private val InkDeep = Color(0xFF122720)
private val InkFloor = Color(0xFF0F1916)
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
        Box(Modifier.align(Alignment.Center).size(250.dp).blur(64.dp, BlurredEdgeTreatment.Unbounded)
            .background(Color(0x2291D4BF), CircleShape))
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
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null,
            modifier = Modifier.size(144.dp).alpha(titleAlpha).graphicsLayer {
                translationY = (1f - titleAlpha) * 20.dp.toPx()
            })
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.app_name),
            modifier = Modifier.alpha(titleAlpha),
            color = RicePaper,
            fontSize = 34.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 5.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        HorizontalDivider(
            modifier = Modifier
                .width((40f * lineFrac).dp)
                .alpha(0.88f),
            thickness = 0.5.dp,
            color = Color(0xFF91D4BF).copy(alpha = 0.5f),
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.splash_tagline),
            modifier = Modifier
                .alpha(taglineAlpha)
                .fillMaxWidth(),
            color = RicePaperMuted.copy(alpha = 0.92f),
            fontSize = 15.sp,
            fontWeight = FontWeight.Normal,
            lineHeight = 28.sp,
            letterSpacing = 1.2.sp,
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
        titleAlpha.animateTo(1f, tween(360, easing = FastOutSlowInEasing))
        lineFrac.animateTo(1f, tween(240, easing = FastOutSlowInEasing))
        taglineAlpha.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
        delay(650)
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
