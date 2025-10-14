package com.g992.blegpsmocker

import android.content.Context
import android.content.SharedPreferences

object AppPrefs {
    private const val PREFS_NAME = "blegpsmocker_prefs"
    private const val KEY_MOCK_ENABLED = "mock_enabled"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isMockEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MOCK_ENABLED, false)

    fun setMockEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MOCK_ENABLED, enabled).apply()
    }
}
