package com.mojing.app.ui.creation

import com.mojing.app.ui.common.MoJingTopAppBar as TopAppBar
import com.mojing.app.ui.common.MoJingIcon as Icon
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.RestorePage
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.mojing.app.ui.navigation.MainAppBottomNavigation
import com.mojing.app.ui.navigation.Routes
import com.mojing.app.ui.navigation.navigateSingleTop
import com.mojing.app.ui.common.MoJingCoverImage

private data class CreationSection(
    val title: String,
    val icon: ImageVector,
    val route: String,
)

private val storyCreationSection = CreationSection(
    title = "小说创作",
    icon = Icons.Outlined.AutoAwesome,
    route = Routes.STORY_SIMULATION,
)

private val creationResourceSections = listOf(
    CreationSection("角色", Icons.Outlined.Badge, Routes.CHARACTER_LIST),
    CreationSection("世界", Icons.AutoMirrored.Outlined.MenuBook, Routes.ENCYCLOPEDIA_LIST),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreationHubScreen(navController: NavHostController, viewModel: CreationHubViewModel = hiltViewModel()) {
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                        expandedHeight = 52.dp,
                title = { Text("创作", style = MaterialTheme.typography.headlineSmall) },
                actions = {
                    IconButton(onClick = { navController.navigateSingleTop(Routes.STORY_SIMULATION) }) {
                        Icon(Icons.Outlined.RestorePage, contentDescription = "恢复创作草稿")
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = { MainAppBottomNavigation(navController) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)) {
                CreationWorkspaceContent({ navController.navigateSingleTop(it) })
                Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("最近项目", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { navController.navigateSingleTop(Routes.SESSION_LIST) }) { Text("全部") }
                }
                when (val state = projects) {
                    CreationProjects.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    CreationProjects.Failed -> TextButton(onClick = viewModel::retry) { Text("读取失败，重新加载") }
                    is CreationProjects.Ready -> if (state.items.isEmpty()) {
                        Text("还没有故事。从上方选择一个入口，开始你的创作。", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                    } else state.items.forEach { session ->
                        Surface(onClick = { navController.navigateSingleTop(Routes.chat(session.session.id)) },
                            modifier = Modifier.padding(bottom = 8.dp), shape = MaterialTheme.shapes.small,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), color = MaterialTheme.colorScheme.surface) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Surface(Modifier.size(48.dp, 60.dp), shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                    if (!session.coverImagePath.isNullOrBlank()) MoJingCoverImage(
                                        path = session.coverImagePath,
                                        modifier = Modifier.fillMaxSize(),
                                        description = null,
                                    ) else Box(contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Outlined.MenuBook, null) }
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(session.session.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(session.session.updatedAt)),
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text((session.lastMessagePreview ?: session.session.summary).ifBlank { "打开故事，继续创作。" }, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CreationWorkspaceContent(onNavigate: (String) -> Unit, modifier: Modifier = Modifier) {
    val fontScale = LocalDensity.current.fontScale
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("故事、角色与世界，从这里开始", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().padding(top = 12.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf(storyCreationSection) + creationResourceSections).forEach { section ->
                Surface(onClick = { onNavigate(section.route) }, modifier = Modifier.weight(1f).fillMaxHeight(),
                    shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column {
                        Box(Modifier.fillMaxWidth().height(64.dp).padding(start = 12.dp, top = 16.dp),
                            contentAlignment = Alignment.CenterStart) {
                            Icon(section.icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(section.title, style = MaterialTheme.typography.titleSmall,
                                modifier = if (fontScale > 1.2f) Modifier.widthIn(max = (16f * fontScale * 2.2f).dp)
                                    .heightIn(min = (24f * fontScale * 2).dp) else Modifier)

                        }
                    }
                }
            }
        }
    }
}
