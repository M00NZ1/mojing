package com.mojing.app.ui.generation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mojing.app.data.local.entity.GenerationTaskEntity
import com.mojing.app.data.local.entity.GenerationTaskKinds
import com.mojing.app.data.local.entity.GenerationTaskStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerationTaskListScreen(
    onBack: () -> Unit,
    viewModel: GenerationTaskListViewModel = hiltViewModel(),
) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val queuePaused by viewModel.queuePaused.collectAsStateWithLifecycle()
    val snackbarMessage by viewModel.snackbar.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    LaunchedEffect(snackbarMessage) {
        val msg = snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.consumeSnackbar()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("AI 生成任务", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (queuePaused) {
                        TextButton(onClick = { viewModel.resumeQueue() }) { Text("继续接新") }
                    } else {
                        TextButton(
                            onClick = { viewModel.pauseQueue() },
                            modifier = Modifier.padding(end = 4.dp),
                        ) { Text("暂停接新") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            if (tasks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "暂无任务",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(tasks, key = { it.id }) { t ->
                        TaskCard(
                            t = t,
                            dateFmt = dateFmt,
                            onCancel = { viewModel.cancelTask(t.id) },
                            onRetry = { viewModel.retryFailedTask(t) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskCard(
    t: GenerationTaskEntity,
    dateFmt: SimpleDateFormat,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    val active = t.status == GenerationTaskStatus.QUEUED ||
        t.status == GenerationTaskStatus.RUNNING ||
        t.status == GenerationTaskStatus.PAUSED
    val failed = t.status == GenerationTaskStatus.FAILED
    val done = t.status == GenerationTaskStatus.COMPLETED
    val created = dateFmt.format(Date(t.createdAt))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = created,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = t.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        text = "${kindLabel(t.taskKind)} · ${statusLabel(t.status)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                when {
                    active -> TextButton(onClick = onCancel) { Text("取消") }
                    failed && isRetryableKind(t.taskKind) ->
                        TextButton(onClick = onRetry) { Text("重新排队") }
                }
            }
            if (t.progressTotal > 0 && active) {
                val p = (t.progressDone.toFloat() / t.progressTotal.toFloat()).coerceIn(0f, 1f)
                Text(
                    "${t.progressDone}/${t.progressTotal}（${(p * 100).toInt()}%）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(p)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
            if (failed && t.errorMessage.isNotBlank()) {
                Text(
                    text = t.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else if (done && t.errorMessage.isNotBlank()) {
                Text(
                    text = t.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

private fun isRetryableKind(kind: String): Boolean =
    kind == GenerationTaskKinds.ENCYCLOPEDIA_ENTRIES ||
        kind == GenerationTaskKinds.ENCYCLOPEDIA_META_FILL ||
        kind == GenerationTaskKinds.CHARACTER_PERSONA_AI ||
        kind == GenerationTaskKinds.WORLD_TEMPLATE_PROMPT_AI

private fun kindLabel(kind: String): String = when (kind) {
    GenerationTaskKinds.ENCYCLOPEDIA_ENTRIES -> "百科条目"
    GenerationTaskKinds.ENCYCLOPEDIA_META_FILL -> "百科扩展字段"
    GenerationTaskKinds.CHARACTER_PERSONA_AI -> "角色人设"
    GenerationTaskKinds.WORLD_TEMPLATE_PROMPT_AI -> "世界观模板"
    else -> kind
}

private fun statusLabel(s: String): String = when (s) {
    GenerationTaskStatus.QUEUED -> "排队中"
    GenerationTaskStatus.RUNNING -> "生成中"
    GenerationTaskStatus.PAUSED -> "已暂停"
    GenerationTaskStatus.COMPLETED -> "已完成"
    GenerationTaskStatus.FAILED -> "失败"
    GenerationTaskStatus.CANCELLED -> "已取消"
    else -> s
}
