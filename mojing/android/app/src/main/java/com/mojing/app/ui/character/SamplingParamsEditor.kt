package com.mojing.app.ui.character

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SamplingParamsEditor(
    temperature: Float, topP: Float,
    presencePenalty: Float, frequencyPenalty: Float,
    maxTokens: Int,
    onTemperatureChange: (Float) -> Unit,
    onTopPChange: (Float) -> Unit,
    onPresencePenaltyChange: (Float) -> Unit,
    onFrequencyPenaltyChange: (Float) -> Unit,
    onMaxTokensChange: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("采样参数", style = MaterialTheme.typography.titleMedium)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = temperature.toString(), onValueChange = { it.toFloatOrNull()?.let(onTemperatureChange) }, label = { Text("温度") }, modifier = Modifier.weight(1f), singleLine = true)
            OutlinedTextField(value = topP.toString(), onValueChange = { it.toFloatOrNull()?.let(onTopPChange) }, label = { Text("Top P") }, modifier = Modifier.weight(1f), singleLine = true)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = presencePenalty.toString(), onValueChange = { it.toFloatOrNull()?.let(onPresencePenaltyChange) }, label = { Text("存在惩罚") }, modifier = Modifier.weight(1f), singleLine = true)
            OutlinedTextField(value = frequencyPenalty.toString(), onValueChange = { it.toFloatOrNull()?.let(onFrequencyPenaltyChange) }, label = { Text("频率惩罚") }, modifier = Modifier.weight(1f), singleLine = true)
        }
        OutlinedTextField(value = maxTokens.toString(), onValueChange = { it.toIntOrNull()?.let(onMaxTokensChange) }, label = { Text("最大Token") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    }
}
