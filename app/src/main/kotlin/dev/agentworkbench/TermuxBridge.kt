package dev.agentworkbench

import android.content.Context
import java.io.Closeable
import kotlinx.coroutines.flow.StateFlow

data class TermuxBridgeConfig(
    val port: Int = 8_022,
    val username: String = "",
    val workspace: String = "~/agent-workbench",
    val hostKeyFingerprint: String = "",
) {
    val configured: Boolean
        get() = username.isNotBlank() && hostKeyFingerprint.isNotBlank()
}

data class TermuxBridgeSnapshot(
    val supported: Boolean,
    val termuxInstalled: Boolean,
    val configured: Boolean,
    val loopbackHost: String = LOOPBACK_HOST,
    val port: Int,
    val username: String,
    val workspace: String,
    val hostKeyPinned: Boolean,
    val publicKey: String?,
    val detail: String,
) {
    val readyForConnection: Boolean
        get() = supported && termuxInstalled && configured
}

data class TermuxHostKeyProbe(
    val host: String,
    val port: Int,
    val algorithm: String,
    val fingerprint: String,
)

data class TermuxCommandRequest(
    val id: String,
    val command: String,
    val workingDirectory: String,
    val timeoutMillis: Long = 30_000,
    val outputLimitBytes: Int = 131_072,
    val environment: Map<String, String> = emptyMap(),
    val allocatePty: Boolean = false,
)

data class TermuxCommandResult(
    val exitCode: Int?,
    val output: String,
    val timedOut: Boolean,
    val truncated: Boolean,
    val durationMillis: Long,
)

data class TermuxFileEntry(
    val path: String,
    val directory: Boolean,
    val symbolicLink: Boolean,
    val size: Long,
    val modifiedAtMillis: Long?,
)

data class TermuxFileContent(
    val path: String,
    val text: String,
    val size: Long,
    val sha256: String,
)

data class TermuxFileMutation(
    val path: String,
    val previousSha256: String?,
    val sha256: String,
    val size: Long,
    val created: Boolean,
)

enum class TerminalSessionState {
    CONNECTING,
    RUNNING,
    CLOSED,
    FAILED,
}

interface TermuxTerminalSession : Closeable {
    val id: String
    val output: StateFlow<String>
    val state: StateFlow<TerminalSessionState>
    val failure: StateFlow<String?>

    suspend fun write(value: String)
    suspend fun resize(columns: Int, rows: Int)
}

interface TermuxBridge {
    fun snapshot(config: TermuxBridgeConfig): TermuxBridgeSnapshot

    fun publicKey(): String

    suspend fun probeHostKey(config: TermuxBridgeConfig): TermuxHostKeyProbe

    suspend fun execute(
        config: TermuxBridgeConfig,
        request: TermuxCommandRequest,
        onOutput: (String) -> Unit = {},
    ): TermuxCommandResult

    suspend fun cancel(commandId: String): Boolean

    suspend fun openTerminal(
        config: TermuxBridgeConfig,
        workingDirectory: String,
        columns: Int = 100,
        rows: Int = 32,
    ): TermuxTerminalSession

    suspend fun listFiles(
        config: TermuxBridgeConfig,
        path: String = ".",
        depth: Int = 2,
        maxEntries: Int = 500,
    ): List<TermuxFileEntry>

    suspend fun readTextFile(
        config: TermuxBridgeConfig,
        path: String,
        maxBytes: Int = 131_072,
    ): TermuxFileContent

    suspend fun writeTextFile(
        config: TermuxBridgeConfig,
        path: String,
        text: String,
        expectedSha256: String? = null,
        createOnly: Boolean = false,
    ): TermuxFileMutation

    suspend fun createDirectory(
        config: TermuxBridgeConfig,
        path: String,
    )

    suspend fun moveToTrash(
        config: TermuxBridgeConfig,
        path: String,
    ): String

    companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
    }
}

const val LOOPBACK_HOST = TermuxBridge.LOOPBACK_HOST

class TermuxBridgeConfigRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun load(): TermuxBridgeConfig = TermuxBridgeConfig(
        port = preferences.getInt(KEY_PORT, 8_022),
        username = preferences.getString(KEY_USERNAME, "").orEmpty(),
        workspace = preferences.getString(KEY_WORKSPACE, "~/agent-workbench")
            .orEmpty()
            .ifBlank { "~/agent-workbench" },
        hostKeyFingerprint = preferences.getString(KEY_HOST_KEY, "").orEmpty(),
    )

    fun save(config: TermuxBridgeConfig) {
        validate(config, requirePinnedKey = true)
        preferences.edit()
            .putInt(KEY_PORT, config.port)
            .putString(KEY_USERNAME, config.username.trim())
            .putString(KEY_WORKSPACE, config.workspace.trim())
            .putString(KEY_HOST_KEY, config.hostKeyFingerprint.trim())
            .apply()
    }

    fun saveCandidate(config: TermuxBridgeConfig) {
        validate(config, requirePinnedKey = false)
        preferences.edit()
            .putInt(KEY_PORT, config.port)
            .putString(KEY_USERNAME, config.username.trim())
            .putString(KEY_WORKSPACE, config.workspace.trim())
            .apply()
    }

    fun clearPinnedHostKey() {
        preferences.edit().remove(KEY_HOST_KEY).apply()
    }

    private fun validate(config: TermuxBridgeConfig, requirePinnedKey: Boolean) {
        require(config.port in 1_024..65_535) { "Porta SSH deve estar entre 1024 e 65535." }
        require(USERNAME.matches(config.username.trim())) {
            "Usuário Termux deve ter 1–64 caracteres seguros."
        }
        val workspace = config.workspace.trim()
        require(workspace.length in 1..1_024 && workspace.none { it == '\u0000' || it == '\r' || it == '\n' }) {
            "Workspace remoto inválido."
        }
        require(workspace.replace('\\', '/').split('/').none { it == ".." }) {
            "Workspace remoto não pode conter travessia '..'."
        }
        if (requirePinnedKey) {
            require(HOST_KEY_FINGERPRINT.matches(config.hostKeyFingerprint.trim())) {
                "Fingerprint SSH inválido ou ausente."
            }
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "termux_bridge"
        const val KEY_PORT = "port"
        const val KEY_USERNAME = "username"
        const val KEY_WORKSPACE = "workspace"
        const val KEY_HOST_KEY = "host_key_fingerprint"
        val USERNAME = Regex("[A-Za-z0-9._-]{1,64}")
        val HOST_KEY_FINGERPRINT = Regex("[A-Za-z0-9][A-Za-z0-9:+/=_-]{15,255}")
    }
}
