package com.mojing.app.ui.encyclopedia.meta

import com.mojing.app.ui.common.MoJingTextField as OutlinedTextField
import com.mojing.app.ui.common.MoJingButton as Button
import com.mojing.app.ui.common.MoJingOutlinedButton as OutlinedButton

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

private fun jsonElementEditString(el: JsonElement): String =
    when {
        el.isJsonPrimitive -> {
            val p = el.asJsonPrimitive
            if (p.isString) p.asString else p.toString()
        }
        else -> el.toString()
    }

private fun jsonObjStr(o: JsonObject, k: String): String =
    o.get(k)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.asString ?: ""

private fun ensureCustomFieldsArray(root: JsonObject): JsonArray {
    val ex = root.get("custom_fields")
    if (ex != null && ex.isJsonArray) return ex.asJsonArray
    val arr = JsonArray()
    root.add("custom_fields", arr)
    return arr
}

private fun removeJsonArrayAt(root: JsonObject, key: String, index: Int) {
    val arr = root.getAsJsonArray(key) ?: return
    val n = JsonArray()
    for (j in 0 until arr.size()) {
        if (j != index) n.add(arr[j])
    }
    if (n.size() == 0) root.remove(key) else root.add(key, n)
}

/** 已弃用在表单展示的来源键等（仍可留在历史 JSON 中，不在编辑页出现） */
private val META_JSON_UI_HIDDEN_KEYS = setOf("source_license")

private fun swapJsonArrayElements(arr: JsonArray, i: Int, j: Int) {
    if (i < 0 || j < 0 || i >= arr.size() || j >= arr.size()) return
    val a = arr[i]
    val b = arr[j]
    arr.set(i, b)
    arr.set(j, a)
}

