package com.mojing.app.ui.character

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun VoiceConfigEditor(
    voiceProvider: String, voiceModel: String, voiceApiKey: String,
    onProviderChange: (String) -> Unit, onModelChange: (String) -> Unit, onApiKeyChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("朗读配置", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = voiceProvider, onValueChange = onProviderChange, label = { Text("语音服务商") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(value = voiceModel, onValueChange = onModelChange, label = { Text("语音模型") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(value = voiceApiKey, onValueChange = onApiKeyChange, label = { Text("朗读 API Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    }
}
