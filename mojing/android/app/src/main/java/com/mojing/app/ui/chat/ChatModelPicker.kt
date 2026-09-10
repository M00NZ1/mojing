package com.mojing.app.ui.chat

import com.mojing.app.ui.common.MoJingTextField as OutlinedTextField

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mojing.app.data.ModelPlatform

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatModelPicker(platforms: List<ModelPlatform>, onDismiss: () -> Unit, selectedLabel: String = "", lastRequestModel: String? = null, isGenerating: Boolean = false, isSaving: Boolean = false, saveError: String? = null, selectedModel: Pair<String, String>? = null, onFollowSettings: (() -> Unit)? = null, onSelect: (String, String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val followMatches = onFollowSettings != null && (query.isBlank() || "跟随角色与模型设置".contains(query, true))
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("选择对话模型", modifier = Modifier.weight(1f).padding(top = 8.dp),
                    style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
            OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("搜索平台或模型") }, singleLine = true)
            if (isSaving) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("正在保存模型选择…", style = MaterialTheme.typography.bodySmall)
            } else if (saveError != null) {
                Text(saveError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            LazyColumn(Modifier.weight(1f).testTag("chat-model-list"), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (query.isBlank()) item(key = "request-info") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("从下一次发送生效，当前回复继续使用原模型。选择后，本场角色与旁白统一使用该线路。",
                            style = MaterialTheme.typography.bodySmall)
                        if (selectedLabel.isNotBlank()) Text("下次发送：$selectedLabel", style = MaterialTheme.typography.bodyMedium)
                        if (selectedLabel == "跟随角色与模型设置") Text("未指定会话模型时，各角色沿用独立配置或公共设置；思考模型覆盖仍按设置生效。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (lastRequestModel != null || isGenerating) Text(
                            lastRequestModel?.let { "最近请求：$it" } ?: "本轮正在准备上下文",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (followMatches && onFollowSettings != null) {
                    item(key = "follow-settings") {
                        TextButton(onClick = onFollowSettings, enabled = !isSaving,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .testTag("chat-model-follow-settings").semantics { selected = selectedLabel == "跟随角色与模型设置" }) {
                            Text("跟随角色与模型设置", Modifier.weight(1f))
                            if (selectedLabel == "跟随角色与模型设置") Icon(Icons.Default.Check, "已选择")
                        }
                    }
                }
                platforms.forEach { p ->
                    val names = p.models.filter { p.name.contains(query, true) || it.contains(query, true) }
                    if (names.isNotEmpty()) {
                        item(key = "platform:${p.id}") {
                            Text(p.name + if (p.apiKey.isBlank()) " · 请先配置 Key" else "", modifier = Modifier.padding(top = 12.dp),
                                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        items(names, key = { "${p.id}:$it" }) { name ->
                            val isSelected = selectedModel == (p.id to name)
                            TextButton(onClick = { onSelect(p.id, name) }, enabled = !isSaving && p.apiKey.isNotBlank(),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .testTag("chat-model:${p.id}:$name").semantics { selected = isSelected },
                                colors = ButtonDefaults.textButtonColors(containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
                            ) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(name, modifier = Modifier.weight(1f), color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else LocalContentColor.current)
                                    if (isSelected) Icon(Icons.Default.Check, "已选择", tint = MaterialTheme.colorScheme.onSecondaryContainer)
                                }
                            }
                        }
                    }
                }
                if (!followMatches && platforms.none { p -> p.models.any { p.name.contains(query, true) || it.contains(query, true) } }) {
                    item { Text("没有匹配模型，请在模型设置中添加平台和模型。", modifier = Modifier.padding(vertical = 20.dp)) }
                }
            }
        }
    }
}
