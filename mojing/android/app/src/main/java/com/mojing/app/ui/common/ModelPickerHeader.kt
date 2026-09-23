package com.mojing.app.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Search replaces the title row instead of adding a second toolbar. */
@Composable
internal fun ModelPickerHeader(title: String, query: String, onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit, searchLabel: String = "模型") {
    var searching by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val collapseSearch = {
        focusManager.clearFocus()
        onQueryChange("")
        searching = false
    }
    BackHandler(enabled = searching) { collapseSearch() }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        if (searching) {
            IconButton(onClick = collapseSearch) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "收起搜索") }
            MoJingTextField(query, onQueryChange,
                modifier = Modifier.weight(1f).focusRequester(focusRequester).testTag("model-picker-search"),
                placeholder = { Text("搜索$searchLabel") }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            )
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
        } else {
            Text(title, Modifier.weight(1f).padding(start = 8.dp),
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = { searching = true }) { Icon(Icons.Default.Search, "搜索$searchLabel") }
        }
        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭") }
    }
}
