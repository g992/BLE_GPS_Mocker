package com.g992.blegpsmocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

object NotificationUtils {
    const val CHANNEL_ID = "mock_location_channel"
    const val NOTIFICATION_ID = 1001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channelName = context.getString(R.string.app_name)
        val description = context.getString(
            R.string.notification_channel_description,
            channelName
        )
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                this.description = description
                setShowBadge(false)
            }
        manager.createNotificationChannel(channel)
    }

    fun buildStatusNotification(
        context: Context,
        connected: Boolean,
        lastAgeSeconds: Double?
    ): Notification {
        ensureChannel(context)
        val titleRes =
            if (connected) {
                R.string.notification_title_connected
            } else {
                R.string.notification_title_disconnected
            }
        val text =
            if (connected) {
                lastAgeSeconds?.let {
                    context.getString(R.string.notification_text_connected, it)
                } ?: context.getString(R.string.notification_text_connected_no_age)
            } else {
                context.getString(R.string.notification_text_disconnected)
            }
        val title = context.getString(titleRes, context.getString(R.string.app_name))

        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()
    }
}
