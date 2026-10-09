package com.mojing.app.ui.character

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.heightIn
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil.compose.AsyncImage
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.ui.common.avatarImageModel
import com.mojing.app.ui.common.MoJingCenterAlignedTopAppBar
import com.mojing.app.ui.common.MoJingButton
import com.mojing.app.ui.common.MoJingTonalButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterDetailScreen(
    characterId: Long,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onChat: (Long) -> Unit,
    viewModel: CharacterDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(characterId) { viewModel.load(characterId, force = true) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.load(characterId, force = true) }
    LaunchedEffect(state.actionError) {
        state.actionError?.let { snackbar.showSnackbar(it); viewModel.consumeActionError() }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            MoJingCenterAlignedTopAppBar(
                title = { Text("角色详情", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回角色")
                    }
                },
                actions = {
                    state.character?.let { character ->
                        IconButton(onClick = { onEdit(character.id) },
                            enabled = !state.loading && state.error == null && !state.actionInProgress) {
                            Icon(Icons.Outlined.Edit, contentDescription = "编辑角色")
                        }
                    }
                },
            )
        },
        bottomBar = {
            state.character?.takeIf { !state.loading && state.error == null }?.let { character ->
                CharacterDetailActions(
                    favorite = character.favorite,
                    busy = state.actionInProgress,
                    onEdit = { onEdit(character.id) },
                    onChat = { viewModel.startChat(onChat) },
                    onFavorite = viewModel::toggleFavorite,
                )
            }
        },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.error != null -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(state.error!!, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { viewModel.retry(characterId) }) { Text("重试") }
            }
            state.character != null -> CharacterDetailContent(
                character = state.character!!,
                encyclopediaName = state.encyclopediaName,
                recentStories = state.recentStories,
                storyPageIndex = state.storyPageIndex,
                storyHasNext = state.storyHasNext,
                storiesLoading = state.storiesLoading,
                storiesError = state.storiesError,
                onPreviousStoryPage = viewModel::previousStoryPage,
                onNextStoryPage = viewModel::nextStoryPage,
                onRetryStories = viewModel::retryStoryPage,
                busy = state.actionInProgress,
                onFavorite = viewModel::toggleFavorite,
                onOpenStory = onChat,
                modifier = Modifier.fillMaxSize().padding(padding),
                context = context,
            )
        }
    }
}

@Composable
private fun CharacterDetailActions(
    favorite: Boolean,
    busy: Boolean,
    onEdit: () -> Unit,
    onChat: () -> Unit,
    onFavorite: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val compact = maxWidth < 360.dp
                val style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge
                val actionPadding = PaddingValues(horizontal = if (compact) 8.dp else 12.dp, vertical = 12.dp)
                Row(
                    Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MoJingTonalButton(onClick = onEdit, enabled = !busy,
                        modifier = Modifier.weight(1f), contentPadding = actionPadding) {
                        Text("编辑", style = style)
                    }
                    MoJingButton(onClick = onChat, enabled = !busy,
                        modifier = Modifier.weight(1.5f), contentPadding = actionPadding) {
                        Text(if (busy) "处理中…" else "开始对话", style = style)
                    }
                    MoJingTonalButton(onClick = onFavorite, enabled = !busy,
                        modifier = Modifier.weight(1f), contentPadding = actionPadding) {
                        Text(if (favorite) "已收藏" else "收藏", style = style)
                    }
                }
            }
        }
    }
}

@Composable
private fun CharacterDetailContent(
    character: CharacterEntity,
    encyclopediaName: String?,
    recentStories: List<com.mojing.app.data.local.entity.SessionEntity>,
    storyPageIndex: Int,
    storyHasNext: Boolean,
    storiesLoading: Boolean,
    storiesError: String?,
    onPreviousStoryPage: () -> Unit,
    onNextStoryPage: () -> Unit,
    onRetryStories: () -> Unit,
    busy: Boolean,
    onFavorite: () -> Unit,
    onOpenStory: (Long) -> Unit,
    modifier: Modifier,
    context: android.content.Context,
) {
    Column(
        modifier.clipToBounds().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val imagePath = character.cardImagePath.ifBlank { character.avatarImagePath }
                    if (imagePath.isNotBlank()) {
                        AsyncImage(
                            model = avatarImageModel(context, imagePath),
                            contentDescription = character.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(84.dp).clip(RoundedCornerShape(12.dp)),
                        )
                    } else {
                        Box(
                            Modifier.size(84.dp).clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) { Text(character.name.take(1).ifBlank { "?" }, style = MaterialTheme.typography.displaySmall) }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(character.name.ifBlank { "未命名角色" }, style = MaterialTheme.typography.headlineSmall)
                    }
                    IconButton(onClick = onFavorite, enabled = !busy) {
                        Icon(
                            if (character.favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                            contentDescription = if (character.favorite) "取消收藏" else "收藏",
                            tint = if (character.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(encyclopediaName?.ifBlank { null }?.let { "所属世界 · $it" } ?: "尚未绑定世界", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Text("人物设定", style = MaterialTheme.typography.titleMedium)
        Text(
            character.personaPrompt.ifBlank { "还没有写下人物设定。编辑角色，补充性格、背景与说话方式。" },
            style = MaterialTheme.typography.bodyLarge,
            color = if (character.personaPrompt.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        if (character.boundEncyclopediaId > 0L) {
            Text("关联资料", style = MaterialTheme.typography.titleMedium)
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Text(encyclopediaName ?: "已绑定世界", Modifier.fillMaxWidth().padding(14.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("相关故事", style = MaterialTheme.typography.titleMedium)
            if (storiesLoading) {
                androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("正在读取相关故事…", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (recentStories.isEmpty() && !storiesLoading && storiesError == null) {
                Text("还没有相关故事，可开始一段新对话", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            recentStories.forEach { story ->
                Surface(onClick = { onOpenStory(story.id) }, enabled = !busy,
                    shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(story.title.ifBlank { "未命名故事" }, style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(story.summary.ifBlank { "继续这段故事" }, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            storiesError?.let { error ->
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetryStories, enabled = !storiesLoading,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("重试读取相关故事") }
            }
            if (storyPageIndex > 0 || storyHasNext) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onPreviousStoryPage, enabled = !storiesLoading && storyPageIndex > 0) {
                        Text("上一页")
                    }
                    Text("第 ${storyPageIndex + 1} 页", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onNextStoryPage, enabled = !storiesLoading && storyHasNext) { Text("下一页") }
                }
            }
        }
    }
}
