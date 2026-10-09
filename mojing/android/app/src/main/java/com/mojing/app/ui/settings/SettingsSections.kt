package com.mojing.app.ui.settings

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import com.mojing.app.ui.common.MoJingIcon as Icon
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.mojing.app.ui.common.isImeKeyboardOpen

/** 分类首页与独立内容层共用未保存确认；快捷入口直接进入模型页。 */
@Composable
internal fun SettingsSections(
    requestModelSection: Boolean,
    onModelRequestConsumed: () -> Unit,
    requestNavigation: (() -> Unit) -> Unit,
    onBack: () -> Unit,
    sectionState: MutableIntState = rememberSaveable { mutableIntStateOf(-1) },
    content: @Composable (Int) -> Unit,
) {
    var selectedTab by sectionState
    LaunchedEffect(requestModelSection) {
        if (requestModelSection) {
            onModelRequestConsumed()
            if (selectedTab != 0) requestNavigation { selectedTab = 0 }
        }
    }
    val imeOpen = isImeKeyboardOpen()
    BackHandler(enabled = selectedTab >= 0 && !imeOpen) {
        requestNavigation { selectedTab = -1 }
    }
    val sections = listOf(
        SettingsSection("平台与模型", "连接平台、管理模型与单价", Icons.Outlined.Forum),
        SettingsSection("创作偏好", "新故事与角色的默认设置", Icons.AutoMirrored.Outlined.MenuBook),
        SettingsSection("个性化", "我的资料、主题与阅读显示", Icons.Outlined.Tune),
        SettingsSection("用量与费用", "平台、模型与单次请求", Icons.Outlined.DataUsage),
        SettingsSection("应用更新", "版本信息、更新与改进", Icons.Outlined.SystemUpdate),
    )
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (selectedTab >= 0) {
                IconButton(onClick = { requestNavigation { selectedTab = -1 } }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回设置")
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(sections[selectedTab].title, style = MaterialTheme.typography.titleMedium,
                        maxLines = 1)
                }
                Spacer(Modifier.width(48.dp))
            } else {
                Text("设置", style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(start = 8.dp, end = 12.dp))
            }
        }
        if (selectedTab >= 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val reveal = remember(selectedTab) { Animatable(0f) }
        LaunchedEffect(selectedTab) { reveal.animateTo(1f, tween(160)) }
        Box(Modifier.weight(1f).graphicsLayer {
            translationY = (1f - reveal.value) * 8.dp.toPx()
        }) {
            if (selectedTab < 0) {
                LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    sections.forEachIndexed { index, section ->
                        item(key = section.title) {
                            Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.surfaceContainerLowest).clickable(role = Role.Button) {
                                requestNavigation { selectedTab = index }
                            }.heightIn(min = 72.dp).padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                    Icon(section.icon, null, Modifier.padding(6.dp).size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(section.title, style = MaterialTheme.typography.titleMedium)
                                    Text(section.summary, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            } else content(selectedTab)
        }
    }
}

private data class SettingsSection(
    val title: String,
    val summary: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)
