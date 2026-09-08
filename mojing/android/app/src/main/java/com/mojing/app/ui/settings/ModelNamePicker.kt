package com.mojing.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun ModelNamePicker(
    names: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var search by remember { mutableStateOf("") }
    val matches = remember(names, search) { names.filter { it.contains(search.trim(), ignoreCase = true) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择默认模型") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(search, { search = it }, label = { Text("搜索模型") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("${matches.size} 个模型", style = MaterialTheme.typography.labelMedium)
                if (matches.isEmpty()) Text("没有匹配的模型，试试其他关键词。")
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(matches, key = { it }) { name ->
                        TextButton(onClick = { onSelect(name) }, modifier = Modifier.fillMaxWidth()) {
                            Text(name, modifier = Modifier.weight(1f))
                            if (name == selected) Text(" ✓")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
