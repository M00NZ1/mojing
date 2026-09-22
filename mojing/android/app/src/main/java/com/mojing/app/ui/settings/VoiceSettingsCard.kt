package com.mojing.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
    onChoiceSelected: (VoiceChoice, () -> Unit) -> Unit,
    onRegionChange: (String) -> Unit,
    onKeyChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    var pickerVisible by remember { mutableStateOf(false) }
    // Reset the default when the selected engine changes. A manual collapse is
    // retained while the same engine remains selected, but returning to Azure
    // makes its required connection section visible again.
    var azureExpanded by remember(choice.engineId) { mutableStateOf(choice.engineId == "azure") }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("朗读与语音", style = MaterialTheme.typography.titleMedium)
                Text(
                    "作为默认朗读引擎与音色；角色也可以单独设置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("当前朗读", style = MaterialTheme.typography.labelLarge)
                    Text(
                        choice.label(),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Button(onClick = { pickerVisible = true }, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                        Text("选择引擎与音色")
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("微软 Azure 连接", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (choice.engineId == "azure") {
                        "当前引擎需要 Azure 区域和 Speech Key。"
                    } else {
                        "当前未使用 Azure；如需切换，可先在这里准备连接信息。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { azureExpanded = !azureExpanded }, enabled = !saving) {
                    Text(if (azureExpanded) "收起连接设置" else "查看连接设置")
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Button(onClick = onSave, enabled = !saving) {
                            Text(if (saving) "保存中…" else "保存 Azure 设置")
                        }
                    }
                }
            }
            error?.let {
                Text(
                    it,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    if (pickerVisible) {
        VoiceChoicePicker(
            choice = choice,
            allowInherit = false,
            saving = saving,
            saveError = error,
            onSelected = { selected -> onChoiceSelected(selected) { pickerVisible = false } },
            onDismiss = { pickerVisible = false },
        )
    }
}
