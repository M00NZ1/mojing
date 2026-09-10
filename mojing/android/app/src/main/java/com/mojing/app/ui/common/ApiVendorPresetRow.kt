package com.mojing.app.ui.common

import com.mojing.app.ui.common.MoJingTextField as OutlinedTextField

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

enum class ApiVendorModelHint {
    /** 使用 [ApiProviderLine.suggestedModels] */
    CHAT,
    /** 使用 [ApiProviderLine.imageSuggestedModels] */
    IMAGE,
    /** 使用 [ApiProviderLine.voiceSuggestedModels] */
    VOICE_TTS,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiVendorPresetRow(
    sectionLabel: String,
    currentBaseUrl: String,
    currentModel: String,
    onBaseUrlChange: (String) -> Unit,
    onModelChange: ((String) -> Unit)?,
    modelHint: ApiVendorModelHint = ApiVendorModelHint.CHAT,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val presetLines = remember(modelHint) { ApiProviderPresets.linesFor(modelHint) }
    val display = ApiProviderPresets.labelForBaseUrl(currentBaseUrl)
    Column(modifier = modifier.fillMaxWidth()) col@{
        Text(
            sectionLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        ExposedDropdownMenuBox(
            expanded = expanded && enabled,
            onExpandedChange = { if (enabled) expanded = !expanded },
        ) {
            OutlinedTextField(
                modifier = Modifier.menuAnchor().fillMaxWidth(),
                readOnly = true,
                enabled = enabled,
                value = display,
                onValueChange = {},
                label = { Text("常用线路") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.fillMaxWidth(),
            ) {
                val imageSection = modelHint == ApiVendorModelHint.IMAGE
                val voiceSection = modelHint == ApiVendorModelHint.VOICE_TTS
                presetLines.forEach { line ->
                    DropdownMenuItem(
                        text = {
                            Column(Modifier.fillMaxWidth()) {
                                Text(line.label, style = MaterialTheme.typography.bodyLarge)
                                if (imageSection && line.id == "volcengine") {
                                    Text(
                                        "生图模型见下方（如 doubao-seedream-5.0-lite）",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (voiceSection && line.id == "fish_audio") {
                                    Text(
                                        "模型填 s2-pro；音色 ID 填下方「TTS 音色 voice」作 reference_id",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (line.baseUrl.isNotBlank()) {
                                    Text(
                                        line.baseUrl,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    Text(
                                        "不预填，请在下方手填 URL",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        onClick = {
                            expanded = false
                            if (line.id == "custom") return@DropdownMenuItem
                            onBaseUrlChange(line.baseUrl)
                            val m = onModelChange ?: return@DropdownMenuItem
                            if (currentModel.isNotBlank()) return@DropdownMenuItem
                            val hints = when (modelHint) {
                                ApiVendorModelHint.CHAT -> line.suggestedModels
                                ApiVendorModelHint.IMAGE -> line.imageSuggestedModels
                                ApiVendorModelHint.VOICE_TTS -> line.voiceSuggestedModels
                            }
                            if (hints.isNotEmpty()) m(hints.first())
                        },
                    )
                }
            }
        }
        val matchedLine = presetLines.find { preset ->
            preset.baseUrl.isNotBlank() && preset.baseUrl.equals(currentBaseUrl.trim(), ignoreCase = true)
        } ?: ApiProviderPresets.LINES.find { preset ->
            preset.baseUrl.isNotBlank() && preset.baseUrl.equals(currentBaseUrl.trim(), ignoreCase = true)
        }
        val hintModels: List<String> = when (modelHint) {
            ApiVendorModelHint.CHAT -> matchedLine?.suggestedModels.orEmpty()
            ApiVendorModelHint.IMAGE -> matchedLine?.imageSuggestedModels.orEmpty()
            ApiVendorModelHint.VOICE_TTS -> matchedLine?.voiceSuggestedModels.orEmpty()
        }
        val showModelChips = onModelChange != null &&
            hintModels.isNotEmpty() &&
            (currentModel.isBlank() ||
                hintModels.none { it.equals(currentModel.trim(), ignoreCase = true) })
        if (showModelChips) {
            val om = onModelChange ?: return@col
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 4.dp),
            ) {
                hintModels.take(10).forEach { mid ->
                    AssistChip(
                        onClick = { om(mid) },
                        label = {
                            Text(
                                mid,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }
        }
    }
}

/**
 * 当根地址与某一快捷预设完全一致且无多网关时，可收起与 [ApiVendorPresetRow] 重复的根地址 / 模型手填框。
 * @param baseMultiline 全局设置用 `true`（多行备选）；角色页对话根一般为单行用 `false`。
 */
@Composable
fun CollapsiblePresetUrlModelBlock(
    collapsedPreset: Boolean,
    showManualFields: Boolean,
    onExpandManual: () -> Unit,
    onCollapseManual: () -> Unit,
    baseUrl: String,
    model: String,
    onBaseChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    baseLabel: String,
    basePlaceholder: String,
    modelLabel: String,
    modelPlaceholder: String,
    baseMultiline: Boolean = true,
    modifier: Modifier = Modifier,
    fieldsEnabled: Boolean = true,
) {
    if (collapsedPreset && !showManualFields) {
        Card(
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
            ),
        ) {
            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("URL 与模型", style = MaterialTheme.typography.labelMedium)
                Text(
                    baseUrl,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = if (baseMultiline) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (model.isBlank()) "模型：（未填）" else "模型：$model",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onExpandManual, enabled = fieldsEnabled, modifier = Modifier.align(Alignment.End)) {
                    Text("展开编辑")
                }
            }
        }
    } else {
        OutlinedTextField(
            value = baseUrl,
            onValueChange = onBaseChange,
            label = { Text(baseLabel) },
            placeholder = { Text(basePlaceholder) },
            modifier = Modifier.fillMaxWidth(),
            enabled = fieldsEnabled,
            singleLine = !baseMultiline,
            minLines = if (baseMultiline) 2 else 1,
        )
        OutlinedTextField(
            value = model,
            onValueChange = onModelChange,
            label = { Text(modelLabel) },
            placeholder = { Text(modelPlaceholder) },
            modifier = Modifier.fillMaxWidth(),
            enabled = fieldsEnabled,
            singleLine = true,
        )
        if (collapsedPreset && showManualFields) {
            TextButton(onClick = onCollapseManual, enabled = fieldsEnabled) {
                Text("收起")
            }
        }
    }
}