/** 对象数组：元素均为 JSON 对象时，以列表展示并支持上移 / 下移排序。 */
private fun isJsonArrayOfObjects(arr: JsonArray): Boolean {
    if (arr.size() == 0) return true
    for (i in 0 until arr.size()) {
        if (!arr[i].isJsonObject) return false
    }
    return true
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EntryEditMetaSection(
    entryType: String,
    metaJson: String,
    onMetaJsonChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fields = remember(entryType) { EncyclopediaMetaDefinitions.fieldsFor(entryType) }
    val knownKeys = remember(entryType) { fields.map { it.key }.toSet() }
    var root by remember { mutableStateOf(JsonObject()) }
    LaunchedEffect(metaJson) {
        root = runCatching { JsonParser.parseString(metaJson.ifBlank { "{}" }).asJsonObject }.getOrElse { JsonObject() }
    }

    fun commit() {
        onMetaJsonChange(root.toString())
    }

    fun getEl(key: String): JsonElement? = root.get(key)

    fun setPrimitive(key: String, text: String) {
        if (text.isBlank()) root.remove(key)
        else root.addProperty(key, text)
        commit()
    }

    fun tagsList(key: String): List<String> {
        val el = getEl(key) ?: return emptyList()
        if (el.isJsonArray) {
            return el.asJsonArray.mapNotNull { j ->
                if (j.isJsonPrimitive) j.asJsonPrimitive.asString.trim().takeIf { it.isNotEmpty() } else null
            }
        }
        if (el.isJsonPrimitive) {
            return el.asJsonPrimitive.asString.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
        return emptyList()
    }

    fun setTags(key: String, list: List<String>) {
        if (list.isEmpty()) root.remove(key)
        else {
            val arr = JsonArray()
            list.forEach { arr.add(it) }
            root.add(key, arr)
        }
        commit()
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("结构化 Meta（按条目类型预设字段）", style = MaterialTheme.typography.titleSmall)
        fields.forEach { f ->
            when (f.kind) {
                MetaFieldKind.Text -> {
                    val v = getEl(f.key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.asString ?: ""
                    OutlinedTextField(
                        value = v,
                        onValueChange = { setPrimitive(f.key, it) },
                        label = { Text(f.label) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                MetaFieldKind.TextArea -> {
                    val v = getEl(f.key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.asString ?: ""
                    OutlinedTextField(
                        value = v,
                        onValueChange = { setPrimitive(f.key, it) },
                        label = { Text(f.label) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                }
                MetaFieldKind.Number -> {
                    val raw = getEl(f.key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.asString ?: ""
                    OutlinedTextField(
                        value = raw,
                        onValueChange = { nv ->
                            if (nv.isBlank()) root.remove(f.key)
                            else {
                                val n = nv.toIntOrNull() ?: nv.toDoubleOrNull()
                                if (n != null) root.addProperty(f.key, n)
                                else root.addProperty(f.key, nv)
                            }
                            commit()
                        },
                        label = { Text(f.label) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                MetaFieldKind.Select -> {
                    var expanded by remember(f.key, entryType) { mutableStateOf(false) }
                    val cur = getEl(f.key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.asString ?: ""
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                        OutlinedTextField(
                            value = cur,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(f.label) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            f.options.forEach { opt ->
                                val shown = f.optionLabels[opt] ?: opt
                                DropdownMenuItem(
                                    text = { Text(shown) },
                                    onClick = {
                                        setPrimitive(f.key, opt)
                                        expanded = false
                                    },
                                )
                            }
                        }
                    }
                }
                MetaFieldKind.Tags -> {
                    val tags = tagsList(f.key)
                    var draft by remember(f.key, tags, metaJson) { mutableStateOf("") }
                    Column {
                        Text(f.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            tags.forEach { t ->
                                AssistChip(
                                    onClick = { setTags(f.key, tags.filter { x -> x != t }) },
                                    label = { Text(t) },
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = draft,
                                onValueChange = { draft = it },
                                label = { Text("新标签") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            Button(
                                onClick = {
                                    val v = draft.trim()
                                    if (v.isNotEmpty()) {
                                        setTags(f.key, tags + v)
                                        draft = ""
                                    }
                                },
                            ) { Text("添加") }
                        }
                    }
                }
            }
        }

        val extras = root.keySet().filter {
            it !in knownKeys && it != "custom_fields" && it !in META_JSON_UI_HIDDEN_KEYS
        }.sorted()
        if (extras.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text("扩展字段", style = MaterialTheme.typography.titleSmall)
            extras.forEach { k ->
                val el = root.get(k) ?: return@forEach
                if (el.isJsonArray && isJsonArrayOfObjects(el.asJsonArray)) {
                    val arr = el.asJsonArray
                    Text(k, style = MaterialTheme.typography.labelLarge)
                    if (arr.size() == 0) {
                        Text("（暂无条目）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    (0 until arr.size()).forEach { idx ->
                        val row = arr[idx].asJsonObject
                        Text("第 ${idx + 1} 项", style = MaterialTheme.typography.labelMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    swapJsonArrayElements(arr, idx, idx - 1)
                                    commit()
                                },
                                enabled = idx > 0,
                                modifier = Modifier.weight(1f),
                            ) { Text("上移") }
                            OutlinedButton(
                                onClick = {
                                    swapJsonArrayElements(arr, idx, idx + 1)
                                    commit()
                                },
                                enabled = idx < arr.size() - 1,
                                modifier = Modifier.weight(1f),
                            ) { Text("下移") }
                        }
                        OutlinedTextField(
                            value = jsonElementEditString(row),
                            onValueChange = { text ->
                                if (text.isBlank()) {
                                    removeJsonArrayAt(root, k, idx)
                                } else {
                                    val parsed = runCatching { JsonParser.parseString(text) }.getOrNull()
                                    if (parsed != null && parsed.isJsonObject) {
                                        arr.set(idx, parsed.asJsonObject)
                                    }
                                }
                                commit()
                            },
                            label = { Text("对象 JSON") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 14,
                        )
                        OutlinedButton(
                            onClick = {
                                removeJsonArrayAt(root, k, idx)
                                commit()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("删除此项") }
                        Spacer(Modifier.height(4.dp))
                    }
                    OutlinedButton(
                        onClick = {
                            arr.add(JsonObject())
                            commit()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("+ 添加一项") }
                    Spacer(Modifier.height(8.dp))
                } else {
                    OutlinedTextField(
                        value = jsonElementEditString(el),
                        onValueChange = { text ->
                            if (text.isBlank()) {
                                root.remove(k)
                            } else {
                                val parsed = runCatching { JsonParser.parseString(text) }.getOrNull()
                                if (parsed != null) root.add(k, parsed)
                                else root.addProperty(k, text)
                            }
                            commit()
                        },
                        label = { Text(k) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 1,
                        maxLines = 8,
                    )
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Text("自定义字段 (custom_fields)", style = MaterialTheme.typography.titleSmall)
        val cfArr = ensureCustomFieldsArray(root)
        (0 until cfArr.size()).forEach { idx ->
            val el = cfArr[idx]
            val row = if (el.isJsonObject) {
                el.asJsonObject
            } else {
                val fixed = JsonObject()
                cfArr.set(idx, fixed)
                commit()
                fixed
            }
            Text("条目 ${idx + 1}", style = MaterialTheme.typography.labelMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        swapJsonArrayElements(cfArr, idx, idx - 1)
                        commit()
                    },
                    enabled = idx > 0,
                    modifier = Modifier.weight(1f),
                ) { Text("上移") }
                OutlinedButton(
                    onClick = {
                        swapJsonArrayElements(cfArr, idx, idx + 1)
                        commit()
                    },
                    enabled = idx < cfArr.size() - 1,
                    modifier = Modifier.weight(1f),
                ) { Text("下移") }
            }
            OutlinedTextField(
                value = jsonObjStr(row, "key"),
                onValueChange = { row.addProperty("key", it); commit() },
                label = { Text("字段键 key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = jsonObjStr(row, "label"),
                onValueChange = { row.addProperty("label", it); commit() },
                label = { Text("显示名 label") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = jsonObjStr(row, "value"),
                onValueChange = { row.addProperty("value", it); commit() },
                label = { Text("字段值 value") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            OutlinedTextField(
                value = jsonObjStr(row, "source"),
                onValueChange = { row.addProperty("source", it); commit() },
                label = { Text("来源/备注 source") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedButton(
                onClick = {
                    removeJsonArrayAt(root, "custom_fields", idx)
                    commit()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("删除此条") }
            Spacer(Modifier.height(4.dp))
        }
        OutlinedButton(
            onClick = {
                val o = JsonObject()
                o.addProperty("key", "")
                o.addProperty("label", "")
                o.addProperty("value", "")
                o.addProperty("source", "")
                cfArr.add(o)
                commit()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("+ 添加自定义字段") }
    }
}
