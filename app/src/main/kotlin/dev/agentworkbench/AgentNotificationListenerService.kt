package dev.agentworkbench

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

class AgentNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() { AgentNotificationRuntime.connected = true }
    override fun onListenerDisconnected() { AgentNotificationRuntime.connected = false }
    override fun onNotificationPosted(sbn: StatusBarNotification) { AgentNotificationRuntime.upsert(sbn) }
    override fun onNotificationRemoved(sbn: StatusBarNotification) { AgentNotificationRuntime.remove(sbn.key) }
}

private object AgentNotificationRuntime {
    @Volatile var connected = false
    private val values = ConcurrentHashMap<String, JSONObject>()

    fun upsert(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        values[sbn.key] = JSONObject()
            .put("key", sbn.key)
            .put("package", sbn.packageName)
            .put("posted_at", sbn.postTime)
            .put("ongoing", sbn.isOngoing)
            .put("title", safe(extras.getCharSequence(Notification.EXTRA_TITLE)))
            .put("text", safe(extras.getCharSequence(Notification.EXTRA_TEXT)))
        if (values.size > 150) values.entries.minByOrNull { it.value.optLong("posted_at") }?.let { values.remove(it.key) }
    }

    fun remove(key: String) { values.remove(key) }
    fun list(limit: Int): List<JSONObject> = values.values.sortedByDescending { it.optLong("posted_at") }.take(limit)
    private fun safe(value: CharSequence?): String = RemoteContextRedactor.redactText(value?.toString().orEmpty()).take(2_000)
}

class AgentNotificationController(context: Context) : NotificationAccessBridge {
    private val authorization = CapabilityAuthorizationStore(context)
    override fun status(): String = JSONObject()
        .put("listener_connected", AgentNotificationRuntime.connected)
        .put("app_authorized", authorization.isAuthorized(CAPABILITY))
        .toString(2)

    override fun list(limit: Int): String {
        require(authorization.isAuthorized(CAPABILITY)) { "Notificações não foram autorizadas na Central de Capacidades." }
        return JSONObject().put("notifications", JSONArray(AgentNotificationRuntime.list(limit.coerceIn(1, 100)))).toString(2)
    }

    private companion object { const val CAPABILITY = "notifications" }
}
