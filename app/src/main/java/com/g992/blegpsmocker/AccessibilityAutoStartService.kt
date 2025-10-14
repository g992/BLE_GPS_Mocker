package com.g992.blegpsmocker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent

class AccessibilityAutoStartService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        maybeStartAutoBleService()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    private fun maybeStartAutoBleService() {
        if (!AppPrefs.isMockEnabled(this)) return

        try {
            val startFullIntent = Intent(this, AutoBleService::class.java).apply {
                action = AutoBleService.ACTION_START_FULL
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(startFullIntent)
            } else {
                startService(startFullIntent)
            }
        } catch (_: Exception) {
        }
    }
}
