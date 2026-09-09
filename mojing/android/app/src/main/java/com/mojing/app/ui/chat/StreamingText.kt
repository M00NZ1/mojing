package com.mojing.app.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@Composable
fun StreamingText(text: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "cursor")
    val d = LocalChatDensityMetrics.current
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursor_alpha"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = d.rowHorizontal, vertical = d.rowVertical)
    ) {
        Surface(
            shape = RoundedCornerShape(d.bubbleCornerInner, d.bubbleCornerOuter, d.bubbleCornerOuter, d.bubbleCornerOuter),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = d.bubbleMaxWidth)
        ) {
            Text(
                text = ChatMessageTextFormat.forBubbleDisplay(text) + "▌",
                modifier = Modifier.padding(d.bubbleInnerPadding),
                color = MaterialTheme.colorScheme.onSurface,
                style = d.bodyTextStyle()
            )
        }
    }
}
