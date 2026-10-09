package com.mojing.app.ui.generation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mojing.app.domain.generation.GenerationResultSnapshot
import com.mojing.app.ui.common.MoJingButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GenerationResultApplySheet(state: SnapshotApplicationState, onDismiss: () -> Unit, onRefresh: () -> Unit, onApply: () -> Unit) {
    if (state.taskId == null) return
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = 720.dp, contentWindowInsets = { WindowInsets.safeDrawing }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("应用生成结果", style = MaterialTheme.typography.titleLarge)
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.preview?.let { preview ->
                    if (!preview.targetExists) Text("目标已删除，生成结果仍保留在记录中。")
                    else {
                        Text("当前内容", style = MaterialTheme.typography.titleSmall)
                        SelectionContainer { Text(preview.currentPersona ?: listOfNotNull(preview.currentSummary, preview.currentWorld).filter(String::isNotBlank).joinToString("\n\n")) }
                    }
                    HorizontalDivider()
                    Text("生成内容", style = MaterialTheme.typography.titleSmall)
                    SelectionContainer { Text(when (val result = preview.result) {
                        is GenerationResultSnapshot.CharacterPersona -> result.personaPrompt
                        is GenerationResultSnapshot.WorldTemplate -> listOfNotNull(result.summary, result.worldPrompt).filter(String::isNotBlank).joinToString("\n\n")
                    }) }
                    if (preview.alreadyApplied) Text("此结果已经应用")
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss, enabled = !state.applying) { Text("取消") }
                if (state.error != null) TextButton(onClick = onRefresh, enabled = !state.applying) { Text("刷新当前内容") }
                else MoJingButton(onClick = onApply, enabled = !state.loading && !state.applying && state.preview?.let { it.targetExists && !it.alreadyApplied } == true,
                    modifier = Modifier.weight(1f)) { Text(if (state.applying) "应用中…" else "确认应用") }
            }
        }
    }
}
