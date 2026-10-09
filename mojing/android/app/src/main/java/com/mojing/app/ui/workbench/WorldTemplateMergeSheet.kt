package com.mojing.app.ui.workbench

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Visibility
import com.mojing.app.ui.common.MoJingButton as Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import com.mojing.app.ui.common.MoJingIcon as Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import com.mojing.app.ui.common.MoJingOutlinedButton as OutlinedButton
import com.mojing.app.ui.common.MoJingTextField as OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mojing.app.data.local.dao.EncyclopediaFilterOption
import com.mojing.app.domain.usecase.WorldMergeField
import com.mojing.app.domain.usecase.WorldTemplateMergeLoreRow
import com.mojing.app.domain.usecase.WorldTemplateMergePreview
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldTemplateMergeSheet(
    templateId: Long,
    onDismiss: () -> Unit,
    onMerged: (Long) -> Unit,
    viewModel: WorldTemplateMergeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var expandedField by remember { mutableStateOf<WorldMergeField?>(null) }
    var dismissing by remember { mutableStateOf(false) }

    LaunchedEffect(templateId) { viewModel.start(templateId) }
    DisposableEffect(viewModel) { onDispose { viewModel.release() } }

    fun dismissAfterCancellation() {
        if (dismissing) return
        dismissing = true
        scope.launch {
            viewModel.cancelAndJoin()
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = ::dismissAfterCancellation,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        modifier = Modifier.navigationBarsPadding(),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).imePadding()) {
            MergeSheetHeader(
                title = if (state.preview == null) "归入已有世界" else "预览归入内容",
                onBack = if (state.preview == null) null else viewModel::clearPreview,
                onClose = ::dismissAfterCancellation,
            )
            if (state.preview == null) {
                WorldTargetPicker(
                    modifier = Modifier.weight(1f),
                    state = state,
                    onQuery = viewModel::setSearchQuery,
                    onSelect = viewModel::selectWorld,
                    onPrevious = viewModel::previousWorldPage,
                    onNext = viewModel::nextWorldPage,
                    onRetry = viewModel::retryWorlds,
                    onRetryMapping = viewModel::retryMapping,
                    onRetryPreview = viewModel::retryPreview,
                    onCreateWorld = { viewModel.createWorld(onMerged) },
                )
            } else {
                MergePreviewContent(
                    modifier = Modifier.weight(1f),
                    state = state,
                    expandedField = expandedField,
                    onExpandField = { expandedField = if (expandedField == it) null else it },
                    onToggleField = viewModel::toggleField,
                    onRetryPreview = viewModel::retryPreview,
                    onRetryLore = viewModel::retryLore,
                    onPreviousLorePage = viewModel::previousLorePage,
                    onNextLorePage = viewModel::nextLorePage,
                )
            }
            if (state.preview != null) {
                Divider()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = ::dismissAfterCancellation, modifier = Modifier.semantics { contentDescription = "取消归入世界" }) { Text("取消") }
                    Button(
                        onClick = { viewModel.apply(onMerged) },
                        modifier = Modifier.semantics { contentDescription = "确认归入世界" },
                        enabled = !state.applying && state.preview != null && state.previewValid,
                    ) {
                        if (state.applying) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text("归入世界")
                    }
                }
            }
        }
    }
}

