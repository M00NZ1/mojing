package com.mojing.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlatformEditorDialog(onDismissRequest: () -> Unit, title: @Composable () -> Unit,
    text: @Composable () -> Unit, confirmButton: @Composable () -> Unit, dismissButton: @Composable () -> Unit,
    error: String? = null) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.92f).dp
    Dialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 12.dp)
            .heightIn(max = maxHeight).imePadding(),
            shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                    ProvideTextStyle(MaterialTheme.typography.titleMedium) { title() }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.weight(1f, fill = false).padding(horizontal = 16.dp)) { text() }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        error?.let { message ->
                            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(10.dp)) {
                                Text(message, Modifier.fillMaxWidth().padding(12.dp)
                                    .semantics { liveRegion = LiveRegionMode.Polite },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, androidx.compose.ui.Alignment.End),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            dismissButton()
                            confirmButton()
                        }
                    }
                }
            }
        }
    }
}
