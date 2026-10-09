package com.mojing.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mojing.app.ui.common.MoJingButton
import com.mojing.app.ui.common.MoJingWritingField

@Composable
internal fun NarratorRequestDialog(
    guidance: String,
    onGuidanceChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onGenerate: (String) -> Unit,
) {
    val direction = guidance.trim()
    ChatPromptSheet(
        onDismiss = onDismiss,
        title = "生成旁白",
        editor = {
            MoJingWritingField(
                value = guidance,
                onValueChange = onGuidanceChange,
                modifier = Modifier.fillMaxWidth().weight(1f),
                label = "剧情方向（可选）",
                placeholder = "剧情方向（可选），例如：推进到夜晚，屋外传来异响",
            )
        },
        actions = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MoJingButton(onClick = { onGenerate(direction) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (direction.isEmpty()) "自动生成旁白" else "按此方向生成")
                }
                if (direction.isNotEmpty()) {
                    TextButton(onClick = { onGenerate("") }, modifier = Modifier.fillMaxWidth()) {
                        Text("不指定方向，自动生成")
                    }
                }
            }
        },
    )
}