@Composable
private fun MergeSheetHeader(title: String, onBack: (() -> Unit)?, onClose: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回世界选择") }
        Column(Modifier.weight(1f).padding(horizontal = if (onBack == null) 8.dp else 0.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text("先预览，确认后才会写入世界", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onClose != null) TextButton(onClick = onClose, modifier = Modifier.semantics { contentDescription = "关闭归入世界" }) { Text("关闭") }
    }
}

@Composable
private fun WorldTargetPicker(
    modifier: Modifier = Modifier,
    state: WorldTemplateMergeState,
    onQuery: (String) -> Unit,
    onSelect: (Long) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRetry: () -> Unit,
    onRetryMapping: () -> Unit,
    onRetryPreview: () -> Unit,
    onCreateWorld: () -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Text("选择一个已有世界", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.titleMedium)
        Text("已有世界的名称和同名设定条目会保留。", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth().padding(16.dp).semantics { contentDescription = "搜索归入目标世界" },
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            label = { Text("搜索世界") },
            placeholder = { Text("按名称查找") },
            enabled = !state.applying,
        )
        if (state.worldsLoading && !state.worldsLoaded) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        } else if (state.previewError != null) {
            InlineProblem(state.previewError, onRetryPreview)
        } else if (state.worldsError != null) {
            InlineProblem(state.worldsError, onRetry)
        } else if (state.mappingError != null && state.worlds.isEmpty()) {
            InlineProblem(state.mappingError, onRetryMapping)
            EmptyWorlds(onCreateWorld)
        } else if (state.worlds.isEmpty()) {
            EmptyWorlds(onCreateWorld)
        } else {
            state.mappingError?.let { InlineProblem(it, onRetryMapping) }
            LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("世界模板合并内容")) {
                items(state.worlds, key = { it.id }) { world -> WorldTargetRow(world, onSelect) }
            }
            PagerControls(page = "第 ${state.worldsPage + 1} 页", hasPrevious = state.worldsPage > 0, hasNext = state.worldsHasNext, onPrevious = onPrevious, onNext = onNext)
        }
        OutlinedButton(onClick = onCreateWorld, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).semantics { contentDescription = "新建世界" }, enabled = !state.applying) {
            Icon(Icons.Outlined.AddCircleOutline, null)
            Spacer(Modifier.width(8.dp))
            Text("新建世界")
        }
    }
}

