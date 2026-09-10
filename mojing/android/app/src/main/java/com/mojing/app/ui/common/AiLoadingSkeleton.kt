package com.mojing.app.ui.common

import androidx.compose.material3.TextButton

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun PulsingSkeletonBar(
    modifier: Modifier = Modifier,
    height: Dp = 16.dp,
) {
    val transition = rememberInfiniteTransition(label = "sk")
    val alpha by transition.animateFloat(
        initialValue = 0.32f,
        targetValue = 0.78f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "a",
    )
    Surface(
        modifier = modifier
            .height(height)
            .fillMaxWidth(),
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha),
    ) {}
}

/** 百科条目「AI 补全」：摘要 / 正文 / 标签区域占位。 */
@Composable
fun EntryAiCompleteSkeletonBlock(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        ),
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    "正在调用模型，补全摘要与正文…",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            PulsingSkeletonBar(Modifier.fillMaxWidth(0.92f), 18.dp)
            PulsingSkeletonBar(Modifier.fillMaxWidth(0.68f), 18.dp)
            Spacer(Modifier.height(4.dp))
            PulsingSkeletonBar(Modifier.fillMaxWidth(), 88.dp)
            Spacer(Modifier.height(4.dp))
            PulsingSkeletonBar(Modifier.fillMaxWidth(0.45f), 16.dp)
        }
    }
}

/** 模板「补全世界书」队列进行中：摘要 / 世界书区域占位。 */
@Composable
fun TemplateWorldPromptSkeletonBlock(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        ),
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    "世界书补全任务进行中…",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            PulsingSkeletonBar(Modifier.fillMaxWidth(0.9f), 20.dp)
            PulsingSkeletonBar(Modifier.fillMaxWidth(0.55f), 20.dp)
            Spacer(Modifier.height(6.dp))
            PulsingSkeletonBar(Modifier.fillMaxWidth(), 96.dp)
            PulsingSkeletonBar(Modifier.fillMaxWidth(0.85f), 72.dp)
            Spacer(Modifier.height(4.dp))
            PulsingSkeletonBar(Modifier.fillMaxWidth(0.7f), 18.dp)
        }
    }
}

/** 未配置公共 LLM Key 时的顶部提示条（与工坊等页一致）。 */
@Composable
fun LlmKeySetupHintCard(
    message: String,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    showActionButton: Boolean = true,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
        ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
            if (showActionButton) {
                TextButton(
                    onClick = onOpenSettings,
                ) {
                    Text("去设置")
                }
            }
        }
    }
}
