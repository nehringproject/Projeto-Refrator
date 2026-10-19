package dev.agentworkbench

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import dev.agentworkbench.core.ExecutionMode
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Loopback bridge that lets command-line agents running in the embedded terminal (Claude Code,
 * OpenCode, Gemini CLI…) use Refrator: `/open` hands OAuth/login URLs to the Android browser and
 * `/mcp` exposes the agent toolbox as an MCP server.
 *
 * Only 127.0.0.1 is bound and every request needs the per-install bearer token, which lives in
 * app-private storage, so other apps on the device cannot drive it. Tool calls go through the same
 * [PolicyEngine]/[ApprovalAuthority] path as the chat; anything the current mode would ask about is
 * shown to the user through [CliApprovalCenter] before it runs.
 */
internal object CliBridge {
    // A broken or hostile local connection must never take the app down with it.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> },
    )
    private var server: ServerSocket? = null
    private lateinit var appContext: Context
    private lateinit var workspace: File
    private lateinit var token: String
    private val toolboxes = mutableMapOf<ExecutionMode, AgentToolbox>()

    @Synchronized
    fun ensureStarted(context: Context, workspaceRoot: File): Info {
        server?.takeIf { !it.isClosed }?.let { return info(it) }
        appContext = context.applicationContext
        workspace = workspaceRoot.canonicalFile
        token = loadOrCreateToken(appContext)
        val socket = ServerSocket(0, BACKLOG, InetAddress.getByName("127.0.0.1"))
        server = socket
        Thread({ acceptLoop(socket) }, "refrator-cli-bridge").apply { isDaemon = true }.start()
        return info(socket)
    }

    data class Info(val url: String, val token: String)

    private fun info(socket: ServerSocket) = Info("http://127.0.0.1:${socket.localPort}", token)

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: continue
            scope.launch { client.use { runCatching { handle(it) } } }
        }
    }

    private suspend fun handle(socket: Socket) {
        socket.soTimeout = READ_TIMEOUT_MS
        val input = BufferedInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        val requestLine = readLine(input) ?: return
        val (method, path) = requestLine.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        if (!authorized(headers["authorization"])) {
            return respond(output, 401, JSONObject().put("error", "unauthorized"))
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length !in 0..MAX_BODY_BYTES) return respond(output, 413, JSONObject().put("error", "body too large"))
        val body = ByteArray(length).also { readFully(input, it) }.toString(StandardCharsets.UTF_8)
        when {
            method == "POST" && path == "/open" -> respond(output, 200, openUrl(body))
            method == "POST" && path == "/mcp" -> {
                val response = runCatching { mcp(JSONObject(body)) }
                    .getOrElse { rpcError(null, -32700, "Parse error: ${it.message}") }
                // JSON-RPC notifications have no reply; Streamable HTTP answers them with 202.
                if (response == null) respond(output, 202, null) else respond(output, 200, response)
            }
            method == "GET" && path == "/health" -> respond(output, 200, JSONObject().put("ok", true))
            else -> respond(output, 404, JSONObject().put("error", "not found"))
        }
    }

    private fun openUrl(body: String): JSONObject {
        val url = runCatching { JSONObject(body).getString("url") }.getOrNull()
            ?: return JSONObject().put("ok", false).put("error", "url ausente")
        val uri = Uri.parse(url)
        // Only web links: a CLI must not be able to fire arbitrary intents (tel:, intent:, file:…).
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            return JSONObject().put("ok", false).put("error", "somente URLs http(s)")
        }
        return runCatching {
            appContext.startActivity(
                Intent(Intent.ACTION_VIEW, uri)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            JSONObject().put("ok", true)
        }.getOrElse { JSONObject().put("ok", false).put("error", it.message ?: "falha ao abrir navegador") }
    }

    private suspend fun mcp(request: JSONObject): JSONObject? {
        val id = request.opt("id").takeUnless { it == null || it == JSONObject.NULL }
        val method = request.optString("method")
        if (id == null) return null
        val params = request.optJSONObject("params") ?: JSONObject()
        return when (method) {
            "initialize" -> rpcResult(
                id,
                JSONObject()
                    .put("protocolVersion", params.optString("protocolVersion").ifBlank { MCP_PROTOCOL_VERSION })
                    .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                    .put(
                        "serverInfo",
                        JSONObject().put("name", "refrator").put("version", BuildConfig.VERSION_NAME),
                    )
                    .put(
                        "instructions",
                        "Ferramentas do Refrator no Android (arquivos do workspace, memória, navegador, " +
                            "automação do aparelho). Ações sensíveis pedem aprovação na tela do Refrator.",
                    ),
            )
            "ping" -> rpcResult(id, JSONObject())
            "tools/list" -> rpcResult(
                id,
                JSONObject().put(
                    "tools",
                    JSONArray().apply {
                        toolbox().definitions
                            .filter { it.name !in CHAT_ONLY_TOOLS }
                            .forEach { tool ->
                                put(
                                    JSONObject()
                                        .put("name", tool.name)
                                        .put("description", tool.description)
                                        .put("inputSchema", JSONObject(tool.inputJsonSchema).withObjectType()),
                                )
                            }
                    },
                ),
            )
            "tools/call" -> rpcResult(id, callTool(params))
            else -> rpcError(id, -32601, "Método não suportado: $method")
        }
    }

    private suspend fun callTool(params: JSONObject): JSONObject {
        val name = params.optString("name")
        val arguments = params.optJSONObject("arguments") ?: JSONObject()
        val toolResult = if (name in CHAT_ONLY_TOOLS) {
            null to "A ferramenta $name só funciona dentro do chat do Refrator."
        } else {
            val toolbox = toolbox()
            val invocation = AgentToolInvocation("cli-${UUID.randomUUID()}", name, arguments.toString())
            when (val preparation = toolbox.prepare(invocation)) {
                is AgentToolPreparation.Rejected -> preparation.result to null
                is AgentToolPreparation.Ready -> {
                    val approved = !preparation.prepared.requiresApproval ||
                        CliApprovalCenter.request(preparation.prepared)
                    toolbox.execute(preparation.prepared, approved) to null
                }
            }
        }
        val (result, failure) = toolResult
        return JSONObject()
            .put(
                "content",
                JSONArray().put(JSONObject().put("type", "text").put("text", result?.payload ?: failure)),
            )
            .put("isError", result?.isError ?: true)
    }

    /** The toolbox follows the mode chosen in the composer, so "Ignorar permissões" applies here too. */
    @Synchronized
    private fun toolbox(): AgentToolbox {
        val mode = ProviderSettingsRepository(appContext).load().executionMode
        return toolboxes.getOrPut(mode) {
            AgentToolbox(
                appContext = appContext,
                profile = DistributionBindings.profile(),
                executionMode = mode,
                workspaceRoot = workspace,
                conversationId = "cli-bridge",
            )
        }
    }

    private fun JSONObject.withObjectType(): JSONObject = apply { if (!has("type")) put("type", "object") }

    private fun rpcResult(id: Any, result: JSONObject) =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)

    private fun rpcError(id: Any?, code: Int, message: String) = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put("error", JSONObject().put("code", code).put("message", message))

    private fun authorized(header: String?): Boolean {
        val presented = header?.removePrefix("Bearer ")?.trim() ?: return false
        return MessageDigest.isEqual(presented.toByteArray(), token.toByteArray())
    }

    private fun respond(output: OutputStream, status: Int, body: JSONObject?) {
        val bytes = body?.toString()?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
        val reason = when (status) {
            200 -> "OK"
            202 -> "Accepted"
            401 -> "Unauthorized"
            404 -> "Not Found"
            413 -> "Payload Too Large"
            else -> "Error"
        }
        val head = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            if (body != null) append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(StandardCharsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte == -1) return if (buffer.size() == 0) null else buffer.toString(StandardCharsets.UTF_8.name())
            if (byte == '\n'.code) return buffer.toString(StandardCharsets.UTF_8.name()).trimEnd('\r')
            if (buffer.size() >= MAX_HEADER_LINE) return null
            buffer.write(byte)
        }
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = input.read(target, offset, target.size - offset)
            if (read < 0) error("Conexão encerrada no meio do corpo")
            offset += read
        }
    }

    private fun loadOrCreateToken(context: Context): String {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        preferences.getString(KEY_TOKEN, null)?.let { return it }
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val created = bytes.joinToString("") { "%02x".format(it) }
        preferences.edit().putString(KEY_TOKEN, created).apply()
        return created
    }

    private val CHAT_ONLY_TOOLS = setOf("ask_user")
    private const val MCP_PROTOCOL_VERSION = "2025-06-18"
    private const val BACKLOG = 16
    private const val READ_TIMEOUT_MS = 15 * 60 * 1000
    private const val MAX_BODY_BYTES = 4 * 1024 * 1024
    private const val MAX_HEADER_LINE = 16 * 1024
    private const val PREFERENCES = "refrator_cli_bridge"
    private const val KEY_TOKEN = "token"
}

