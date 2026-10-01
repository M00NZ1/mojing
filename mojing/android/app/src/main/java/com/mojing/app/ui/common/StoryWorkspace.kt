package com.mojing.app.ui.common

import com.mojing.app.ui.common.MoJingIcon as Icon
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Shared editorial surfaces for the story library and creation workspace. */
@Composable
fun StoryFeatureCard(
    eyebrow: String,
    title: String,
    description: String,
    action: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        color = colors.surface, contentColor = colors.onSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(eyebrow, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            }
            Text(description, style = MaterialTheme.typography.bodyMedium)
            MoJingButton(onClick) {
                Text(action)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null)
            }
        }
    }
}

@Composable
fun StoryLaunchCard(title: String, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        color = colors.surface, contentColor = colors.onSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(description, style = MaterialTheme.typography.bodyMedium)
            }
            Surface(shape = MaterialTheme.shapes.small, color = colors.primaryContainer, contentColor = colors.onPrimaryContainer) {
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.padding(12.dp).size(24.dp))
            }
        }
    }
}

@Composable
fun WorkspaceResourceCard(
    title: String, description: String, index: String, icon: ImageVector,
    onClick: () -> Unit, modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = onClick, modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small, color = colors.surfaceContainerLowest,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = MaterialTheme.shapes.small, color = colors.primaryContainer) {
                Icon(icon, null, Modifier.padding(10.dp).size(22.dp), tint = colors.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                }
                Text(description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
        }
    }
}

@Composable
fun WorkspaceSectionHeading(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
