package com.mojing.app.ui.creation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.mojing.app.ui.navigation.MainAppBottomNavigation
import com.mojing.app.ui.navigation.Routes
import com.mojing.app.ui.navigation.navigateSingleTop
import com.mojing.app.ui.navigation.returnToSessionHome

private data class CreationSection(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val route: String,
)

private val storyCreationSection = CreationSection(
    title = "小说创作",
    description = "填写故事背景与开篇走向，并选择角色和世界",
    icon = Icons.Default.AutoAwesome,
    route = Routes.STORY_SIMULATION,
)

private val creationResourceSections = listOf(
    CreationSection("角色", "编辑人物设定、形象和对话参数", Icons.Default.Badge, Routes.CHARACTER_LIST),
    CreationSection("世界", "进入世界百科，整理规则、地点、势力、人物关系与剧情事件", Icons.AutoMirrored.Filled.MenuBook, Routes.ENCYCLOPEDIA_LIST),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreationHubScreen(navController: NavHostController) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("创作空间", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = { navController.returnToSessionHome() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回会话主页")
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = { MainAppBottomNavigation(navController) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            CreationWorkspaceContent(
                onNavigate = { navController.navigateSingleTop(it) },
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
internal fun CreationWorkspaceContent(onNavigate: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        com.mojing.app.ui.common.StoryLaunchCard(
            title = "小说创作",
            description = "构思开篇，续写故事。",
            onClick = { onNavigate(storyCreationSection.route) },
        )
        com.mojing.app.ui.common.WorkspaceSectionHeading(
            "故事素材", "人物与世界，共同构成故事。",
            Modifier.padding(top = 14.dp, bottom = 4.dp),
        )
        creationResourceSections.forEachIndexed { index, section ->
            com.mojing.app.ui.common.WorkspaceResourceCard(
                title = section.title, description = section.description,
                index = "0${index + 1}", icon = section.icon,
                onClick = { onNavigate(section.route) },
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}
