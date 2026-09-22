package com.mojing.app.ui.settings

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.rememberSaveable

/** 分类与资料草稿共同恢复；模型快捷入口仍经过页面的未保存修改确认。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSections(
    requestModelSection: Boolean,
    onModelRequestConsumed: () -> Unit,
    requestNavigation: (() -> Unit) -> Unit,
    content: @Composable (Int) -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(requestModelSection) {
        if (requestModelSection) {
            onModelRequestConsumed()
            if (selectedTab != 0) requestNavigation { selectedTab = 0 }
        }
    }
    PrimaryScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 0.dp) {
        listOf("模型", "创作", "个性化", "用量", "关于与更新").forEachIndexed { index, title ->
            Tab(selected = selectedTab == index,
                onClick = { if (selectedTab != index) requestNavigation { selectedTab = index } },
                text = { Text(title) })
        }
    }
    content(selectedTab)
}
