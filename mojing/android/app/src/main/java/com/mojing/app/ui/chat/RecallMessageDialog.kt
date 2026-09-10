package com.mojing.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mojing.app.data.local.dao.MessageRecallImpact
import kotlinx.coroutines.CancellationException

@Composable
internal fun RecallMessageDialog(
    content: String,
    loadImpact: suspend () -> MessageRecallImpact,
    onConfirm: ((Boolean) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    speakerType: String? = null,
) {
    val preview = remember(content, speakerType) { ChatMessageTextFormat.preview(content, speakerType, 240) }
    var impact by remember { mutableStateOf<MessageRecallImpact?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(revision) {
        loading = true
        try {
            impact = loadImpact()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            impact = null
            failure = "暂时无法检查撤回影响，请重试。"
        } finally {
            loading = false
        }
    }
    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        title = { Text("撤回这条消息？") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(preview, style = MaterialTheme.typography.bodyMedium)
                if (loading) Text("正在检查故事线引用…")
                impact?.let { current ->
                    if (!current.canRecall) {
                        Text("需要保留这条消息", style = MaterialTheme.typography.titleSmall)
                        Text(current.reason)
                        current.references.forEach { reference ->
                            Text("${if (reference.isCheckpoint) "检查点" else "故事线"} · ${reference.label}")
                        }
                        if (current.referenceCount > current.references.size) Text("另有 ${current.referenceCount - current.references.size} 个引用。")
                    } else {
                        Text("撤回后无法恢复，会影响共享这段原文的故事线。相关收藏和自动记忆会更新；未被其他消息使用的本地附件也会清理。")
                        if (current.affectedSummaryCount > 0) Text("将重新整理 ${current.affectedSummaryCount} 段自动摘要，后续对话会逐批补齐；手动纠正会保留。")
                        if (current.removesDerivedMessages) Text("有明确生成标记的附属媒体消息也会一起撤回。")
                        if (current.maySelectRemainingReply) Text("同组的其他回复会保留；撤回当前版本后会显示剩余版本。")
                    }
                }
                failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!loading && impact == null) TextButton(onClick = { failure = null; revision++ }) { Text("重试检查") }
            }
        },
        dismissButton = { TextButton(enabled = !deleting, onClick = onDismiss) { Text(if (impact?.canRecall == false) "保留并返回" else "取消") } },
        confirmButton = {
            TextButton(enabled = !loading && !deleting && impact?.canRecall == true, onClick = {
                if (!deleting) {
                    deleting = true
                    failure = null
                    onConfirm { success ->
                        deleting = false
                        if (success) onDismiss() else { loading = true; impact = null; failure = "撤回未完成，请查看最新检查结果后重试。"; revision++ }
                    }
                }
            }) { Text(if (deleting) "正在撤回…" else "确认撤回") }
        },
    )
}
