package com.mojing.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mojing.app.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class GenerateService : Service() {

    @Inject lateinit var notificationHelper: NotificationHelper

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra("title") ?: "生成中"
        val notification = notificationHelper.createGenerateNotification(title, 0)
        startForeground(1001, notification)
        return START_NOT_STICKY
    }

    fun updateProgress(title: String, progress: Int) {
        val notification = notificationHelper.createGenerateNotification(title, progress)
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(1001, notification)
    }

    fun complete(title: String) {
        val notification = notificationHelper.createCompletionNotification(title)
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(1001, notification)
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
