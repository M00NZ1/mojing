package com.mojing.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ImageGenButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier.size(40.dp),
    iconPadding: Dp = 10.dp
) {
    Surface(
        modifier = modifier.clickable { onClick() },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Icon(
            Icons.Default.Image, "图片生成",
            modifier = Modifier.padding(iconPadding),
            tint = MaterialTheme.colorScheme.onSurface
        )
    }
}
