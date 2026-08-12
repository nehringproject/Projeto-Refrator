package dev.agentworkbench

import android.content.Context
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

enum class AgentJobStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMED_OUT,
    INTERRUPTED,
}

data class AgentJobSnapshot(
    val id: String,
    val backend: String,
    val command: String,
    val workingDirectory: String,
    val status: AgentJobStatus,
    val createdAtMillis: Long,
    val startedAtMillis: Long?,
    val completedAtMillis: Long?,
    val exitCode: Int?,
    val timedOut: Boolean,
    val truncated: Boolean,
    val outputBytes: Long,
    val error: String?,
) {
    fun toJson(includeCommand: Boolean = true): JSONObject = JSONObject()
        .put("id", id)
        .put("backend", backend)
        .put("command", if (includeCommand) command else "<redacted>")
        .put("working_directory", workingDirectory)
        .put("status", status.name.lowercase())
        .put("created_at", createdAtMillis)
        .put("started_at", startedAtMillis ?: JSONObject.NULL)
        .put("completed_at", completedAtMillis ?: JSONObject.NULL)
        .put("exit_code", exitCode ?: JSONObject.NULL)
        .put("timed_out", timedOut)
        .put("truncated", truncated)
        .put("output_bytes", outputBytes)
        .put("error", error ?: JSONObject.NULL)
}

class AgentProcessManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, "agent-jobs").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val running = ConcurrentHashMap<String, Job>()
    private val terminals = ConcurrentHashMap<String, TermuxTerminalSession>()

    init {
        recoverInterruptedJobs()
    }

    suspend fun startTermux(
        bridge: TermuxBridge,
        config: TermuxBridgeConfig,
        command: String,
        workingDirectory: String,
        timeoutMillis: Long,
        outputLimitBytes: Int,
        environment: Map<String, String> = emptyMap(),
    ): AgentJobSnapshot = mutex.withLock {
        require(running.size < MAX_CONCURRENT_JOBS) {
            "Limite de $MAX_CONCURRENT_JOBS jobs simultâneos atingido."
        }
        val id = UUID.randomUUID().toString()
        val initial = AgentJobSnapshot(
            id = id,
            backend = "termux_ssh",
            command = command,
            workingDirectory = workingDirectory,
            status = AgentJobStatus.QUEUED,
            createdAtMillis = System.currentTimeMillis(),
            startedAtMillis = null,
            completedAtMillis = null,
            exitCode = null,
            timedOut = false,
            truncated = false,
            outputBytes = 0,
            error = null,
        )
        persistBlocking(initial)
        logFile(id).writeText("", Charsets.UTF_8)
        val job = scope.launch {
            runJob(bridge, config, initial, timeoutMillis, outputLimitBytes, environment)
        }
        running[id] = job
        job.invokeOnCompletion { running.remove(id) }
        initial
    }

    suspend fun startInternal(
        runtime: InternalRuntime,
        command: String,
        workingDirectory: String,
        timeoutMillis: Long,
        outputLimitBytes: Int,
        environment: Map<String, String> = emptyMap(),
    ): AgentJobSnapshot = mutex.withLock {
        require(running.size < MAX_CONCURRENT_JOBS) {
            "Limite de $MAX_CONCURRENT_JOBS jobs simultaneos atingido."
        }
        val id = UUID.randomUUID().toString()
        val initial = AgentJobSnapshot(
            id = id,
            backend = "internal_android_runtime",
            command = command,
            workingDirectory = workingDirectory,
            status = AgentJobStatus.QUEUED,
            createdAtMillis = System.currentTimeMillis(),
            startedAtMillis = null,
            completedAtMillis = null,
            exitCode = null,
            timedOut = false,
            truncated = false,
            outputBytes = 0,
            error = null,
        )
        persistBlocking(initial)
        logFile(id).writeText("", Charsets.UTF_8)
        val job = scope.launch {
            runInternalJob(runtime, initial, timeoutMillis, outputLimitBytes, environment)
        }
        running[id] = job
        job.invokeOnCompletion { running.remove(id) }
        initial
    }

    suspend fun cancel(id: String, bridge: TermuxBridge?): Boolean {
        val snapshot = load(id) ?: return false
        if (snapshot.status !in ACTIVE_STATUSES) return false
        bridge?.cancel(id)
        running.remove(id)?.cancel()
        persist(
            snapshot.copy(
                status = AgentJobStatus.CANCELLED,
                completedAtMillis = System.currentTimeMillis(),
                outputBytes = logFile(id).length(),
                error = "Cancelado pelo usuário ou agente.",
            ),
        )
        return true
    }

    suspend fun cancelInternal(id: String, runtime: InternalRuntime): Boolean {
        val snapshot = load(id) ?: return false
        if (snapshot.status !in ACTIVE_STATUSES) return false
        runtime.cancel(id)
        running.remove(id)?.cancel()
        persist(
            snapshot.copy(
                status = AgentJobStatus.CANCELLED,
                completedAtMillis = System.currentTimeMillis(),
                outputBytes = logFile(id).length(),
                error = "Cancelado pelo usuario ou agente.",
            ),
        )
        return true
    }

    suspend fun load(id: String): AgentJobSnapshot? = mutex.withLock {
        readSnapshot(id)
    }

    suspend fun list(limit: Int = 30): List<AgentJobSnapshot> = mutex.withLock {
        directory.listFiles { file -> file.name.endsWith(METADATA_SUFFIX) }
            ?.mapNotNull { runCatching { decode(JSONObject(it.readText(Charsets.UTF_8))) }.getOrNull() }
            ?.sortedByDescending(AgentJobSnapshot::createdAtMillis)
            ?.take(limit.coerceIn(1, 100))
            .orEmpty()
    }

    suspend fun output(id: String, offset: Long, maxBytes: Int): JSONObject = mutex.withLock {
        require(id.matches(ID_PATTERN)) { "ID de job inválido." }
        require(offset >= 0) { "Offset inválido." }
        val limit = maxBytes.coerceIn(1, MAX_READ_BYTES)
        val file = logFile(id)
        require(file.exists()) { "Log do job não existe." }
        val length = file.length()
        val start = minOf(offset, length)
        val bytes = file.inputStream().use { input ->
            var remainingSkip = start
            while (remainingSkip > 0) {
                val skipped = input.skip(remainingSkip)
                if (skipped <= 0) break
                remainingSkip -= skipped
            }
            val requested = minOf(limit.toLong(), length - start).toInt()
            val buffer = ByteArray(requested)
            var readTotal = 0
            while (readTotal < requested) {
                val read = input.read(buffer, readTotal, requested - readTotal)
                if (read < 0) break
                readTotal += read
            }
            if (readTotal == buffer.size) buffer else buffer.copyOf(readTotal)
        }
        JSONObject()
            .put("job_id", id)
            .put("offset", start)
            .put("next_offset", start + bytes.size)
            .put("total_bytes", length)
            .put("has_more", start + bytes.size < length)
            .put("output", bytes.toString(Charsets.UTF_8))
    }

    suspend fun openTerminal(
        bridge: TermuxBridge,
        config: TermuxBridgeConfig,
        workingDirectory: String,
        columns: Int,
        rows: Int,
    ): TermuxTerminalSession {
        require(terminals.size < MAX_TERMINALS) { "Limite de $MAX_TERMINALS terminais atingido." }
        val terminal = bridge.openTerminal(config, workingDirectory, columns, rows)
        terminals[terminal.id] = terminal
        return terminal
    }

    fun terminal(id: String): TermuxTerminalSession? = terminals[id]

    fun terminalSnapshots(): JSONArray = JSONArray(
        terminals.values.map { terminal ->
            JSONObject()
                .put("id", terminal.id)
                .put("state", terminal.state.value.name.lowercase())
                .put("output_chars", terminal.output.value.length)
                .put("failure", terminal.failure.value ?: JSONObject.NULL)
        },
    )

    suspend fun closeTerminal(id: String): Boolean = mutex.withLock {
        val terminal = terminals.remove(id) ?: return@withLock false
        terminal.close()
        true
    }

    private suspend fun runJob(
        bridge: TermuxBridge,
        config: TermuxBridgeConfig,
        initial: AgentJobSnapshot,
        timeoutMillis: Long,
        outputLimitBytes: Int,
        environment: Map<String, String>,
    ) {
        var current = initial.copy(
            status = AgentJobStatus.RUNNING,
            startedAtMillis = System.currentTimeMillis(),
        )
        persist(current)
        val log = logFile(initial.id)
        try {
            val result = bridge.execute(
                config = config,
                request = TermuxCommandRequest(
                    id = initial.id,
                    command = initial.command,
                    workingDirectory = initial.workingDirectory,
                    timeoutMillis = timeoutMillis,
                    outputLimitBytes = outputLimitBytes,
                    environment = environment,
                ),
                onOutput = { chunk -> appendLog(log, chunk, outputLimitBytes) },
            )
            val status = when {
                result.timedOut -> AgentJobStatus.TIMED_OUT
                result.exitCode == 0 -> AgentJobStatus.COMPLETED
                else -> AgentJobStatus.FAILED
            }
            current = current.copy(
                status = status,
                completedAtMillis = System.currentTimeMillis(),
                exitCode = result.exitCode,
                timedOut = result.timedOut,
                truncated = result.truncated || log.length() >= outputLimitBytes,
                outputBytes = log.length(),
                error = if (status == AgentJobStatus.FAILED) {
                    "Comando terminou com exit code ${result.exitCode}."
                } else {
                    null
                },
            )
        } catch (error: Exception) {
            current = current.copy(
                status = AgentJobStatus.FAILED,
                completedAtMillis = System.currentTimeMillis(),
                outputBytes = log.length(),
                error = RemoteContextRedactor.redactText(
                    error.message ?: error::class.java.simpleName,
                ).take(500),
            )
        }
        persist(current)
    }

    private suspend fun runInternalJob(
        runtime: InternalRuntime,
        initial: AgentJobSnapshot,
        timeoutMillis: Long,
        outputLimitBytes: Int,
        environment: Map<String, String>,
    ) {
        var current = initial.copy(
            status = AgentJobStatus.RUNNING,
            startedAtMillis = System.currentTimeMillis(),
        )
        persist(current)
        val log = logFile(initial.id)
        try {
            val result = runtime.execute(
                request = RuntimeCommandRequest(
                    id = initial.id,
                    command = initial.command,
                    workingDirectory = initial.workingDirectory,
                    timeoutMillis = timeoutMillis,
                    outputLimitBytes = outputLimitBytes,
                    environment = environment,
                ),
                onOutput = { chunk -> appendLog(log, chunk, outputLimitBytes) },
            )
            val status = when {
                result.timedOut -> AgentJobStatus.TIMED_OUT
                result.exitCode == 0 -> AgentJobStatus.COMPLETED
                else -> AgentJobStatus.FAILED
            }
            current = current.copy(
                status = status,
                completedAtMillis = System.currentTimeMillis(),
                exitCode = result.exitCode,
                timedOut = result.timedOut,
                truncated = result.truncated || log.length() >= outputLimitBytes,
                outputBytes = log.length(),
                error = if (status == AgentJobStatus.FAILED) {
                    "Comando terminou com exit code ${result.exitCode}."
                } else {
                    null
                },
            )
        } catch (error: Exception) {
            current = current.copy(
                status = AgentJobStatus.FAILED,
                completedAtMillis = System.currentTimeMillis(),
                outputBytes = log.length(),
                error = RemoteContextRedactor.redactText(
                    error.message ?: error::class.java.simpleName,
                ).take(500),
            )
        }
        persist(current)
    }

    @Synchronized
    private fun appendLog(file: File, value: String, limit: Int) {
        val remaining = limit - file.length()
        if (remaining <= 0) return
        val bytes = RemoteContextRedactor.redactText(value).toByteArray(Charsets.UTF_8)
        file.appendBytes(bytes.copyOf(minOf(bytes.size.toLong(), remaining).toInt()))
    }

    private fun recoverInterruptedJobs() {
        directory.listFiles { file -> file.name.endsWith(METADATA_SUFFIX) }
            .orEmpty()
            .forEach { file ->
                runCatching {
                    val snapshot = decode(JSONObject(file.readText(Charsets.UTF_8)))
                    if (snapshot.status in ACTIVE_STATUSES) {
                        persistBlocking(
                            snapshot.copy(
                                status = AgentJobStatus.INTERRUPTED,
                                completedAtMillis = System.currentTimeMillis(),
                                outputBytes = logFile(snapshot.id).length(),
                                error = "O processo do app foi encerrado durante o job.",
                            ),
                        )
                    }
                }
            }
    }

    private suspend fun persist(snapshot: AgentJobSnapshot) = mutex.withLock {
        persistBlocking(snapshot)
    }

    private fun persistBlocking(snapshot: AgentJobSnapshot) {
        val target = metadataFile(snapshot.id)
        val temp = File.createTempFile(".job-", ".tmp", directory)
        try {
            // Commands commonly contain bearer tokens or one-shot credentials. Execution keeps
            // the command in memory, but durable job metadata never stores it.
            temp.writeText(snapshot.toJson(includeCommand = false).toString(), Charsets.UTF_8)
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }

    private fun readSnapshot(id: String): AgentJobSnapshot? {
        require(id.matches(ID_PATTERN)) { "ID de job inválido." }
        val file = metadataFile(id)
        if (!file.exists()) return null
        return decode(JSONObject(file.readText(Charsets.UTF_8)))
    }

    private fun decode(value: JSONObject): AgentJobSnapshot = AgentJobSnapshot(
        id = value.getString("id"),
        backend = value.getString("backend"),
        command = value.getString("command"),
        workingDirectory = value.getString("working_directory"),
        status = AgentJobStatus.valueOf(value.getString("status").uppercase()),
        createdAtMillis = value.getLong("created_at"),
        startedAtMillis = value.optLongOrNull("started_at"),
        completedAtMillis = value.optLongOrNull("completed_at"),
        exitCode = value.optIntOrNull("exit_code"),
        timedOut = value.optBoolean("timed_out"),
        truncated = value.optBoolean("truncated"),
        outputBytes = value.optLong("output_bytes"),
        error = value.optStringOrNull("error"),
    )

    private fun JSONObject.optLongOrNull(name: String): Long? =
        if (isNull(name)) null else getLong(name)

    private fun JSONObject.optIntOrNull(name: String): Int? =
        if (isNull(name)) null else getInt(name)

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (isNull(name)) null else getString(name)

    private fun metadataFile(id: String) = File(directory, "$id$METADATA_SUFFIX")
    private fun logFile(id: String) = File(directory, "$id.log")

    companion object {
        @Volatile
        private var instance: AgentProcessManager? = null

        fun get(context: Context): AgentProcessManager = instance ?: synchronized(this) {
            instance ?: AgentProcessManager(context).also { instance = it }
        }

        private const val METADATA_SUFFIX = ".json"
        private const val MAX_CONCURRENT_JOBS = 4
        private const val MAX_TERMINALS = 4
        private const val MAX_READ_BYTES = 131_072
        private val ACTIVE_STATUSES = setOf(AgentJobStatus.QUEUED, AgentJobStatus.RUNNING)
        private val ID_PATTERN = Regex("[0-9a-fA-F-]{36}")
    }
}
