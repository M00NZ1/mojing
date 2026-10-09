package com.mojing.app.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.launch

/**
 * Defers one navigation request while an export is active.
 *
 * The first request owns the pending action. Export completion dismisses the
 * question without navigating, so completion or write errors remain visible.
 */
@Composable
fun rememberExportNavigationGuard(
    exporting: () -> Boolean,
    onStopExport: suspend () -> Unit,
): ((() -> Unit) -> Unit) {
    val currentExporting = rememberUpdatedState(exporting)
    val currentStopExport = rememberUpdatedState(onStopExport)
    val pendingAction = remember { mutableStateOf<(() -> Unit)?>(null) }
    val dialogVisible = remember { mutableStateOf(false) }
    val stopping = remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val isExporting = currentExporting.value()

    LaunchedEffect(isExporting, stopping.value) {
        if (!isExporting && !stopping.value) {
            pendingAction.value = null
            dialogVisible.value = false
        }
    }

    if ((pendingAction.value != null || stopping.value) && dialogVisible.value) {
        AlertDialog(
            shape = RoundedCornerShape(16.dp),
            onDismissRequest = { if (!stopping.value) {
                pendingAction.value = null
                dialogVisible.value = false
            } },
            title = { Text("正在导出") },
            text = { Text(if (stopping.value) "正在停止导出…" else "离开会中断导出，目标文件可能不完整。") },
            confirmButton = {
                TextButton(onClick = {
                    if (stopping.value) return@TextButton
                    val action = pendingAction.value
                    pendingAction.value = null
                    if (action != null) {
                        stopping.value = true
                        scope.launch {
                            try {
                                currentStopExport.value()
                                stopping.value = false
                                dialogVisible.value = false
                                action()
                            } finally {
                                stopping.value = false
                                dialogVisible.value = false
                            }
                        }
                    }
                }, enabled = !stopping.value) { Text(if (stopping.value) "正在停止…" else "停止并离开") }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (stopping.value) return@TextButton
                    pendingAction.value = null
                    dialogVisible.value = false
                }, enabled = !stopping.value) { Text("继续导出") }
            },
        )
    }

    return remember {
        { action: () -> Unit ->
            if (!stopping.value) {
                if (currentExporting.value()) {
                    if (pendingAction.value == null) pendingAction.value = action
                    dialogVisible.value = true
                } else {
                    action()
                }
            }
        }
    }
}
