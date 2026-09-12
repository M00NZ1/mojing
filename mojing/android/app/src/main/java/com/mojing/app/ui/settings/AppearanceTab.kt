package com.mojing.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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

/**
 * 外观设置独立页：对齐系统「设置」常见模式——**分组卡片**、**主标题 + 辅助说明**、**当前值可见**，
 * 单屏控件数量可控（Material / AOSP：相关项同组、避免单页过长）。
 */
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
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
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
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(vertical = 4.dp)) {
                ListItem(
                    headlineContent = { Text("主题配色") },
                    supportingContent = {
                        Text(AppThemes.label(themeMode), style = MaterialTheme.typography.bodySmall)
                    },
                    leadingContent = {
                        Icon(Icons.Default.Palette, contentDescription = null)
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppThemes.ORDER.forEach { id ->
                        FilterChip(
                            selected = themeMode == id,
                            onClick = {
                                viewModel.setThemeMode(id)
                                onThemeChanged(id)
                            },
                            label = { Text(AppThemes.label(id)) },
                        )
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(bottom = 8.dp)) {
                ListItem(
                    headlineContent = { Text("界面字体") },
                    leadingContent = {
                        Icon(Icons.Default.TextFields, contentDescription = null)
                    },
                    trailingContent = {
                        Text(
                            "${(uiFontScale * 100).toInt()}%",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
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
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(vertical = 4.dp)) {
                ListItem(
                    headlineContent = { Text("聊天显示密度") },
                    supportingContent = { Text("统一管理对话内容的间距与阅读宽度") },
                    leadingContent = { Icon(Icons.Default.ViewAgenda, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
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
