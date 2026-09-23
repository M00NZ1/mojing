package com.mojing.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
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
    content: @Composable (Int) -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(-1) }
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
        SettingsSection("创作偏好", "新故事与角色的默认设置", Icons.AutoMirrored.Filled.MenuBook),
        SettingsSection("个性化", "我的资料、主题与阅读显示", Icons.Outlined.Tune),
        SettingsSection("用量与费用", "平台、模型与单次请求", Icons.Outlined.DataUsage),
        SettingsSection("关于与更新", "当前版本与检查更新", Icons.Outlined.SystemUpdate),
    )
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                if (selectedTab >= 0) requestNavigation { selectedTab = -1 } else onBack()
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, if (selectedTab >= 0) "返回设置" else "返回故事库")
            }
            Text(sections.getOrNull(selectedTab)?.title ?: "设置",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(start = 4.dp, end = 12.dp))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(Modifier.weight(1f)) {
            if (selectedTab < 0) {
                LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
                    sections.forEachIndexed { index, section ->
                        if (index == 0 || index == 2 || index == 4) item(key = "section-$index") {
                            Text(when (index) { 0 -> "连接与创作"; 2 -> "个人与记录"; else -> "应用" },
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = if (index == 0) 8.dp else 24.dp, bottom = 8.dp))
                        }
                        item(key = section.title) {
                            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) {
                                requestNavigation { selectedTab = index }
                            }.padding(vertical = 18.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(section.icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(section.title, style = MaterialTheme.typography.titleMedium)
                                    Text(section.summary, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
