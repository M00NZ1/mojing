package com.mojing.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.mojing.app.data.SeedDataManager
import com.mojing.app.data.local.branch.BranchVisibilityIndexManager
import com.mojing.app.data.local.search.MessageSearchIndexManager
import com.mojing.app.util.UsbSessionLog
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class MoJingApp : Application() {

    @Inject lateinit var seedDataManager: SeedDataManager
    @Inject lateinit var branchVisibilityIndexManager: BranchVisibilityIndexManager
    @Inject lateinit var messageSearchIndexManager: MessageSearchIndexManager

    override fun onCreate() {
        super.onCreate()
        UsbSessionLog.init(this)
        createNotificationChannels()
        CoroutineScope(Dispatchers.IO).launch {
            seedDataManager.seedIfNeeded()
            seedDataManager.mergeBuiltinPresetsFromAsset()
        }
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { branchVisibilityIndexManager.repairIfNeeded() }
                .onFailure { error ->
                    UsbSessionLog.w("BranchVisibility", "分支查询区段修复失败：${error::class.simpleName}")
                }
            runCatching { messageSearchIndexManager.rebuildIfNeeded() }
                .onFailure { error ->
                    UsbSessionLog.w("MessageSearchIndex", "后台索引更新失败：${error::class.simpleName}")
                }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            val generateChannel = NotificationChannel(
                "generate", "生成通知",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "回复生成进度" }

            val chatChannel = NotificationChannel(
                "chat_reminder", "对话提醒",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "对话完成提醒" }

            val mediaChannel = NotificationChannel(
                "media_playback", "语音播放",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "TTS 语音播放控制" }

            manager.createNotificationChannels(
                listOf(generateChannel, chatChannel, mediaChannel)
            )
        }
    }
}
