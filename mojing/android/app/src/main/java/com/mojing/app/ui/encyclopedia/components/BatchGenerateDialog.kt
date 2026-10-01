package com.mojing.app.ui.encyclopedia.components

import com.mojing.app.ui.common.MoJingFilterChip as FilterChip
import com.mojing.app.ui.common.MoJingTextField as OutlinedTextField
import com.mojing.app.ui.common.MoJingButton as Button

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BatchGenerateDialog(
    worldAnchorReady: Boolean,
    onDismiss: () -> Unit,
    onGenerate: (
        type: String,
        count: Int,
        minWords: Int,
        maxWords: Int,
        contextPrompt: String,
        /** 生成前用户补充（非必填），会并入传给模型的说明 */
        preGenNotes: String,
        runInBackground: Boolean,
        outputMode: String,
    ) -> Unit,
) {
    /** `entries`：写入百科条目表；`timeline`：写入时间线事件表 */
    var outputMode by remember { mutableStateOf("entries") }
    var selectedType by remember { mutableStateOf("character") }
    var countText by remember { mutableStateOf("1") }
    var minWordsText by remember { mutableStateOf("200") }
    var maxWordsText by remember { mutableStateOf("800") }
    var contextPrompt by remember { mutableStateOf("") }
    var preGenNotes by remember { mutableStateOf("") }
    var runInBackground by remember { mutableStateOf(true) }
    val countValue = countText.toIntOrNull()
    val minWordsValue = minWordsText.toIntOrNull()
    val maxWordsValue = maxWordsText.toIntOrNull()
    val inputValid = countValue in 1..200 && minWordsValue != null && maxWordsValue != null &&
        minWordsValue >= 1 && maxWordsValue >= minWordsValue

    val typeLabels = listOf(
        "world" to "世界",
        "character" to "角色", "faction" to "阵营", "location" to "地点",
        "item" to "物品", "event" to "事件", "skill" to "技能",
        "creature" to "生物", "profession" to "职业", "concept" to "概念",
    )

    AlertDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        onDismissRequest = onDismiss,
        title = { Text("批量生成（条目 / 时间线）") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!worldAnchorReady) {
                    Text(
                        "建议先填写百科简介",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                Text("写入目标", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = outputMode == "entries",
                        onClick = { outputMode = "entries" },
                        label = { Text("百科条目") },
                    )
                    FilterChip(
                        selected = outputMode == "timeline",
                        onClick = { outputMode = "timeline" },
                        label = { Text("时间线事件") },
                    )
                }
                if (outputMode == "entries") {
                    Text("条目类型", style = MaterialTheme.typography.labelMedium)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        typeLabels.forEach { (type, label) ->
                            FilterChip(
                                selected = selectedType == type,
                                onClick = { selectedType = type },
                                label = { Text(label) },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = countText,
                    onValueChange = { countText = it.filter(Char::isDigit) },
                    label = { Text("生成数量") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = countText.isNotEmpty() && countValue !in 1..200,
                    supportingText = if (countText.isNotEmpty() && countValue !in 1..200) {
                        { Text("请输入 1–200 的整数") }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = minWordsText,
                        onValueChange = { minWordsText = it.filter(Char::isDigit) },
                        label = { Text("最少字数") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        isError = minWordsText.isNotEmpty() && (minWordsValue == null || minWordsValue < 1),
                        supportingText = if (minWordsText.isNotEmpty() && (minWordsValue == null || minWordsValue < 1)) {
                            { Text("请输入正整数") }
                        } else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = maxWordsText,
                        onValueChange = { maxWordsText = it.filter(Char::isDigit) },
                        label = { Text("最多字数") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        isError = maxWordsText.isNotEmpty() && (maxWordsValue == null || (minWordsValue != null && maxWordsValue < minWordsValue)),
                        supportingText = if (maxWordsText.isNotEmpty() && maxWordsValue != null && minWordsValue != null && maxWordsValue < minWordsValue) {
                            { Text("最多字数不能小于最少字数") }
                        } else if (maxWordsText.isNotEmpty() && maxWordsValue == null) {
                            { Text("请输入正整数") }
                        } else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }

                val ctxLabel = when {
                    outputMode == "timeline" -> "生成方向与细节（时间跨度、事件密度等）"
                    selectedType == "world" -> "世界梗概 / 核心要求（AI 会在此基础上扩写，字数以上方为准）"
                    else -> "世界观上下文（强烈建议填写）"
                }
                val ctxPlaceholder = when {
                    outputMode == "timeline" -> "如：按朝代顺序写政权更迭；突出主角团相关大事件…"
                    selectedType == "world" -> "如：现代都市异能，政府暗中管控觉醒者；多势力博弈…"
                    else -> "如：修仙世界，以灵气修炼为核心…"
                }
                OutlinedTextField(
                    value = contextPrompt,
                    onValueChange = { contextPrompt = it },
                    label = { Text(ctxLabel) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    placeholder = { Text(ctxPlaceholder) },
                )

                OutlinedTextField(
                    value = preGenNotes,
                    onValueChange = { preGenNotes = it },
                    label = { Text("生成前补充（可选）") },
                    placeholder = { Text("可选") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("后台生成", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Switch(
                        checked = runInBackground,
                        onCheckedChange = { runInBackground = it },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = inputValid,
                onClick = {
                    val count = countValue!!
                    val minWords = minWordsValue!!
                    val maxWords = maxWordsValue!!
                    val mode = outputMode.trim().lowercase()
                    val effectiveType = if (mode == "timeline") "event" else selectedType
                    onGenerate(
                        effectiveType,
                        count,
                        minWords,
                        maxWords,
                        contextPrompt,
                        preGenNotes,
                        runInBackground,
                        mode,
                    )
                    onDismiss()
                },
            ) {
                Text("加入队列")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
