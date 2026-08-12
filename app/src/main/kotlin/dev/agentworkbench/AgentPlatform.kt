package dev.agentworkbench

import android.content.Context
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class AgentNodeState { QUEUED, RUNNING, WAITING_INPUT, PAUSED, SUCCEEDED, FAILED, CANCELLED }
enum class McpTransportKind { STREAMABLE_HTTP, SSE, STDIO }
enum class HookEvent {
    RUN_START, RUN_END, BEFORE_MODEL, AFTER_MODEL, BEFORE_TOOL, AFTER_TOOL,
    PERMISSION_REQUEST, MEMORY_WRITE, COMPACTION, FAILURE,
}
enum class HookFailurePolicy { FAIL_CLOSED, WARN }

class AgentPlatformRepository(context: Context) {
    private val dao = WorkbenchDatabase.get(context).dao()
    private val preferences = context.applicationContext.getSharedPreferences(
        "agent-platform",
        Context.MODE_PRIVATE,
    )

    suspend fun ensureDefaultProfile(settings: ProviderSettings): AgentProfile {
        dao.agentProfiles().firstOrNull { it.id == DEFAULT_PROFILE_ID }?.let { return it }
        val now = System.currentTimeMillis()
        return AgentProfile(
            id = DEFAULT_PROFILE_ID,
            name = "Principal",
            description = "Perfil principal do Refrator",
            systemPrompt = "",
            providerPreset = settings.preset.name,
            modelId = settings.modelId,
            reasoningLevel = "provider_default",
            toolPolicyJson = JSONObject().put("mode", settings.executionMode.name).toString(),
            skillIdsJson = "[]",
            mcpServerIdsJson = "[]",
            browserProfileId = null,
            maxDelegates = 2,
            enabled = true,
            createdAtMillis = now,
            updatedAtMillis = now,
        ).also { dao.upsertAgentProfile(it) }
    }

    suspend fun profiles(): List<AgentProfile> = dao.agentProfiles()
    fun selectedProfileId(): String = preferences.getString("selected_profile", DEFAULT_PROFILE_ID)
        ?: DEFAULT_PROFILE_ID
    fun selectProfile(id: String) {
        preferences.edit().putString("selected_profile", id).apply()
    }
    suspend fun selectedProfile(settings: ProviderSettings): AgentProfile {
        ensureDefaultProfile(settings)
        return dao.agentProfiles().firstOrNull { it.id == selectedProfileId() }
            ?: ensureDefaultProfile(settings)
    }
    suspend fun saveProfile(value: AgentProfile) = dao.upsertAgentProfile(value)
    suspend fun mcpServers(): List<McpServerConfig> = dao.mcpServers()
    suspend fun saveMcpServer(value: McpServerConfig) = dao.upsertMcpServer(value)
    suspend fun hooks(event: HookEvent): List<HookDefinition> = dao.hooks(event.name)
    suspend fun saveHook(value: HookDefinition) = dao.upsertHook(value)
    suspend fun commands(): List<CommandTemplate> = dao.commands()
    suspend fun saveCommand(value: CommandTemplate) = dao.upsertCommand(value)
    suspend fun runNodes(runId: String): List<AgentRunNode> = dao.agentRunNodes(runId)
    suspend fun saveRunNode(value: AgentRunNode) = dao.upsertAgentRunNode(value)

    companion object { const val DEFAULT_PROFILE_ID = "default-agent" }
}

data class HookEvaluation(
    val allowed: Boolean,
    val warnings: List<String>,
    val patchedPayload: JSONObject,
)

class HookEngine(private val repository: AgentPlatformRepository) {
    suspend fun evaluate(event: HookEvent, payload: JSONObject): HookEvaluation {
        var current = JSONObject(payload.toString())
        val warnings = mutableListOf<String>()
        for (hook in repository.hooks(event)) {
            val result = runCatching { applyHook(hook, current) }
            if (result.isFailure) {
                val message = "Hook ${hook.name} falhou: ${result.exceptionOrNull()?.message}"
                if (hook.failurePolicy == HookFailurePolicy.FAIL_CLOSED.name) {
                    return HookEvaluation(false, warnings + message, current)
                }
                warnings += message
                continue
            }
            val decision = result.getOrThrow()
            if (!decision.first) return HookEvaluation(false, warnings, current)
            current = decision.second
        }
        return HookEvaluation(true, warnings, current)
    }

    private fun applyHook(hook: HookDefinition, payload: JSONObject): Pair<Boolean, JSONObject> {
        require(hook.timeoutMillis in 100..30_000) { "timeout fora do limite" }
        return when (hook.actionType) {
            "DENY" -> false to payload
            "REQUIRE_FIELD" -> {
                val field = JSONObject(hook.actionPayload).getString("field")
                payload.has(field) to payload
            }
            "PATCH_JSON" -> {
                val patch = JSONObject(hook.actionPayload)
                val copy = JSONObject(payload.toString())
                patch.keys().forEach { key -> copy.put(key, patch.get(key)) }
                true to copy
            }
            "LOG" -> true to payload
            else -> error("tipo de hook não suportado")
        }
    }
}

class SubagentCoordinator(maxConcurrency: Int = 2) {
    private val semaphore = Semaphore(maxConcurrency.coerceIn(1, 4))

    suspend fun <T> run(task: suspend () -> T): T = semaphore.withPermit { task() }
}

class McpHttpClient {
    suspend fun call(
        server: McpServerConfig,
        method: String,
        params: JSONObject = JSONObject(),
    ): JSONObject = withContext(Dispatchers.IO) {
        require(server.transport in setOf(McpTransportKind.STREAMABLE_HTTP.name, McpTransportKind.SSE.name)) {
            "Este cliente aceita apenas MCP remoto HTTP."
        }
        val uri = URI(server.commandOrUrl)
        require(uri.scheme == "https" || (BuildConfig.FULL_CAPABILITIES && uri.scheme == "http" && uri.host in LOCAL_HOSTS)) {
            "MCP remoto exige HTTPS; HTTP é aceito somente em destinos locais validados."
        }
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 10_000
        connection.readTimeout = 60_000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json, text/event-stream")
        val request = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", UUID.randomUUID().toString())
            .put("method", method)
            .put("params", params)
        connection.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText().take(MAX_RESPONSE_CHARS) }.orEmpty()
        require(status in 200..299) { "MCP HTTP $status: ${body.take(300)}" }
        if (body.trimStart().startsWith("data:")) {
            val data = body.lineSequence().firstOrNull { it.startsWith("data:") }
                ?.removePrefix("data:")?.trim().orEmpty()
            JSONObject(data)
        } else {
            JSONObject(body)
        }
    }

    private companion object {
        const val MAX_RESPONSE_CHARS = 1_048_576
        val LOCAL_HOSTS = setOf("127.0.0.1", "localhost", "::1")
    }
}

fun AgentProfile.skillIds(): List<String> = JSONArray(skillIdsJson).let { array ->
    buildList { for (index in 0 until array.length()) add(array.getString(index)) }
}
