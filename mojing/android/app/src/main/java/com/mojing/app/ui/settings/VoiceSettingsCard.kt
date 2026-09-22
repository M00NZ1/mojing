package com.mojing.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mojing.app.data.VoiceChoice
import com.mojing.app.ui.common.MoJingTextField
import com.mojing.app.ui.common.VoiceChoicePicker

@Composable
fun VoiceSettingsCard(
    choice: VoiceChoice,
    azureRegion: String,
    azureKey: String,
    saving: Boolean,
    error: String?,
    onChoiceSelected: (VoiceChoice) -> Unit,
    onRegionChange: (String) -> Unit,
    onKeyChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    var pickerVisible by remember { mutableStateOf(false) }
    var azureExpanded by remember { mutableStateOf(choice.engineId == "azure") }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("朗读与语音", style = MaterialTheme.typography.titleMedium)
            Text("选择本机或外部引擎；Azure 音色需要区域和 API Key。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { pickerVisible = true }, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                Text(choice.label())
            }
            TextButton(onClick = { azureExpanded = !azureExpanded }, enabled = !saving) {
                Text(if (azureExpanded) "收起微软 Azure 连接" else "配置微软 Azure 连接")
            }
            if (azureExpanded) {
                MoJingTextField(
                    value = azureRegion,
                    onValueChange = onRegionChange,
                    label = { Text("Azure 区域") },
                    placeholder = { Text("如 eastasia") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !saving,
                )
                MoJingTextField(
                    value = azureKey,
                    onValueChange = onKeyChange,
                    label = { Text("Azure Speech API Key") },
                    placeholder = { Text("输入后保存") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !saving,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onClick = onSave, enabled = !saving) { Text(if (saving) "保存中…" else "保存 Azure 设置") }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (pickerVisible && !saving) {
        VoiceChoicePicker(
            choice = choice,
            allowInherit = false,
            onSelected = { selected -> pickerVisible = false; onChoiceSelected(selected) },
            onDismiss = { pickerVisible = false },
        )
    }
}
