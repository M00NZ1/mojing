package com.mojing.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 44dp visual tab, 48dp minimum hit area; labels can grow with system font scale. */
@Composable
fun MoJingSectionTabs(labels: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup()
        .padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Column(Modifier.selectable(selected, role = Role.Tab, onClick = { onSelect(index) })
                .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.heightIn(min = 42.dp).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Text(label, style = MaterialTheme.typography.labelMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                        color = if (selected) colors.primary else colors.onSurfaceVariant)
                }
                Box(Modifier.width(28.dp).height(2.dp)
                    .background(if (selected) colors.primary else androidx.compose.ui.graphics.Color.Transparent))
            }
        }
    }
}
