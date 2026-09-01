package com.mojing.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.foundation.shape.CircleShape

@Composable
fun VoiceInputButton(
    onClick: () -> Unit,
    isListening: Boolean,
    modifier: Modifier = Modifier.size(40.dp),
    iconPadding: Dp = 10.dp
) {
    Surface(
        modifier = modifier.clickable { onClick() },
        shape = CircleShape,
        color = if (isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Icon(
            Icons.Default.Mic, "语音输入",
            modifier = Modifier.padding(iconPadding),
            tint = if (isListening) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSurface
        )
    }
}
