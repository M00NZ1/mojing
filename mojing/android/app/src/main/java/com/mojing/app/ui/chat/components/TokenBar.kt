package com.mojing.app.ui.chat.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 聊天顶栏下的轻量条：只估算当前已加载消息窗口，避免为展示统计扫描整段大历史。
 * 该数值不参与 API 截断。
 */
@Composable
fun ChatContextUsageStrip(
    estimateTokens: Int,
    displayLimit: Int,
    modifier: Modifier = Modifier,
) {
    val cap = displayLimit.coerceAtLeast(1)
    val ratio = (estimateTokens.toFloat() / cap).coerceIn(0f, 1f)
    val color = when {
        ratio > 0.9f -> Color(0xFFFF6B6B)
        ratio > 0.7f -> Color(0xFFFF9800)
        else -> MaterialTheme.colorScheme.primary
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "当前已加载约 $estimateTokens / $cap tokens（估算·仅展示）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${(ratio * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
        LinearProgressIndicator(
            progress = { ratio },
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .height(3.dp)
                .clip(MaterialTheme.shapes.small),
        )
    }
}
