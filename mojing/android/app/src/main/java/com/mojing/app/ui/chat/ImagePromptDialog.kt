package com.mojing.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mojing.app.ui.common.MoJingButton
import com.mojing.app.ui.common.MoJingTextField

@Composable
internal fun ImagePromptDialog(
    prompt: String,
    onPromptChange: (String) -> Unit,
    busy: Boolean,
    onDismiss: () -> Unit,
    onGenerate: () -> Unit,
) {
    ChatPromptSheet(
        onDismiss = onDismiss,
        title = "生成配图",
        editor = {
            MoJingTextField(
                value = prompt,
                onValueChange = onPromptChange,
                label = { Text("画面描述") },
                placeholder = { Text("描述人物、场景、光线与画面风格") },
                modifier = Modifier.fillMaxWidth().weight(1f),
                minLines = 3,
            )
        },
        actions = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MoJingButton(onClick = onGenerate, enabled = prompt.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "正在生成，请稍候" else "生成并加入对话")
                }
            }
        },
    )
}
