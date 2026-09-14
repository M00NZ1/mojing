package com.mojing.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.mojing.app.ui.common.ModelPickerHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelNamePicker(names: List<String>, selected: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    val matches = remember(names, search) { names.distinct().filter { it.contains(search.trim(), ignoreCase = true) } }
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.72f
    ModalBottomSheet(onDismissRequest = onDismiss, dragHandle = null,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).padding(horizontal = 12.dp, vertical = 8.dp)) {
            ModelPickerHeader("默认模型", search, { search = it }, onDismiss)
            if (matches.isEmpty()) Text("没有匹配的模型，试试其他关键词。", Modifier.padding(12.dp))
            key(search) {
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    items(matches, key = { it }) { name ->
                        com.mojing.app.ui.common.ModelOptionRow(name, name == selected, { onSelect(name) })
                    }
                }
            }
        }
    }
}
