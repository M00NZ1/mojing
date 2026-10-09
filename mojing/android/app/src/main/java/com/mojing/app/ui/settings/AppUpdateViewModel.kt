package com.mojing.app.ui.settings

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mojing.app.BuildConfig
import com.mojing.app.data.update.AppRelease
import com.mojing.app.data.update.GitHubAppUpdates
import com.mojing.app.data.update.ReleaseVersion
import com.mojing.app.data.update.UpdateCheckException
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class AppUpdateState(
    val checking: Boolean = false,
    val release: AppRelease? = null,
    val message: String? = null,
    val error: String? = null,
    val showPrompt: Boolean = false,
)

@HiltViewModel
internal class AppUpdateViewModel internal constructor(
    private val fetchRelease: suspend () -> AppRelease,
    private val currentVersion: () -> ReleaseVersion?,
) : ViewModel() {
    @Inject constructor() : this(
        fetchRelease = { GitHubAppUpdates().latest(Build.SUPPORTED_ABIS.toList()) },
        currentVersion = { ReleaseVersion.parse(BuildConfig.VERSION_NAME) },
    )
    private val mutableState = MutableStateFlow(AppUpdateState())
    val state = mutableState.asStateFlow()

    fun check() {
        if (mutableState.value.checking) return
        mutableState.update { it.copy(checking = true, message = null, error = null, showPrompt = false) }
        viewModelScope.launch {
            try {
                val release = fetchRelease()
                val current = currentVersion()
                    ?: throw UpdateCheckException("当前版本号无法识别，请查看发布页")
                mutableState.value = when {
                    release.version < current -> AppUpdateState(message = "当前版本高于 GitHub 最新发布版 ${release.version}")
                    release.version == current -> AppUpdateState(message = "当前已是最新版本")
                    release.apk == null -> AppUpdateState(release = release, error = "发现 ${release.version}，但尚无适合此设备的安装包，请查看发布页")
                    else -> AppUpdateState(release = release, showPrompt = true)
                }
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(checking = false) }
                throw cancelled
            }
            catch (e: Exception) {
                val message = when (e) {
                    is UpdateCheckException -> e.message
                    is java.net.SocketTimeoutException -> "检查更新超时，请检查网络后重试"
                    is java.io.IOException -> "无法连接 GitHub，请检查网络后重试"
                    else -> "检查更新失败，请重试"
                }
                mutableState.update { it.copy(checking = false, error = message) }
            }
        }
    }
    fun showDownload() { mutableState.update { it.copy(showPrompt = it.release?.apk != null) } }
    fun dismissPrompt() { mutableState.update { it.copy(showPrompt = false) } }
    fun downloadOpened() { mutableState.update { it.copy(showPrompt = false, error = null, message = "已交给浏览器下载，完成后打开安装包更新") } }
    fun linkFailed() { mutableState.update { it.copy(error = "无法打开下载链接，请安装或启用浏览器后重试") } }
}
