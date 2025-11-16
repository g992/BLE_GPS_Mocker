package com.g992.blegpsmocker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "GNSSClientBootReceiver";
    private static final String ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON";

    // Статический receiver для событий экрана
    private static final BroadcastReceiver screenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }
            String action = intent.getAction();

            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                Log.i(TAG, "Screen turned off - stopping service");
                if (GNSSClientService.isServiceRunning()) {
                    Intent serviceIntent = new Intent(context, GNSSClientService.class);
                    context.stopService(serviceIntent);
                    // Сохраняем флаг, что сервис был остановлен из-за сна
                    GNSSClientService.setServiceEnabled(context, false);
                }
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                Log.i(TAG, "Screen turned on - starting service if enabled");
                if (GNSSClientService.isServiceEnabled(context)) {
                    if (!GNSSClientService.isServiceRunning()) {
                        Intent serviceIntent = new Intent(context, GNSSClientService.class);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent);
                        } else {
                            context.startService(serviceIntent);
                        }
                    }
                }
            }
        }
    };

    private static boolean screenReceiverRegistered = false;

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

            // Регистрируем receiver для событий экрана
            registerScreenStateReceiver(context);

            if (GNSSClientService.isServiceEnabled(context)) {
                if (GNSSClientService.isServiceRunning()) {
                    Log.i(TAG, "Service already running, skipping auto-start");
                    return;
                }
                Log.i(TAG, "Auto-starting GNSS service after boot");
                Intent serviceIntent = new Intent(context, GNSSClientService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            } else {
                Log.d(TAG, "Service not enabled for auto-start");
            }
        }
    }

    private static void registerScreenStateReceiver(Context context) {
        if (screenReceiverRegistered) {
            return;
        }

        try {
            IntentFilter screenFilter = new IntentFilter();
            screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
            screenFilter.addAction(Intent.ACTION_SCREEN_ON);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(screenStateReceiver, screenFilter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(screenStateReceiver, screenFilter);
            }

            screenReceiverRegistered = true;
            Log.i(TAG, "Screen state receiver registered");
        } catch (Exception e) {
            Log.e(TAG, "Failed to register screen state receiver", e);
        }
    }
}
