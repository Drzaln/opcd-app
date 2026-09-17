package dev.opencode.mobile.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import dev.opencode.mobile.R

object Notifications {
    const val CHANNEL_WATCH = "watch"
    const val CHANNEL_DONE = "done"
    const val WATCH_ID = 1

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_WATCH, "Background watch", NotificationManager.IMPORTANCE_MIN),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Turn finished", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun watchNotification(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_WATCH)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("opencode watch")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    fun post(context: Context, id: Int, title: String, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        manager.notify(id, notification)
    }
}