package dev.agentworkbench

import org.json.JSONArray
import org.json.JSONObject

interface RuntimePackBridge {
    fun catalog(): JSONArray
    fun status(): JSONObject
    suspend fun install(packId: String, onProgress: (String) -> Unit = {}): JSONObject
    fun commandLine(command: String): List<String>?
    fun configureEnvironment(environment: MutableMap<String, String>)
}