/** Approvals requested by terminal agents, shown as a dialog over whatever screen is open. */
internal object CliApprovalCenter {
    class Pending(val prepared: PreparedAgentTool, val decision: CompletableDeferred<Boolean>)

    val pending = mutableStateListOf<Pending>()

    suspend fun request(prepared: PreparedAgentTool): Boolean {
        val entry = Pending(prepared, CompletableDeferred())
        withContext(Dispatchers.Main) { pending += entry }
        // Unanswered requests are denied, matching ApprovalAuthority's short challenge lifetime.
        val approved = withTimeoutOrNull(APPROVAL_TIMEOUT_MS) { entry.decision.await() } ?: false
        withContext(Dispatchers.Main) { pending -= entry }
        return approved
    }

    fun resolve(entry: Pending, approved: Boolean) {
        entry.decision.complete(approved)
        pending -= entry
    }

    private const val APPROVAL_TIMEOUT_MS = 110_000L
}

/** Shell glue installed in ~/.refrator/bin for every terminal session. */
internal object CliBridgeShims {
    fun install(home: File, bashPath: String): File {
        val bin = File(home, ".refrator/bin").apply { mkdirs() }
        val shebang = "#!$bashPath"
        fun script(name: String, body: String) {
            File(bin, name).apply {
                writeText("$shebang\n$body\n")
                setExecutable(true, true)
            }
        }
        script("refrator-open", OPEN_SCRIPT)
        // Names CLIs and libraries (xdg-open, `open`, node's `open` on Android) try for logins.
        listOf("xdg-open", "open", "termux-open-url", "termux-open", "sensible-browser").forEach {
            script(it, "exec refrator-open \"$@\"")
        }
        script("refrator-mcp", MCP_SCRIPT)
        script("refrator-setup", SETUP_SCRIPT)
        return bin
    }

