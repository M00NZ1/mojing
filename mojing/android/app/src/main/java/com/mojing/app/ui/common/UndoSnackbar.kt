package com.mojing.app.ui.common

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun rememberUndoSnackbarState(): SnackbarHostState = androidx.compose.runtime.remember { SnackbarHostState() }

fun SnackbarHostState.showUndo(message: String, onUndo: () -> Unit, scope: kotlinx.coroutines.CoroutineScope) {
    scope.launch {
        val result = showSnackbar(
            message = message,
            actionLabel = "撤销",
            duration = SnackbarDuration.Short
        )
        if (result == SnackbarResult.ActionPerformed) onUndo()
    }
}
