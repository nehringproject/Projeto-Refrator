package dev.agentworkbench

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** User-operated lifecycle for optional device access. Does not change agent features. */
class DeviceAccessController(context: Context) {
    private val context = context.applicationContext
    private val authorizations = CapabilityAuthorizationStore(this.context)

    fun prepareAccessibility() = enable(AgentAccessibilityService::class.java)
    fun prepareNotifications() = enable(AgentNotificationListenerService::class.java)

    private fun enable(service: Class<*>) {
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, service),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    suspend fun disconnect(capability: String) {
        authorizations.setAuthorized(capability, false)
        withContext(Dispatchers.Main.immediate) {
            when (capability) {
                "accessibility" -> AgentAccessibilityController.disconnect(context)
                "notifications" -> AgentNotificationController.disconnect(context)
                "media_projection" -> MediaProjectionGrantStore.clear()
            }
        }
        if (capability == "shizuku") {
            try {
                ShizukuShadowDisplayBridge.get(context).stop()
            } finally {
                ShizukuShellBridge.disconnect()
            }
        }
    }

    suspend fun suspendAll() {
        // Close synchronous gates before any suspended work or queued tool can run.
        CAPABILITIES.forEach { authorizations.setAuthorized(it, false) }
        CAPABILITIES.forEach { disconnect(it) }
        val executions = ExecutionRepository(context)
        CAPABILITIES.forEach {
            executions.setCapabilityLease(it, false, "none", "user_suspended_device_access")
        }
    }

    companion object {
        val CAPABILITIES = listOf("accessibility", "notifications", "media_projection", "shizuku")
    }
}
