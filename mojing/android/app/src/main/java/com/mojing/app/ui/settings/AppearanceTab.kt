package com.mojing.app.ui.settings

import com.mojing.app.ui.common.MoJingIcon as Icon
import com.mojing.app.ui.common.MoJingFilterChip as FilterChip
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.mojing.app.ui.theme.MoJingTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mojing.app.ui.theme.AppThemes
import com.mojing.app.ui.chat.ChatReadingStyle

/** Theme and reading preferences with previews from the actual app palettes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceTab(
    viewModel: SettingsViewModel,
    onThemeChanged: (String) -> Unit,
    onFontScaleChanged: (Float) -> Unit,
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val uiFontScale by viewModel.uiFontScale.collectAsStateWithLifecycle()
    val chatDensity by viewModel.chatDensity.collectAsStateWithLifecycle()
    val chatFont by viewModel.chatFont.collectAsStateWithLifecycle()
    val narratorItalic by viewModel.narratorItalic.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("主题配色", style = MaterialTheme.typography.titleMedium)
            LazyRow(state = rememberLazyListState(initialFirstVisibleItemIndex = AppThemes.ORDER.indexOf(themeMode).coerceAtLeast(0)),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(AppThemes.ORDER, key = { it }) { id ->
                    ThemePreviewOption(id, themeMode == id) {
                        viewModel.setThemeMode(id)
                        onThemeChanged(id)
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("对话字体", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("system" to "跟随系统", "sans" to "无衬线", "serif" to "衬线", "mono" to "等宽").forEach { (id, label) ->
                        FilterChip(selected = chatFont == id, onClick = { viewModel.setChatFont(id) }, label = { Text(label) })
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("旁白使用斜体", Modifier.weight(1f))
                    Switch(checked = narratorItalic, onCheckedChange = viewModel::setNarratorItalic)
                }
                val readingStyle = ChatReadingStyle(chatFont, narratorItalic)
                Text("夜色落在书页上，远处传来潮声。", style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = readingStyle.fontFamily, fontStyle = readingStyle.narratorFontStyle,
                ))
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        ) {
            Column(Modifier.padding(bottom = 8.dp)) {
                ListItem(
                    headlineContent = { Text("界面字体") },
                    leadingContent = {
                        Icon(Icons.Outlined.TextFields, contentDescription = null)
                    },
                    trailingContent = {
                        Text(
                            "${(uiFontScale * 100).toInt()}%",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                )
                Slider(
                    value = uiFontScale,
                    onValueChange = { v ->
                        viewModel.setUiFontScale(v)
                        onFontScaleChanged(v)
                    },
                    valueRange = 0.85f..1.35f,
                    steps = 9,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val applyFont: (Float) -> Unit = { s ->
                        viewModel.setUiFontScale(s)
                        onFontScaleChanged(s)
                    }
                    TextButton(onClick = { applyFont(0.9f) }, modifier = Modifier.weight(1f)) { Text("较小") }
                    TextButton(onClick = { applyFont(1f) }, modifier = Modifier.weight(1f)) { Text("默认") }
                    TextButton(onClick = { applyFont(1.15f) }, modifier = Modifier.weight(1f)) { Text("较大") }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        ) {
            Column(Modifier.padding(vertical = 4.dp)) {
                ListItem(
                    headlineContent = { Text("聊天显示密度") },
                    supportingContent = { Text("统一管理对话内容的间距与阅读宽度") },
                    leadingContent = { Icon(Icons.Outlined.ViewAgenda, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        "comfortable" to "舒适",
                        "compact" to "紧凑",
                        "reader" to "阅读",
                    ).forEach { (id, label) ->
                        FilterChip(
                            selected = chatDensity == id,
                            onClick = { viewModel.setChatDensity(id) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemePreviewOption(id: String, active: Boolean, onSelect: () -> Unit) {
    MoJingTheme(themeMode = id) {
        Surface(onClick = onSelect, modifier = Modifier.width(152.dp).semantics { selected = active },
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(if (active) 2.dp else 1.dp,
                if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.fillMaxWidth().heightIn(min = 100.dp).clearAndSetSemantics {},
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small) {
                        Text("故事继续", Modifier.padding(horizontal = 10.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    Surface(modifier = Modifier.align(Alignment.End), color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.small) {
                        Text("下一幕", Modifier.padding(horizontal = 10.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(AppThemes.label(id), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    if (active) Icon(Icons.Outlined.Check, "已选择", Modifier.size(18.dp))
                }
            }
        }
    }
}
