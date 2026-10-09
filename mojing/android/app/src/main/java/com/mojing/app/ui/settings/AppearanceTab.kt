package com.mojing.app.ui.settings

import com.mojing.app.ui.chat.ChatReadingStyle
import com.mojing.app.ui.common.MoJingFilterChip as FilterChip
import com.mojing.app.ui.common.MoJingIcon as Icon
import com.mojing.app.ui.theme.AppThemes
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Compact appearance and reading preferences backed by the existing preference owner. */
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
    val readingPreferencesSaving by viewModel.readingPreferencesSaving.collectAsStateWithLifecycle()
    var moreThemes by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AppearanceGroup("主题") {
            Text("主题配色", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (id, label) ->
                    FilterChip(selected = themeMode == id, onClick = {
                        viewModel.setThemeMode(id)
                        onThemeChanged(id)
                    }, label = { Text(label) })
                }
            }
            if (moreThemes) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppThemes.ORDER.filter { it !in setOf("system", "light", "dark") }.forEach { id ->
                        FilterChip(selected = themeMode == id, onClick = {
                            viewModel.setThemeMode(id)
                            onThemeChanged(id)
                        }, label = { Text(AppThemes.label(id)) })
                    }
                }
            }
            Surface(onClick = { moreThemes = !moreThemes }, color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.extraSmall, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Text(if (moreThemes) "收起主题" else "查看更多主题", Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium)
            }
        }

        AppearanceGroup("界面显示") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.TextFields, null)
                Text("界面字号", Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.titleSmall)
                Text("${(uiFontScale * 100).toInt()}%", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.9f to "小", 1f to "标准", 1.15f to "大").forEach { (scale, label) ->
                    FilterChip(selected = kotlin.math.abs(uiFontScale - scale) < 0.01f, onClick = {
                        viewModel.setUiFontScale(scale)
                        onFontScaleChanged(scale)
                    }, label = { Text(label) })
                }
            }
        }

        AppearanceGroup("阅读显示") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ViewAgenda, null)
                Text("内容密度", Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.titleSmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("comfortable" to "舒适", "compact" to "紧凑", "reader" to "阅读").forEach { (id, label) ->
                    FilterChip(selected = chatDensity == id, enabled = !readingPreferencesSaving,
                        onClick = { viewModel.setChatDensity(id) }, label = { Text(label) })
                }
            }
        }

        AppearanceGroup("阅读字体与旁白") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("system" to "跟随系统", "sans" to "无衬线", "serif" to "衬线", "mono" to "等宽").forEach { (id, label) ->
                    FilterChip(selected = chatFont == id, enabled = !readingPreferencesSaving,
                        onClick = { viewModel.setChatFont(id) }, label = { Text(label) })
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("旁白使用斜体", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = narratorItalic, enabled = !readingPreferencesSaving, onCheckedChange = viewModel::setNarratorItalic)
            }
            val readingStyle = ChatReadingStyle(chatFont, narratorItalic)
            Text("夜色落在书页上，远处传来潮声。", style = MaterialTheme.typography.bodyLarge.copy(
                fontFamily = readingStyle.fontFamily,
                fontStyle = if (narratorItalic) FontStyle.Italic else FontStyle.Normal,
            ))
        }
    }
}

@Composable
private fun AppearanceGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
        }
    }
}
