package com.g992.blegpsmocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (
            action == Intent.ACTION_BOOT_COMPLETED ||
                action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
                action == ACTION_QUICKBOOT_POWERON
        ) {
            Log.d(TAG, "Device boot completed, checking if GNSS client should auto-start")

            if (GNSSClientService.isServiceEnabled(context)) {
                if (GNSSClientService.isServiceRunning()) {
                    Log.i(TAG, "GNSS client service is already running. Don't start it again.")
                    return
                }

                Log.i(TAG, "Auto-starting GNSS client service")
                val serviceIntent = Intent(context, GNSSClientService::class.java)
                context.startForegroundService(serviceIntent)
            } else {
                Log.d(TAG, "GNSS client service not enabled for auto-start")
            }
        }
    }

    companion object {
        private const val TAG = "GNSSClientBootReceiver"
        private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
