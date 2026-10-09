package com.mojing.app.ui.encyclopedia

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.ui.common.avatarImageModel

data class WorldOverviewStat(
    val label: String,
    val value: Int,
)

/** One overview of the actual world; collapse it to give the paged entries more room. */
@Composable
internal fun WorldOverviewHeader(
    world: EncyclopediaEntity,
    stats: List<WorldOverviewStat> = emptyList(),
) {
    val compact = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 720 ||
        androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.2f
    var expanded by rememberSaveable(world.id) { mutableStateOf(!compact) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (expanded && com.mojing.app.ui.common.hasUserImage(world.coverImagePath)) {
            AsyncImage(model = avatarImageModel(LocalContext.current, world.coverImagePath), contentDescription = "世界封面",
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(2.2f).clip(MaterialTheme.shapes.medium))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(world.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${world.entryCount} 条目", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = { expanded = !expanded }) {
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "收起世界概览" else "展开世界概览")
            }
        }
        if (expanded && world.description.isNotBlank()) {
            Text(world.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 8.dp))
        }
        if (expanded && stats.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                stats.take(4).forEach { stat ->
                    Surface(
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                            Text(stat.value.toString(), style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary)
                            Text(stat.label, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun WorldOverviewActions(onCreateEntry: () -> Unit, onOpenTimeline: () -> Unit,
    onOpenGraph: () -> Unit, onOpenSediment: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OverviewAction("新建条目", Icons.Outlined.AddCircleOutline, onCreateEntry)
            OverviewAction("时间线", Icons.Outlined.EventNote, onOpenTimeline)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OverviewAction("关系图", Icons.Outlined.AccountTree, onOpenGraph)
            OverviewAction("沉积资料", Icons.Outlined.Inventory2, onOpenSediment)
        }
    }
}

@Composable
private fun RowScope.OverviewAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
        shape = MaterialTheme.shapes.small, contentPadding = PaddingValues(horizontal = 8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/** Keep world editing reachable even when short windows hide the overview shortcuts. */
@Composable
internal fun WorldSettingsAction(onClick: () -> Unit) {
    val compact = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 600 ||
        androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.2f
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text("世界设置", style = MaterialTheme.typography.labelLarge)
                if (!compact) Text("编辑名称、简介与世界规则", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null,
                modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
