package com.mojing.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.mojing.app.MainActivity
import com.mojing.app.R
import com.mojing.app.ui.navigation.ExternalNavigationContract
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val CHANNEL_GENERATE = "generate"
        const val CHANNEL_REMINDER = "chat_reminder"
    }

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val generateChannel = NotificationChannel(
                CHANNEL_GENERATE,
                "生成中",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示正在生成回复的状态"
                setShowBadge(false)
            }
            manager.createNotificationChannel(generateChannel)

            val reminderChannel = NotificationChannel(
                CHANNEL_REMINDER,
                "对话提醒",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "角色回复完成后的提醒"
                enableVibration(true)
            }
            manager.createNotificationChannel(reminderChannel)
        }
    }

    fun showGenerationComplete(sessionId: Long, characterName: String, preview: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(
                ExternalNavigationContract.EXTRA_NAVIGATE_TO,
                ExternalNavigationContract.DESTINATION_CHAT,
            )
            putExtra(ExternalNavigationContract.EXTRA_SESSION_ID, sessionId)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, sessionId.toInt(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$characterName 回复了你")
            .setContentText(preview.take(80))
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview.take(200)))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(sessionId.toInt(), notification)
    }

    fun createGenerateNotification(title: String, progress: Int): Notification {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_GENERATE)
            .setContentTitle(title)
            .setContentText("正在生成回复…")
            .setSmallIcon(R.drawable.ic_notification)
            .setProgress(100, progress, progress == 100)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    fun createCompletionNotification(title: String): Notification {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_REMINDER)
            .setContentTitle("回复完成")
            .setContentText(title)
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .build()
    }

    fun showGenerating(characterName: String): Notification {
        return NotificationCompat.Builder(context, CHANNEL_GENERATE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText("${characterName}正在回复...")
            .setOngoing(true)
            .build()
    }
}
