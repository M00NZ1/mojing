package com.mojing.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mojing.app.ui.common.MoJingButton

@Composable
internal fun ProfileSaveActions(saving: Boolean, dirty: Boolean, canSave: Boolean, error: String?, onSave: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        error?.takeIf { dirty && !saving }?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        MoJingButton(onSave, enabled = !saving && dirty && canSave, modifier = Modifier.fillMaxWidth()) {
            Text(if (saving) "正在保存…" else if (dirty) "保存资料" else "已保存")
        }
    }
}
