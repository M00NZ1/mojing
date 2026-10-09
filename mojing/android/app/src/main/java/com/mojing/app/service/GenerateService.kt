package com.mojing.app.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.mojing.app.ui.chat.RetainedChatSessions
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.*

@AndroidEntryPoint
class GenerateService : Service() {
    @Inject lateinit var notificationHelper: NotificationHelper
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notificationJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1001, notificationHelper.createGenerateNotification("墨境 · 对话生成中", 0, RetainedChatSessions.running.value.firstOrNull()))
        if (notificationJob == null) notificationJob = serviceScope.launch {
            RetainedChatSessions.running.collect { ids ->
                if (ids.isNotEmpty()) {
                    getSystemService(android.app.NotificationManager::class.java).notify(1001,
                        notificationHelper.createGenerateNotification("墨境 · ${ids.size} 个对话处理中", 0, ids.first()))
                } else {
                    // Fast completion may precede onStartCommand. Promote first, then stop here;
                    // stopping externally while foreground promotion is pending crashes Android.
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        if (RetainedChatSessions.running.value.isEmpty()) stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        RetainedChatSessions.stores.stopAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
