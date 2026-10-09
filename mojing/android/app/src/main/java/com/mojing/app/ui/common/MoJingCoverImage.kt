package com.mojing.app.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/** Real asset or a neutral fallback; never assign an unrelated illustration to a story. */
@Composable
fun MoJingCoverImage(path: String?, modifier: Modifier = Modifier, description: String? = null,
    shape: androidx.compose.ui.graphics.Shape = MaterialTheme.shapes.small, person: Boolean = false) {
    Surface(modifier, shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Box(contentAlignment = Alignment.Center) {
            Icon(if (person) Icons.Outlined.Person else Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            if (hasUserImage(path)) AsyncImage(
                model = avatarImageModel(LocalContext.current, path.orEmpty()), contentDescription = description,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