@Composable
private fun WorldTargetRow(world: EncyclopediaFilterOption, onSelect: (Long) -> Unit) {
    Surface(
        onClick = { onSelect(world.id) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .semantics { contentDescription = "归入目标世界:${world.id}" },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(world.name.ifBlank { "未命名世界" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("选择后查看合并预览", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.Visibility, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun MergePreviewContent(
    modifier: Modifier = Modifier,
    state: WorldTemplateMergeState,
    expandedField: WorldMergeField?,
    onExpandField: (WorldMergeField) -> Unit,
    onToggleField: (WorldMergeField) -> Unit,
    onRetryPreview: () -> Unit,
    onRetryLore: () -> Unit,
    onPreviousLorePage: () -> Unit,
    onNextLorePage: () -> Unit,
) {
    val preview = state.preview ?: return
    LazyColumn(modifier.fillMaxWidth().testTag("世界模板合并内容"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("归入「${preview.targetWorld.name.ifBlank { "未命名世界" }}」", style = MaterialTheme.typography.titleMedium)
                Text("会新增 ${preview.loreToAdd} 个条目，保留 ${preview.loreConflicts} 个同名条目（已有内容优先）。", style = MaterialTheme.typography.bodyMedium)
                Text("目标世界名称永不覆盖；覆盖字段默认全部关闭。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Text("可选字段覆盖", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        items(WorldMergeField.entries.toList(), key = { it.name }) { field ->
            FieldOption(
                field = field,
                selected = field in state.selectedFields,
                expanded = expandedField == field,
                template = fieldText(preview, field),
                    current = currentFieldText(preview, field),
                    enabled = !state.applying,
                onToggle = { onToggleField(field) },
                onExpand = { onExpandField(field) },
            )
        }
        item {
            Text("条目预览", Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        if (state.loreError != null) item { InlineProblem(state.loreError, onRetryLore) }
        else if (state.loreLoading && state.lore.isEmpty()) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) }
        else if (state.lore.isEmpty()) item { Text("没有可新增的条目。", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        else items(state.lore, key = { it.id }) { LoreRow(it) }
        if (state.loreHasPrevious || state.loreHasNext) item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = onPreviousLorePage, enabled = state.loreHasPrevious && !state.loreLoading) { Text("上一页") }
                TextButton(onClick = onNextLorePage, enabled = state.loreHasNext && !state.loreLoading) { Text(if (state.loreLoading) "读取中…" else "下一页") }
            }
        }
        if (state.applyError != null) item { InlineProblem(state.applyError, onRetryPreview) }
    }
}

@Composable
private fun FieldOption(field: WorldMergeField, selected: Boolean, expanded: Boolean, template: String, current: String, enabled: Boolean, onToggle: () -> Unit, onExpand: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = selected, onCheckedChange = { if (enabled) onToggle() }, enabled = enabled, modifier = Modifier.semantics { contentDescription = "采用模板字段:${field.name}" })
            Text(fieldLabel(field), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = onExpand, enabled = enabled) { Text(if (expanded) "收起" else "查看") }
        }
        if (expanded) {
            val shown = if (field == WorldMergeField.COVER) {
                "当前：${if (current.isBlank()) "未设置" else "已设置"}\n模板：${if (template.isBlank()) "未设置" else "可复用模板封面"}"
            } else {
                "当前：${current.take(240).ifBlank { "（空）" }}\n模板：${template.take(240).ifBlank { "（空）" }}"
            }
            Text(shown, Modifier.padding(start = 48.dp, end = 8.dp, bottom = 8.dp), maxLines = 12, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LoreRow(row: WorldTemplateMergeLoreRow) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (row.conflict) Icons.Outlined.CheckCircle else Icons.Outlined.AddCircleOutline, null, Modifier.size(20.dp), tint = if (row.conflict) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(row.title.ifBlank { "未命名条目" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(if (row.conflict) "同名：保留已有条目" else displayEntryType(row.entryType), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun displayEntryType(value: String): String = when (value) {
    "character" -> "人物"
    "location" -> "地点"
    "faction" -> "势力"
    "event" -> "事件"
    "item" -> "物品"
    "skill" -> "技能"
    "profession" -> "职业"
    "concept" -> "概念"
    "species" -> "种族"
    "world" -> "世界"
    "timeline" -> "时间线"
    else -> value.ifBlank { "设定" }
}

private fun fieldLabel(field: WorldMergeField) = when (field) {
    WorldMergeField.DESCRIPTION -> "简介"
    WorldMergeField.WORLD_PROMPT -> "世界规则"
    WorldMergeField.GAMEPLAY_MODE -> "玩法"
    WorldMergeField.ANTI_CHEAT_PROMPT -> "约束"
    WorldMergeField.COVER -> "封面"
}

private fun fieldText(preview: WorldTemplateMergePreview, field: WorldMergeField) = when (field) {
    WorldMergeField.DESCRIPTION -> preview.template.summary
    WorldMergeField.WORLD_PROMPT -> preview.template.worldPrompt
    WorldMergeField.GAMEPLAY_MODE -> preview.template.gameplayMode
    WorldMergeField.ANTI_CHEAT_PROMPT -> preview.template.antiCheatPrompt
    WorldMergeField.COVER -> preview.template.coverImagePath
}

private fun currentFieldText(preview: WorldTemplateMergePreview, field: WorldMergeField) = when (field) {
    WorldMergeField.DESCRIPTION -> preview.targetWorld.description
    WorldMergeField.WORLD_PROMPT -> preview.targetWorld.worldPrompt
    WorldMergeField.GAMEPLAY_MODE -> preview.targetWorld.gameplayMode
    WorldMergeField.ANTI_CHEAT_PROMPT -> preview.targetWorld.antiCheatPrompt
    WorldMergeField.COVER -> preview.targetWorld.coverImagePath
}

@Composable
private fun InlineProblem(message: String?, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
            Text(message ?: "读取失败", Modifier.padding(start = 8.dp).weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onRetry) { Icon(Icons.Outlined.Refresh, null); Text("重试", Modifier.padding(start = 4.dp)) }
        }
    }
}

@Composable
private fun EmptyWorlds(onCreateWorld: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("还没有匹配的世界", style = MaterialTheme.typography.bodyLarge)
        Text("可以新建世界，再从模板开始。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onCreateWorld) { Text("新建世界") }
    }
}

@Composable
private fun PagerControls(page: String, hasPrevious: Boolean, hasNext: Boolean, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(page, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onPrevious, enabled = hasPrevious) { Text("上一页") }
        TextButton(onClick = onNext, enabled = hasNext) { Text("下一页") }
    }
}
