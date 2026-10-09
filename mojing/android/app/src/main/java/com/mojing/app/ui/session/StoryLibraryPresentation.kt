package com.mojing.app.ui.session

import com.mojing.app.ui.common.MoJingIcon as Icon
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mojing.app.ui.theme.MoJingTheme

/** Secondary setup stays a small, reachable row rather than competing with stories. */
@Composable
internal fun StoryLibraryModelHint(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = onOpenSettings, modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small, color = colors.primaryContainer) {
        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("尚未配置模型", style = MaterialTheme.typography.labelLarge, color = colors.onSurface)
                Text("连接平台并选择模型，即可开始对话。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp), tint = colors.primary)
        }
    }
}

@Composable
internal fun StoryLibrarySectionHeading(title: String, count: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Text("本页 $count 个故事", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Preview(showBackground = true, widthDp = 320)
@Composable
private fun StoryLibraryPresentationPreview() {
    MoJingTheme {
        Column(Modifier.padding(20.dp)) {
            StoryLibraryModelHint({})
            StoryLibrarySectionHeading("最近阅读", 3, Modifier.padding(vertical = 16.dp))
        }
    }
}
