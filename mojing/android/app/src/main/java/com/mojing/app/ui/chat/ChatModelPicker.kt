package com.mojing.app.ui.chat

import com.mojing.app.ui.common.MoJingIcon as Icon
import androidx.compose.material.icons.outlined.Check
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mojing.app.data.ModelPlatform
import com.mojing.app.ui.common.ModelPickerHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatModelPicker(
    platforms: List<ModelPlatform>, onDismiss: () -> Unit, selectedLabel: String = "",
    lastRequestModel: String? = null, isGenerating: Boolean = false, isSaving: Boolean = false,
    saveError: String? = null, selectedModel: Pair<String, String>? = null,
    onFollowSettings: (() -> Unit)? = null, lastRequestPlatform: String? = null,
    onSelect: (String, String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var platformId by rememberSaveable {
        mutableStateOf(selectedModel?.first ?: platforms.firstOrNull { it.apiKey.isNotBlank() && it.models.isNotEmpty() }?.id)
    }
    val platform = platforms.firstOrNull { it.id == platformId } ?: platforms.firstOrNull()
    val names = remember(platform, query) {
        platform?.models.orEmpty().distinct().filter { it.contains(query.trim(), ignoreCase = true) }
    }
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.78f
    ModalBottomSheet(
        scrimColor = androidx.compose.material3.MaterialTheme.colorScheme.scrim.copy(alpha = 0.42f),onDismissRequest = onDismiss, dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).padding(horizontal = 12.dp, vertical = 8.dp)) {
            ModelPickerHeader("对话模型", query, { query = it }, onDismiss)
            if (platforms.isNotEmpty()) {
                com.mojing.app.ui.common.PlatformTabs(platforms, platform?.id, onSelect = {
                    platformId = it
                    query = ""
                }, modifier = Modifier.testTag("chat-model-platforms"), itemTagPrefix = "chat-platform:")
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (isSaving) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("正在保存模型选择…", style = MaterialTheme.typography.bodySmall)
            } else if (saveError != null) {
                Text(saveError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            // Bound request status so long provider/model names do not displace the list.
            if (selectedLabel.isNotBlank()) Text("下次发送：$selectedLabel",
                Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (lastRequestModel != null || isGenerating) Text(
                lastRequestModel?.let { "最近请求：" + listOfNotNull(lastRequestPlatform?.takeIf(String::isNotBlank), it).joinToString(" · ") }
                    ?: "本轮正在准备上下文",
                Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (platform?.apiKey?.isBlank() == true) Text("请先在模型设置中配置此平台的 Key",
                Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (query.isBlank()) "可用模型" else "搜索结果", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${names.size}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            key(platform?.id, query) {
                val initialIndex = if (query.isBlank() && selectedModel?.first == platform?.id) (selectedModel?.second?.let(names::indexOf) ?: -1).coerceAtLeast(0) else 0
                val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
                LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth().testTag("chat-model-list"),
                    state = listState, contentPadding = PaddingValues(vertical = 4.dp)) {
                    if (names.isEmpty()) item {
                        Text(if (query.isNotBlank()) "没有匹配的模型，试试其他关键词。"
                            else "请在模型设置中添加平台和模型。", Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    items(names, key = { it }) { name ->
                        val activePlatform = requireNotNull(platform)
                        val isSelected = selectedModel == (activePlatform.id to name)
                        com.mojing.app.ui.common.ModelOptionRow(name, isSelected,
                            onClick = { onSelect(activePlatform.id, name) }, enabled = !isSaving && activePlatform.apiKey.isNotBlank(),
                            modifier = Modifier.testTag("chat-model:${activePlatform.id}:$name"), query = query)
                    }
                }
            }
            if (onFollowSettings != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                TextButton(onClick = onFollowSettings, enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("chat-model-follow-settings")
                        .semantics { selected = selectedModel == null && selectedLabel == "跟随角色与模型设置" }) {
                    Text("跟随角色与模型设置", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    if (selectedModel == null && selectedLabel == "跟随角色与模型设置") Icon(Icons.Outlined.Check, "已选择", Modifier.size(18.dp))
                }
            }
        }
    }
}
