package com.g992.blegpsmocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object NotificationUtils {
    const val CHANNEL_ID = "mock_location_channel"
    const val CHANNEL_NAME = "Мок геопозиции"
    const val NOTIFICATION_ID = 1001

    const val CHANNEL_ATTENTION_ID = "mock_location_attention"
    const val CHANNEL_ATTENTION_NAME = "Требуется действие пользователя"
    const val NOTIFICATION_ATTENTION_ID = 1002

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel =
                    NotificationChannel(
                        CHANNEL_ID,
                        CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_LOW
                    ).apply { setShowBadge(false) }
                manager.createNotificationChannel(channel)
            }
            if (manager.getNotificationChannel(CHANNEL_ATTENTION_ID) == null) {
                val channel =
                    NotificationChannel(
                        CHANNEL_ATTENTION_ID,
                        CHANNEL_ATTENTION_NAME,
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply { setShowBadge(false) }
                manager.createNotificationChannel(channel)
            }
        }
    }

    fun buildForegroundNotification(context: Context, contentText: String): Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(CHANNEL_NAME)
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }

    fun showNeedsUserActionNotification(context: Context, contentText: String) {
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        val notification =
            NotificationCompat.Builder(context, CHANNEL_ATTENTION_ID)
                .setContentTitle(CHANNEL_ATTENTION_NAME)
                .setContentText(contentText)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setSmallIcon(R.mipmap.ic_launcher)
                .build()
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                val granted =
                    context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!granted) return
            }
            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_ATTENTION_ID, notification)
        } catch (_: SecurityException) {
        }
    }
}
