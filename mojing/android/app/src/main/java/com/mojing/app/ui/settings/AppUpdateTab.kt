package com.mojing.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mojing.app.BuildConfig
import com.mojing.app.data.update.GitHubAppUpdates
import com.mojing.app.ui.common.MoJingButton
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("墨境", style = MaterialTheme.typography.headlineMedium)
        Text("本地角色扮演与长篇创作", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text("应用更新", style = MaterialTheme.typography.titleMedium)
                        Text("当前版本 ${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider()
                Text("从 GitHub 获取正式版本。发现新版本后，由你确认是否下载。",
                    style = MaterialTheme.typography.bodyMedium)
                state.release?.let { Text("发现新版本 ${it.version}", color = MaterialTheme.colorScheme.primary) }
                state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                state.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                MoJingButton(onClick = viewModel::check, enabled = !state.checking, modifier = Modifier.fillMaxWidth()) {
                    if (state.checking) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (state.checking) "正在检查…" else "检查更新")
                }
                if (state.release?.apk != null) {
                    OutlinedButton(onClick = viewModel::showDownload, modifier = Modifier.fillMaxWidth()) { Text("下载新版本") }
                }
                TextButton(onClick = { openLink(GitHubAppUpdates.RELEASES_URL) }) { Text("打开 GitHub 发布页") }
            }
        }
        Text("安装包通过浏览器下载，下载完成后打开 APK 更新。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val release = state.release
    val apk = release?.apk
    if (state.showPrompt && release != null && apk != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissPrompt,
            title = { Text("发现新版本 ${release.version}") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("当前版本 ${BuildConfig.VERSION_NAME}")
                    Text(apk.name, style = MaterialTheme.typography.bodySmall)
                    Text(String.format(Locale.getDefault(), "安装包 %.1f MB", apk.size / 1048576.0))
                    if (release.notes.isNotBlank()) {
                        HorizontalDivider()
                        Text(release.notes, style = MaterialTheme.typography.bodyMedium)
                    }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Text("是否打开浏览器下载最新安装包？")
                }
            },
            confirmButton = { TextButton(onClick = { openLink(apk.url, download = true) }) { Text("下载更新") } },
            dismissButton = { TextButton(onClick = viewModel::dismissPrompt) { Text("暂不下载") } },
        )
    }
}
