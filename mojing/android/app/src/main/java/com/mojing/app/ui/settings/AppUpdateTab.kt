package com.mojing.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mojing.app.BuildConfig
import com.mojing.app.data.update.GitHubAppUpdates
import com.mojing.app.ui.common.MoJingButton
import com.mojing.app.ui.common.MoJingOutlinedButton
import java.util.Locale

@Composable
internal fun AppUpdateTab(viewModel: AppUpdateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    fun openLink(url: String, download: Boolean = false) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            if (download) viewModel.downloadOpened()
        } catch (_: Exception) { viewModel.linkFailed() }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("墨境", style = MaterialTheme.typography.headlineLarge.copy(fontFamily = FontFamily.Serif))
            Text("本地角色扮演与长篇创作", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("应用版本", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp))
                Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("从 GitHub 获取正式版本", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MoJingButton(onClick = viewModel::check, enabled = !state.checking, modifier = Modifier.fillMaxWidth()) {
                if (state.checking) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (state.checking) "正在检查更新" else "检查更新")
            }
            state.message?.let { UpdateStatus(it, error = false) }
            state.error?.let { UpdateStatus(it, error = true) }
            state.release?.let { release ->
                if (release.apk != null) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("发现新版本 v${release.version}", style = MaterialTheme.typography.titleMedium)
                            Text("已找到适合此设备的安装包", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            MoJingOutlinedButton(onClick = viewModel::showDownload, modifier = Modifier.fillMaxWidth()) {
                                Text("查看并下载")
                            }
                        }
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            TextButton(onClick = { openLink(GitHubAppUpdates.RELEASES_URL) },
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) {
                Text("查看所有发布版本")
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Outlined.OpenInNew, null, modifier = Modifier.size(18.dp))
            }
            Text("安装包由浏览器下载，完成后打开安装包即可更新。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    val release = state.release
    val apk = release?.apk
    if (state.showPrompt && release != null && apk != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissPrompt,
            title = { Text("更新至 v${release.version}", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("当前 v${BuildConfig.VERSION_NAME}  ·  安装包 ${String.format(Locale.getDefault(), "%.1f MB", apk.size / 1048576.0)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (release.notes.isNotBlank()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text(release.notes, style = MaterialTheme.typography.bodyMedium)
                    }
                    state.error?.let { UpdateStatus(it, error = true) }
                }
            },
            confirmButton = { TextButton(onClick = { openLink(apk.url, download = true) }) { Text("下载更新") } },
            dismissButton = { TextButton(onClick = viewModel::dismissPrompt) { Text("稍后") } },
        )
    }
}

@Composable
private fun UpdateStatus(message: String, error: Boolean) {
    Surface(color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.small) {
        Text(message, Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface)
    }
}
