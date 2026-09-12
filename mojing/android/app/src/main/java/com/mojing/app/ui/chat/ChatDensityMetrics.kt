package com.mojing.app.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ChatDensityMode {
    Comfortable,
    Compact,
    Reader,
    ;

    companion object {
        fun fromStorage(raw: String?): ChatDensityMode = when (raw?.trim()) {
            "compact" -> Compact
            "reader" -> Reader
            else -> Comfortable
        }
    }
}

@Immutable
data class ChatDensityMetrics(
    val listContentVertical: Dp,
    val rowHorizontal: Dp,
    val rowVertical: Dp,
    val bubbleInnerPadding: Dp,
    val bubbleMaxWidth: Dp,
    val bodyFontSp: Float,
    val bubbleCornerOuter: Dp,
    val bubbleCornerInner: Dp,
    val narratorHorizontal: Dp,
    val narratorVertical: Dp,
    val narratorInnerPadding: Dp,
    val pagerLabelTop: Dp,
    val pagerLabelBottom: Dp,
)

fun ChatDensityMode.toMetrics(): ChatDensityMetrics = when (this) {
    ChatDensityMode.Comfortable -> ChatDensityMetrics(
        listContentVertical = 8.dp,
        rowHorizontal = 16.dp,
        rowVertical = 4.dp,
        bubbleInnerPadding = 12.dp,
        bubbleMaxWidth = 300.dp,
        bodyFontSp = 16f,
        bubbleCornerOuter = 16.dp,
        bubbleCornerInner = 4.dp,
        narratorHorizontal = 24.dp,
        narratorVertical = 6.dp,
        narratorInnerPadding = 14.dp,
        pagerLabelTop = 2.dp,
        pagerLabelBottom = 4.dp,
    )
    ChatDensityMode.Compact -> ChatDensityMetrics(
        listContentVertical = 4.dp,
        rowHorizontal = 10.dp,
        rowVertical = 2.dp,
        bubbleInnerPadding = 8.dp,
        bubbleMaxWidth = 300.dp,
        bodyFontSp = 14f,
        bubbleCornerOuter = 12.dp,
        bubbleCornerInner = 3.dp,
        narratorHorizontal = 14.dp,
        narratorVertical = 4.dp,
        narratorInnerPadding = 10.dp,
        pagerLabelTop = 1.dp,
        pagerLabelBottom = 2.dp,
    )
    ChatDensityMode.Reader -> ChatDensityMetrics(
        listContentVertical = 12.dp,
        rowHorizontal = 18.dp,
        rowVertical = 6.dp,
        bubbleInnerPadding = 14.dp,
        bubbleMaxWidth = 340.dp,
        bodyFontSp = 17f,
        bubbleCornerOuter = 18.dp,
        bubbleCornerInner = 5.dp,
        narratorHorizontal = 28.dp,
        narratorVertical = 8.dp,
        narratorInnerPadding = 16.dp,
        pagerLabelTop = 4.dp,
        pagerLabelBottom = 6.dp,
    )
}

val LocalChatDensityMetrics = compositionLocalOf { ChatDensityMode.Comfortable.toMetrics() }

@Composable
@ReadOnlyComposable
fun ChatDensityMetrics.bodyTextStyle(): TextStyle =
    MaterialTheme.typography.bodyLarge.copy(fontSize = bodyFontSp.sp, fontFamily = LocalChatReadingStyle.current.fontFamily)

@Composable
@ReadOnlyComposable
fun ChatDensityMetrics.narrationTextStyle(): TextStyle =
    MaterialTheme.typography.bodyMedium.copy(
        fontSize = (bodyFontSp - 1f).coerceAtLeast(12f).sp,
        fontFamily = LocalChatReadingStyle.current.fontFamily,
        fontStyle = LocalChatReadingStyle.current.narratorFontStyle,
    )
