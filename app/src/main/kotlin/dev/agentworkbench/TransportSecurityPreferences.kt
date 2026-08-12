package dev.agentworkbench

import android.content.Context

class TransportSecurityPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun allowLocalCleartext(defaultValue: Boolean = BuildConfig.DEVELOPER_BUILD): Boolean =
        preferences.getBoolean(KEY_ALLOW_LOCAL_CLEARTEXT, defaultValue)

    fun setAllowLocalCleartext(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ALLOW_LOCAL_CLEARTEXT, enabled).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "transport_security"
        const val KEY_ALLOW_LOCAL_CLEARTEXT = "allow_local_cleartext"
    }
}
