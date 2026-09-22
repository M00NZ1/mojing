package com.mojing.app.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mojing.app.data.ModelPlatform

@Composable
internal fun PlatformTabs(
    platforms: List<ModelPlatform>, selectedId: String?, onSelect: (String) -> Unit,
    modifier: Modifier = Modifier, itemTagPrefix: String = "platform:",
) {
    val state = rememberLazyListState(
        initialFirstVisibleItemIndex = platforms.indexOfFirst { it.id == selectedId }.coerceAtLeast(0),
    )
    LazyRow(state = state, modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
        items(platforms, key = { it.id }) { platform ->
            val active = platform.id == selectedId
            Surface(onClick = { onSelect(platform.id) }, shape = RoundedCornerShape(10.dp),
                color = if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                contentColor = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("$itemTagPrefix${platform.id}").semantics { selected = active; role = Role.Tab }) {
                Box(Modifier.heightIn(min = 48.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                    Text(platform.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.widthIn(max = 180.dp))
                }
            }
        }
    }
}
