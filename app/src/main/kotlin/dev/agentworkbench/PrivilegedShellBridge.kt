package dev.agentworkbench

import android.app.Activity
import org.json.JSONObject

data class PrivilegedShellSnapshot(
    val supported: Boolean,
    val serverRunning: Boolean,
    val permissionGranted: Boolean,
    val serviceConnected: Boolean,
    val uid: Int?,
    val detail: String,
) {
    val ready: Boolean
        get() = supported && serverRunning && permissionGranted

    fun displayText(): String = buildString {
        append(
            when {
                !supported -> "Shizuku não está disponível nesta edição."
                !serverRunning -> "Shizuku instalado, mas o serviço não está rodando."
                !permissionGranted -> "Shizuku rodando; autorização do app pendente."
                serviceConnected -> "Bridge ADB conectado."
                else -> "Shizuku autorizado; bridge será conectado no primeiro comando."
            },
        )
        uid?.let { append(" UID $it.") }
        if (detail.isNotBlank()) append(" $detail")
    }
}

interface PrivilegedShellBridge {
    fun snapshot(): PrivilegedShellSnapshot

    /**
     * Opens Shizuku's own permission UI. Returns false when no request could be
     * started (for example, because its server is stopped).
     */
    fun requestPermission(activity: Activity, requestCode: Int): Boolean

    suspend fun execute(
        script: String,
        timeoutMs: Int = 20_000,
        maxOutputBytes: Int = 131_072,
    ): String
}

fun PrivilegedShellSnapshot.toJson(): JSONObject = JSONObject()
    .put("supported", supported)
    .put("server_running", serverRunning)
    .put("permission_granted", permissionGranted)
    .put("service_connected", serviceConnected)
    .put("uid", uid ?: JSONObject.NULL)
    .put("access_level", when (uid) {
        0 -> "root"
        2_000 -> "adb_shell"
        null -> "unavailable"
        else -> "uid_$uid"
    })
    .put("is_root", uid == 0)
    .put("detail", detail)
