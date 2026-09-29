package com.mojing.app.ui.chat

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun StreamingText(text: String) {
    val d = LocalChatDensityMetrics.current
    val displayText = remember(text) { ChatMessageTextFormat.forBubbleDisplay(text) + "▌" }

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
                text = displayText,
                modifier = Modifier.padding(d.bubbleInnerPadding),
                color = MaterialTheme.colorScheme.onSurface,
                style = d.bodyTextStyle()
            )
        }
    }
}
