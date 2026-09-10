package com.mojing.app.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** 编辑值由页面持有，展开只改变可见行数。 */
@Composable
fun MoJingLongTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier) {
        MoJingTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            enabled = enabled,
            minLines = 4,
            maxLines = if (expanded) 16 else 6,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
            onClick = { expanded = !expanded },
            enabled = enabled,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text(if (expanded) "收起编辑" else "展开编辑")
        }
    }
}
