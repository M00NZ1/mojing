package com.mojing.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mojing.app.data.ModelPlatform

@Composable
fun ChatModelPicker(platforms: List<ModelPlatform>, onDismiss: () -> Unit, selectedLabel: String = "", lastRequestModel: String? = null, isGenerating: Boolean = false, onSelect: (String, String) -> Unit) {
    var query by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择对话模型") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("从下一次发送生效，当前回复继续使用原模型。选择后，本场角色与旁白统一使用该线路。",
                style = MaterialTheme.typography.bodySmall)
            if (selectedLabel.isNotBlank()) Text("下次发送：$selectedLabel", style = MaterialTheme.typography.bodyMedium)
            if (lastRequestModel != null || isGenerating) Text(
                lastRequestModel?.let { "最近请求：$it" } ?: "本轮正在准备上下文",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(query, { query = it }, label = { Text("搜索平台或模型") }, singleLine = true)
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                platforms.forEach { p ->
                    val names = p.models.filter { p.name.contains(query, true) || it.contains(query, true) }
                    if (names.isNotEmpty()) {
                        item(key = "platform:${p.id}") {
                            Text(p.name + if (p.apiKey.isBlank()) " · 请先配置 Key" else "", modifier = Modifier.padding(top = 12.dp),
                                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        items(names, key = { "${p.id}:$it" }) { name ->
                            TextButton(onClick = { onSelect(p.id, name) }, enabled = p.apiKey.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                                Text(name, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                if (platforms.none { p -> p.models.any { p.name.contains(query, true) || it.contains(query, true) } }) {
                    item { Text("没有匹配模型，请在模型设置中添加平台和模型。", modifier = Modifier.padding(vertical = 20.dp)) }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
