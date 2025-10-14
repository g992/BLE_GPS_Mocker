package com.g992.blegpsmocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        val isBoot =
            action == Intent.ACTION_BOOT_COMPLETED ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                    action == Intent.ACTION_LOCKED_BOOT_COMPLETED)
        val isPackageReplaced = action == Intent.ACTION_MY_PACKAGE_REPLACED

        if (!isBoot && !isPackageReplaced) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            NotificationUtils.showNeedsUserActionNotification(
                context,
                "Откройте приложение, чтобы запустить мок геопозиции"
            )
            return
        }

        if (!AppPrefs.isMockEnabled(context)) return

        try {
            val startFullIntent = Intent(context, AutoBleService::class.java).apply {
                this.action = AutoBleService.ACTION_START_FULL
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(startFullIntent)
            } else {
                context.startService(startFullIntent)
            }
        } catch (_: Exception) {
        }
    }
}
