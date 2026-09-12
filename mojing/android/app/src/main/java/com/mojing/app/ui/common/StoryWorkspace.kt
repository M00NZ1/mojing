package com.mojing.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
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
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp),
        color = colors.primaryContainer, contentColor = colors.onPrimaryContainer) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(eyebrow, style = MaterialTheme.typography.labelMedium, color = colors.onPrimaryContainer)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                StoryLandscape(Modifier.size(44.dp))
            }
            Text(description, style = MaterialTheme.typography.bodyMedium)
            MoJingButton(onClick, Modifier.fillMaxWidth()) {
                Text(action, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
            }
        }
    }
}

@Composable
private fun StoryLandscape(modifier: Modifier) {
    val ink = MaterialTheme.colorScheme.onPrimaryContainer
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawCircle(ink.copy(alpha = 0.16f), w * .35f, Offset(w * .60f, h * .38f))
        val ridge = Path().apply {
            moveTo(0f, h * .75f)
            cubicTo(w * .18f, h * .75f, w * .28f, h * .20f, w * .45f, h * .45f)
            cubicTo(w * .60f, h * .72f, w * .68f, h * .42f, w, h * .68f)
        }
        drawPath(ridge, ink, style = Stroke(2.dp.toPx()))
        drawLine(ink.copy(alpha = .4f), Offset(w * .2f, h * .90f), Offset(w * .8f, h * .90f), 1.dp.toPx())
    }
}

@Composable
fun StoryLaunchCard(title: String, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp),
        color = colors.primaryContainer, contentColor = colors.onPrimaryContainer) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Text(description, style = MaterialTheme.typography.bodyMedium)
            }
            Surface(shape = RoundedCornerShape(20.dp), color = colors.primary, contentColor = colors.onPrimary) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.padding(12.dp).size(24.dp))
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
        shape = RoundedCornerShape(22.dp), color = colors.surfaceContainerLow) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(shape = RoundedCornerShape(16.dp), color = colors.secondaryContainer) {
                Icon(icon, null, Modifier.padding(12.dp).size(24.dp), tint = colors.onSecondaryContainer)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Text(index, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
                Text(description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
        }
    }
}

@Composable
fun WorkspaceSectionHeading(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
