package dev.agentworkbench

import android.content.Context

/** Synchronous policy gate mirrored into Room by the capability center. */
class CapabilityAuthorizationStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "capability_authorizations",
        Context.MODE_PRIVATE,
    )

    fun isAuthorized(capability: String): Boolean =
        preferences.getBoolean("authorized.$capability", false)

    fun setAuthorized(capability: String, enabled: Boolean) {
        preferences.edit().putBoolean("authorized.$capability", enabled).apply()
    }
}
