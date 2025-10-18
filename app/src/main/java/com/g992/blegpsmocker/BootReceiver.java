package com.g992.blegpsmocker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "GNSSClientBootReceiver";
    private static final String ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || ACTION_QUICKBOOT_POWERON.equals(action)) {
            Log.d(TAG, "Boot completed, checking service auto-start flag");

            if (GNSSClientService.isServiceEnabled(context)) {
                if (GNSSClientService.isServiceRunning()) {
                    Log.i(TAG, "Service already running, skipping auto-start");
                    return;
                }
                Log.i(TAG, "Auto-starting GNSS service after boot");
                Intent serviceIntent = new Intent(context, GNSSClientService.class);
                context.startForegroundService(serviceIntent);
            } else {
                Log.d(TAG, "Service not enabled for auto-start");
            }
        }
    }
}
