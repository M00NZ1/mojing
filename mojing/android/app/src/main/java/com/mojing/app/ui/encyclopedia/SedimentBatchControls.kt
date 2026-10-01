package com.mojing.app.ui.encyclopedia

import com.mojing.app.ui.common.MoJingButton as Button
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SedimentBatchControls(
    selecting: Boolean, selectedCount: Int, busy: Boolean,
    onToggle: () -> Unit, onSelect: () -> Unit, onClear: () -> Unit, onConfirm: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = !busy, onClick = onToggle) { Text(if (selecting) "结束选择" else "批量核对") }
            if (selecting) Text("已选 $selectedCount 条", style = MaterialTheme.typography.labelMedium)
        }
        if (selecting) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !busy, onClick = onSelect) { Text("选择前100条") }
                TextButton(enabled = !busy, onClick = onClear) { Text("清空") }
                Button(enabled = selectedCount > 0 && !busy, onClick = onConfirm) { Text(if (busy) "确认中…" else "确认所选") }
            }
        }
    }
}
