package com.mojing.app.ui.session

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.WorldTemplateEntity
import com.mojing.app.ui.common.ModelPickerHeader
import com.mojing.app.ui.common.MoJingButton

@Composable
internal fun NewSessionWorldPicker(
    encyclopedias: List<EncyclopediaEntity>,
    legacyTemplates: List<WorldTemplateEntity>,
    selectedEncyclopediaId: Long?,
    selectedTemplateId: Long?,
    onSelect: (Long?, WorldTemplateEntity?) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val matches = remember(encyclopedias, legacyTemplates, query) {
        val term = query.trim()
        Pair(encyclopedias.filter { it.name.contains(term, ignoreCase = true) },
            legacyTemplates.filter { it.label.contains(term, ignoreCase = true) })
    }
    val showUnbound = "不绑定世界".contains(query.trim(), ignoreCase = true)
    PickerDialogFrame("选择世界", query, { query = it }, "世界", onDismiss) {
        if (!showUnbound && matches.first.isEmpty() && matches.second.isEmpty()) {
            Text("没有匹配的世界", Modifier.fillMaxWidth().padding(20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                if (showUnbound) item(key = "unbound") {
                    WorldOptionRow("不绑定世界", "从角色开始，自由展开故事",
                        selectedEncyclopediaId == null && selectedTemplateId == null) {
                        onSelect(null, null)
                    }
                }
                items(matches.first, key = { "encyclopedia-${it.id}" }) { world ->
                    WorldOptionRow(world.name, "世界百科 · ${world.entryCount} 条资料",
                        selectedEncyclopediaId == world.id) { onSelect(world.id, null) }
                }
                items(matches.second, key = { "template-${it.id}" }) { template ->
                    WorldOptionRow(template.label, "旧世界资料", selectedTemplateId == template.id) {
                        onSelect(null, template)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NewSessionCharacterPicker(
    characters: List<CharacterEntity>,
    selectedIds: Set<Long>,
    onSelectionChange: (Set<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val matches = remember(characters, query) {
        characters.filter { it.name.contains(query.trim(), ignoreCase = true) }
    }
    val matchIds = remember(matches) { matches.mapTo(mutableSetOf()) { it.id } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().widthIn(max = 560.dp).padding(horizontal = 16.dp)
            .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.78f).imePadding(),
            shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    ModelPickerHeader("参与角色 · ${selectedIds.size} 人", query, { query = it }, onDismiss,
                        searchLabel = "角色")
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (matches.isEmpty()) {
                    Text("没有匹配的角色", Modifier.fillMaxWidth().padding(20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                        items(matches, key = { it.id }) { character ->
                            val selected = character.id in selectedIds
                            ListItem(
                                modifier = Modifier.fillMaxWidth().toggleable(value = selected,
                                    role = Role.Checkbox, onValueChange = { checked ->
                                        onSelectionChange(if (checked) selectedIds + character.id else selectedIds - character.id)
                                    }),
                                headlineContent = { Text(character.name.ifBlank { "未命名" },
                                    maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                trailingContent = { Checkbox(checked = selected, onCheckedChange = null) },
                                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                            )
                            HorizontalDivider(Modifier.padding(horizontal = 20.dp),
                                color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    TextButton(enabled = matches.isNotEmpty(), onClick = {
                        onSelectionChange(if (matchIds.all { it in selectedIds }) selectedIds - matchIds
                            else selectedIds + matchIds)
                    }) {
                        Text(if (matchIds.all { it in selectedIds }) "取消选中当前结果" else "全选当前结果")
                    }
                    Spacer(Modifier.weight(1f))
                    MoJingButton(onClick = onDismiss) { Text("完成") }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickerDialogFrame(
    title: String,
    query: String,
    onQueryChange: (String) -> Unit,
    searchLabel: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().widthIn(max = 560.dp).padding(horizontal = 16.dp)
            .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.78f).imePadding(),
            shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    ModelPickerHeader(title, query, onQueryChange, onDismiss, searchLabel)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                content()
            }
        }
    }
}

@Composable
private fun WorldOptionRow(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.fillMaxWidth().selectable(selected = selected,
            role = Role.RadioButton, onClick = onClick),
        headlineContent = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(detail, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        trailingContent = { RadioButton(selected = selected, onClick = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
}
