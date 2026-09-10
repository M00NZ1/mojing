package com.mojing.app.ui.encyclopedia

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import com.mojing.app.ui.common.MoJingTextField

internal val ENTRY_CONFIDENCE_OPTIONS = listOf(
    "已确认" to "confirmed", "对话推断" to "inferred", "草稿" to "draft",
    "待核对" to "pending", "推测" to "heuristic", "低可信" to "low",
)

internal fun entryConfidenceLabel(value: String) =
    ENTRY_CONFIDENCE_OPTIONS.find { it.second == value }?.first ?: "未标注"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EntryConfidenceSelector(value: String, onChange: (String) -> Unit, enabled: Boolean = true) {
    var expanded by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = {
        if (enabled) { if (it) focus.clearFocus(); expanded = it }
    }) {
        MoJingTextField(value = entryConfidenceLabel(value), onValueChange = {}, readOnly = true,
            enabled = enabled, label = { Text("确认状态") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled))
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ENTRY_CONFIDENCE_OPTIONS.forEach { (label, key) ->
                DropdownMenuItem(text = { Text(label) }, enabled = enabled,
                    onClick = { onChange(key); expanded = false })
            }
        }
    }
}
