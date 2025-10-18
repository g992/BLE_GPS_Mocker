package com.g992.blegpsmocker

import android.content.Context
import android.content.SharedPreferences
import android.os.Build

object AppPrefs {
    private const val PREFS_NAME = "blegpsmocker_prefs"
    private const val KEY_MOCK_ENABLED = "mock_enabled"

    private fun prefs(context: Context): SharedPreferences {
        val appContext = context.applicationContext ?: context
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val deviceContext = appContext.createDeviceProtectedStorageContext()
            deviceContext.moveSharedPreferencesFrom(appContext, PREFS_NAME)
            return deviceContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
        return appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @JvmStatic
    fun isMockEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MOCK_ENABLED, false)

    @JvmStatic
    fun setMockEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MOCK_ENABLED, enabled).apply()
    }
}
