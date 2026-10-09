package com.mojing.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

@Composable
fun RoundChoicesRow(choices: List<String>, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    if (choices.isEmpty()) return
    val compact = LocalConfiguration.current.screenHeightDp < 720 || LocalDensity.current.fontScale > 1.2f
    var expanded by rememberSaveable(choices, compact) { mutableStateOf(!compact) }
    Surface(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("剧情建议 · 可自由输入", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    if (expanded) "收起剧情建议" else "展开剧情建议", Modifier.size(20.dp))
            }
            if (expanded) choices.forEachIndexed { index, choice ->
                Surface(onClick = { onSelect(choice) }, shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Text("${index + 1}.   $choice", Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
