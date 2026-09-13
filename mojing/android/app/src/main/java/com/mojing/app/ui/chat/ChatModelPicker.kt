package com.mojing.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    val tabs = rememberLazyListState(initialFirstVisibleItemIndex =
        platforms.indexOfFirst { it.id == platform?.id }.coerceAtLeast(0))
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.78f
    ModalBottomSheet(onDismissRequest = onDismiss, dragHandle = null,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).padding(horizontal = 12.dp, vertical = 8.dp)) {
            ModelPickerHeader("对话模型", query, { query = it }, onDismiss)
            if (platforms.isNotEmpty()) {
                LazyRow(state = tabs, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.testTag("chat-model-platforms")) {
                    items(platforms, key = { it.id }) { item ->
                        FilterChip(selected = item.id == platform?.id, onClick = {
                            platformId = item.id
                            query = ""
                        }, label = {
                            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
                        }, modifier = Modifier.heightIn(min = 48.dp).testTag("chat-platform:${item.id}"))
                    }
                }
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
            key(platform?.id, query) {
                LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth().testTag("chat-model-list"),
                    contentPadding = PaddingValues(vertical = 4.dp)) {
                    if (names.isEmpty()) item {
                        Text(if (query.isNotBlank()) "没有匹配的模型，试试其他关键词。"
                            else "请在模型设置中添加平台和模型。", Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    items(names, key = { it }) { name ->
                        val activePlatform = requireNotNull(platform)
                        val isSelected = selectedModel == (activePlatform.id to name)
                        TextButton(onClick = { onSelect(activePlatform.id, name) }, enabled = !isSaving && activePlatform.apiKey.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .testTag("chat-model:${activePlatform.id}:$name").semantics { selected = isSelected },
                            shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                            colors = ButtonDefaults.textButtonColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                                contentColor = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface),
                        ) {
                            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            if (isSelected) Icon(Icons.Default.Check, "已选择", Modifier.padding(start = 8.dp).size(18.dp))
                        }
                    }
                }
            }
            if (onFollowSettings != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                TextButton(onClick = onFollowSettings, enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("chat-model-follow-settings")
                        .semantics { selected = selectedModel == null && selectedLabel == "跟随角色与模型设置" }) {
                    Text("跟随角色与模型设置", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    if (selectedModel == null && selectedLabel == "跟随角色与模型设置") Icon(Icons.Default.Check, "已选择", Modifier.size(18.dp))
                }
            }
        }
    }
}