    private val OPEN_SCRIPT = """
        # Opens a URL in the Android browser through the Refrator bridge (OAuth, docs, logins).
        url="${'$'}1"
        [ -z "${'$'}url" ] && { echo "uso: refrator-open <url>" >&2; exit 2; }
        escaped=${'$'}(printf '%s' "${'$'}url" | sed 's/\\/\\\\/g; s/"/\\"/g')
        curl -fsS -X POST \
          -H "Authorization: Bearer ${'$'}REFRATOR_BRIDGE_TOKEN" \
          -H 'Content-Type: application/json' \
          --data "{\"url\":\"${'$'}escaped\"}" \
          "${'$'}REFRATOR_BRIDGE_URL/open" >/dev/null \
          || { echo "Abra manualmente: ${'$'}url" >&2; exit 1; }
    """.trimIndent()

    private val MCP_SCRIPT = """
        # MCP stdio server for Claude Code / OpenCode: forwards each JSON-RPC line to the bridge.
        [ -f "${'$'}HOME/.refrator/bridge.env" ] && . "${'$'}HOME/.refrator/bridge.env"
        while IFS= read -r line || [ -n "${'$'}line" ]; do
          [ -z "${'$'}line" ] && continue
          response=${'$'}(curl -sS -X POST \
            -H "Authorization: Bearer ${'$'}REFRATOR_BRIDGE_TOKEN" \
            -H 'Content-Type: application/json' \
            -H 'Accept: application/json, text/event-stream' \
            --data-binary "${'$'}line" \
            "${'$'}REFRATOR_BRIDGE_URL/mcp")
          [ -n "${'$'}response" ] && printf '%s\n' "${'$'}response"
        done
    """.trimIndent()

    private val SETUP_SCRIPT = """
        # Connects installed CLI agents to Refrator's tools (MCP) and browser bridge.
        set -u
        echo "Refrator: configurando integrações de CLI…"
        if command -v claude >/dev/null 2>&1; then
          claude mcp remove refrator -s user >/dev/null 2>&1
          claude mcp add refrator -s user -- refrator-mcp && echo "  ✓ Claude Code: servidor MCP 'refrator' adicionado"
        else
          echo "  · Claude Code não instalado (pkg install nodejs && npm i -g @anthropic-ai/claude-code)"
        fi
        config="${'$'}HOME/.config/opencode/opencode.json"
        if [ ! -f "${'$'}config" ]; then
          mkdir -p "${'$'}(dirname "${'$'}config")"
          printf '%s\n' '{"${'$'}schema":"https://opencode.ai/config.json","mcp":{"refrator":{"type":"local","command":["refrator-mcp"],"enabled":true}}}' > "${'$'}config"
          echo "  ✓ OpenCode: ${'$'}config criado com o MCP 'refrator'"
        elif grep -q '"refrator"' "${'$'}config"; then
          echo "  ✓ OpenCode já tem o MCP 'refrator'"
        else
          echo "  · OpenCode: adicione em ${'$'}config -> \"mcp\": {\"refrator\": {\"type\": \"local\", \"command\": [\"refrator-mcp\"]}}"
        fi
        echo "Logins (Google, Anthropic, GitHub…) abrem no navegador do Android e voltam para o terminal sozinhos."
    """.trimIndent()
}
