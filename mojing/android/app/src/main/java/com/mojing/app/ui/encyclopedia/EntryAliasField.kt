package com.mojing.app.ui.encyclopedia

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.mojing.app.ui.encyclopedia.meta.EncyclopediaMetaDefinitions

/** Edit the existing alias key without replacing unrelated metadata or malformed raw JSON. */
@Composable
internal fun EntryAliasField(entryType: String, metaJson: String, onChange: (String) -> Unit, enabled: Boolean) {
    val field = remember(entryType) { EncyclopediaMetaDefinitions.fieldsFor(entryType).firstOrNull { it.key == "alias" } }
        ?: return
    val root = remember(metaJson) { runCatching { JsonParser.parseString(metaJson.ifBlank { "{}" }).asJsonObject }.getOrNull() }
    val value = remember(metaJson) {
        val alias = root?.get("alias")
        when {
            alias == null || alias.isJsonNull -> ""
            alias.isJsonArray -> alias.asJsonArray.filter { it.isJsonPrimitive }.joinToString(", ") { it.asString }
            alias.isJsonPrimitive -> alias.asString
            else -> ""
        }
    }
    var input by remember(entryType) { mutableStateOf(value) }
    LaunchedEffect(value) {
        fun parts(text: String) = text.split(',', '，').map(String::trim).filter(String::isNotEmpty)
        if (parts(input) != parts(value)) input = value
    }
    com.mojing.app.ui.common.MoJingTextField(
        value = input,
        onValueChange = { text ->
            input = text
            root?.deepCopy()?.let { next ->
                val aliases = text.split(',', '，').map(String::trim).filter(String::isNotEmpty)
                if (aliases.isEmpty()) next.remove("alias")
                else next.add("alias", JsonArray().apply { aliases.forEach(::add) })
                onChange(next.toString())
            }
        },
        label = { Text("${field.label}（可选）") },
        placeholder = { Text("多个名称用逗号分隔") },
        enabled = enabled && root != null,
        supportingText = if (root == null) { { Text("请先在高级设置修正 Meta JSON") } } else null,
        modifier = Modifier.fillMaxWidth(), singleLine = true,
    )
}
