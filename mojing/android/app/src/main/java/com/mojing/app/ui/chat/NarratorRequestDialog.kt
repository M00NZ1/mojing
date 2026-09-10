package com.mojing.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mojing.app.ui.common.MoJingButton
import com.mojing.app.ui.common.MoJingTextField

@Composable
internal fun NarratorRequestDialog(
    guidance: String,
    onGuidanceChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onGenerate: (String) -> Unit,
) {
    val direction = guidance.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("生成旁白") },
        text = {
            MoJingTextField(
                value = guidance,
                onValueChange = onGuidanceChange,
                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                label = { Text("剧情方向（可选）") },
                placeholder = { Text("例如：推进到夜晚，让众人发现屋外的异响") },
                minLines = 3,
                maxLines = 6,
            )
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MoJingButton(onClick = { onGenerate(direction) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (direction.isEmpty()) "自动生成旁白" else "按此方向生成")
                }
                if (direction.isNotEmpty()) {
                    TextButton(onClick = { onGenerate("") }, modifier = Modifier.fillMaxWidth()) {
                        Text("不指定方向，自动生成")
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("返回对话") }
            }
        },
    )
}
