package com.mojing.app.ui.creation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
    description = "填写故事背景与开篇走向，并选择角色、百科或世界模板",
    icon = Icons.Default.AutoAwesome,
    route = Routes.STORY_SIMULATION,
)

private val creationResourceSections = listOf(
    CreationSection("角色", "编辑人物设定、形象和对话参数", Icons.Default.Badge, Routes.CHARACTER_LIST),
    CreationSection("世界百科", "保存世界知识：地点、势力、人物关系与剧情事件", Icons.AutoMirrored.Filled.MenuBook, Routes.ENCYCLOPEDIA_LIST),
    CreationSection("设定工坊", "配置开局玩法、叙事规则与可复用的世界模板", Icons.Default.Public, Routes.WORKBENCH),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreationHubScreen(navController: NavHostController) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("创作") },
                navigationIcon = {
                    IconButton(onClick = { navController.returnToSessionHome() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回会话主页")
                    }
                },
            )
        },
        bottomBar = { MainAppBottomNavigation(navController) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .widthIn(max = 720.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("创作工作区", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "角色决定谁参与，百科提供世界知识，工坊配置开局玩法。创作时按需组合，也可以只选角色开始。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Card(
                    onClick = { navController.navigateSingleTop(storyCreationSection.route) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            modifier = Modifier.size(52.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    storyCreationSection.icon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(storyCreationSection.title, style = MaterialTheme.typography.titleLarge)
                            Text(
                                storyCreationSection.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }

                Text(
                    "创作资料",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Card(modifier = Modifier.fillMaxWidth()) {
                    creationResourceSections.forEachIndexed { index, section ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { navController.navigateSingleTop(section.route) }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                modifier = Modifier.size(42.dp),
                                shape = MaterialTheme.shapes.large,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        section.icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(22.dp),
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(section.title, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    section.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (index < creationResourceSections.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 72.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                            )
                        }
                    }
                }
            }
        }
    }
}
