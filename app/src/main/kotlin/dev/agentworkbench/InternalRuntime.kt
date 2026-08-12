package dev.agentworkbench

import android.content.Context
import android.os.Build
import android.os.StatFs
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class RuntimeCommandRequest(
    val id: String,
    val command: String,
    val workingDirectory: String,
    val timeoutMillis: Long,
    val outputLimitBytes: Int,
    val environment: Map<String, String> = emptyMap(),
)

data class RuntimeCommandResult(
    val exitCode: Int?,
    val output: String,
    val timedOut: Boolean,
    val truncated: Boolean,
    val durationMillis: Long,
)

data class RuntimeEntrypoint(
    val path: String,
    val arguments: List<String> = emptyList(),
)

data class RuntimePackageRequest(
    val name: String,
    val version: String,
    val url: String,
    val sha256: String,
    val format: String,
    val entrypoints: Map<String, RuntimeEntrypoint>,
    val maxDownloadBytes: Long,
)

/**
 * App-owned command runtime. It never opens a listening socket and never crosses
 * into another app. Downloaded executable packs are explicitly
 * approved by the policy layer, HTTPS-only, hash-pinned and installed atomically.
 */
class InternalRuntime(
    context: Context,
    workspaceRoot: File,
    internal val externalPackagesAllowed: Boolean,
) {
    private val appContext = context.applicationContext
    val workspace: File = workspaceRoot.canonicalFile
    val root = File(appContext.noBackupFilesDir, "internal-runtime").canonicalFile
    val binDirectory = File(root, "bin")
    internal val packagesDirectory = File(root, "packages")
    internal val stagingDirectory = File(root, "staging")
    private val trashDirectory = File(root, "trash")
    private val homeDirectory = File(root, "home")
    private val tempDirectory = File(root, "tmp")
    private val activeDirectory = File(root, "active")
    private val runtimePacks = DistributionBindings.runtimePackBridge(appContext, root, workspace)
    private val active = ConcurrentHashMap<String, ActiveProcess>()
    private val packageMutex = Mutex()

    init {
        listOf(
            root,
            binDirectory,
            packagesDirectory,
            stagingDirectory,
            trashDirectory,
            homeDirectory,
            tempDirectory,
            activeDirectory,
            workspace,
        ).forEach { directory ->
            check(directory.mkdirs() || directory.isDirectory) {
                "Nao foi possivel preparar o runtime interno: ${directory.name}"
            }
        }
        cleanupAbandonedStaging()
    }

    fun status(): JSONObject {
        val stat = StatFs(root.path)
        return JSONObject()
            .put("backend", "internal_android_runtime")
            .put("ready", true)
            .put("network_transport", JSONObject.NULL)
            .put("requires_termux", false)
            .put("requires_root", false)
            .put("process_isolation", "same_app_uid_runtime")
            .put(
                "security_note",
                "Packs são código aprovado pelo usuário e compartilham o UID do app; instale apenas hashes de origem confiável.",
            )
            .put("workspace", workspace.path)
            .put("runtime_root", root.path)
            .put("bin", binDirectory.path)
            .put("external_packages_allowed", externalPackagesAllowed)
            .put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
            .put("free_bytes", stat.availableBytes)
            .put("running_processes", active.size)
            .put("installed_packages", listPackages())
            .put("runtime_packs", runtimePacks.status())
            .put("system_commands", systemCommands())
    }

    fun listPackages(): JSONArray {
        val records = packagesDirectory.listFiles()
            .orEmpty()
            .filter(File::isDirectory)
            .flatMap { packageRoot -> packageRoot.listFiles().orEmpty().filter(File::isDirectory) }
            .mapNotNull { versionRoot ->
                val metadata = File(versionRoot, PACKAGE_METADATA)
                runCatching { JSONObject(metadata.readText(Charsets.UTF_8)) }.getOrNull()
            }
            .sortedWith(compareBy({ it.optString("name") }, { it.optString("version") }))
        return JSONArray(records)
    }

    suspend fun execute(
        request: RuntimeCommandRequest,
        onOutput: (String) -> Unit = {},
    ): RuntimeCommandResult = withContext(Dispatchers.IO) {
        validateCommand(request)
        val script = interpretedEntrypointPrelude() + request.command
        val command = runtimePacks.commandLine(script)
        val processBuilder = ProcessBuilder(command)
            .directory(resolveWorkingDirectory(request.workingDirectory))
            .redirectErrorStream(true)
        controlledEnvironment(processBuilder.environment(), request.environment)
        runtimePacks.configureEnvironment(processBuilder.environment())

        val output = BoundedOutput(request.outputLimitBytes, onOutput)
        val startedAt = System.nanoTime()
        var process: Process? = null
        var registration: ActiveProcess? = null
        var timedOut = false
        try {
            process = processBuilder.start()
            registration = ActiveProcess(process)
            if (active.putIfAbsent(request.id, registration) != null) {
                terminate(process)
                error("Ja existe um processo com este ID.")
            }
            val pump = Thread({
                process.inputStream.use { input -> copyBounded(input, output, process) }
            }, "runtime-output-${request.id.take(8)}").apply {
                isDaemon = true
                start()
            }
            val finished = process.waitFor(request.timeoutMillis, TimeUnit.MILLISECONDS)
            timedOut = !finished
            if (!finished || output.truncated) terminate(process)
            pump.join(STREAM_JOIN_TIMEOUT_MS)
            RuntimeCommandResult(
                exitCode = if (process.isAlive) null else runCatching(process::exitValue).getOrNull(),
                output = output.text(),
                timedOut = timedOut,
                truncated = output.truncated,
                durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
            )
        } finally {
            registration?.let { active.remove(request.id, it) }
            process?.let { if (it.isAlive) terminate(it) }
        }
    }

    suspend fun cancel(id: String): Boolean = withContext(Dispatchers.IO) {
        val current = active.remove(id) ?: return@withContext false
        current.cancelled.set(true)
        terminate(current.process)
        true
    }

    suspend fun install(request: RuntimePackageRequest): JSONObject =
        DistributionBindings.installRuntimePackage(this, request)

    fun runtimePackCatalog(): JSONArray = runtimePacks.catalog()

    fun runtimePackStatus(): JSONObject = runtimePacks.status()

    suspend fun installRuntimePack(packId: String): JSONObject =
        runtimePacks.install(packId)

    internal suspend fun <T> withPackageLock(block: suspend () -> T): T = packageMutex.withLock { block() }

    suspend fun remove(name: String): JSONObject = packageMutex.withLock {
        withContext(Dispatchers.IO) {
            require(RuntimePackageRules.packageName.matches(name)) { "Nome de pacote invalido." }
            val activeRecord = File(activeDirectory, "$name.json")
            val metadata = if (activeRecord.exists()) JSONObject(activeRecord.readText()) else null
            activeRecord.delete()
            val packageRoot = File(packagesDirectory, name).canonicalFile
            val moved = if (packageRoot.exists()) moveToRuntimeTrash(packageRoot, "removed") else null
            JSONObject()
                .put("name", name)
                .put("removed", moved != null)
                .put("recoverable_path", moved?.path ?: JSONObject.NULL)
        }
    }

    internal fun activate(request: RuntimePackageRequest, target: File) {
        val installedMetadata = JSONObject(File(target, PACKAGE_METADATA).readText(Charsets.UTF_8))
        val activeRecord = installedMetadata
            .put("package_path", target.path)
            .put("execution_mode", "interpreted_android_sh")
        atomicWrite(File(activeDirectory, "${request.name}.json"), activeRecord.toString().toByteArray())
    }

    internal fun ensureEntrypointOwnership(packageName: String, commands: Set<String>) {
        activeDirectory.listFiles().orEmpty().filter(File::isFile).forEach { recordFile ->
            val record = runCatching { JSONObject(recordFile.readText(Charsets.UTF_8)) }.getOrNull()
                ?: return@forEach
            val owner = record.optString("name")
            if (owner != packageName) {
                val owned = record.optJSONObject("entrypoints")?.keys()?.asSequence()?.toSet().orEmpty()
                val collision = commands.firstOrNull { it in owned }
                check(collision == null) {
                    "O comando $collision ja pertence ao pacote $owner. Remova-o ou escolha outro nome."
                }
            }
        }
    }

    internal fun validateAndHardenEntrypoints(root: File, entrypoints: Map<String, RuntimeEntrypoint>) {
        entrypoints.forEach { (command, entrypoint) ->
            require(RuntimePackageRules.commandName.matches(command)) { "Nome de comando invalido: $command" }
            require(entrypoint.arguments.size <= MAX_ENTRYPOINT_ARGUMENTS) { "Argumentos prefixados demais." }
            entrypoint.arguments.forEach { require(it.length <= MAX_ENTRYPOINT_ARGUMENT_LENGTH && '\u0000' !in it) }
            val executable = resolvePackagePath(root, entrypoint.path)
            require(executable.isFile) { "Entrypoint ausente: ${entrypoint.path}" }
            require(executable.length() in 1..MAX_ENTRYPOINT_BYTES) { "Entrypoint vazio ou grande demais." }
            validateInterpretedEntrypoint(executable)
            check(executable.setReadable(true, true)) { "Falha ao marcar entrada legivel." }
            check(executable.setWritable(false, false)) { "Falha ao proteger entrada contra escrita." }
            executable.setExecutable(false, false)
            require(!executable.canExecute()) { "Entrypoint interpretado nao deve ficar executavel." }
        }
    }

    private fun validateInterpretedEntrypoint(file: File) {
        val header = file.inputStream().use { input -> ByteArray(20).also { input.read(it) } }
        val elf = RuntimePackageRules.isElf(header)
        require(!elf) {
            "ELF baixado nao pode ser executado pelo dominio untrusted_app no Android moderno. " +
                "Ferramentas nativas devem vir em componente APK assinado; este instalador aceita scripts sh interpretados."
        }
        val firstLine = file.bufferedReader(Charsets.UTF_8).use { it.readLine().orEmpty().trim() }
        require(RuntimePackageRules.isAllowedShellScript(firstLine)) {
            "Entrypoint deve ser script sh com shebang de /system/bin/sh ou /bin/sh."
        }
    }

    /**
     * Android 10+ forbids an untrusted app from execve'ing files it downloaded
     * into writable app data. Packs therefore remain data and are read by the
     * immutable /system/bin/sh interpreter. Functions give them command-like
     * ergonomics without pretending the files themselves are executable.
     */
    private fun interpretedEntrypointPrelude(): String = buildString {
        activeDirectory.listFiles().orEmpty().filter(File::isFile).sortedBy(File::getName).forEach records@ { recordFile ->
            val record = runCatching { JSONObject(recordFile.readText(Charsets.UTF_8)) }.getOrNull()
                ?: return@records
            if (record.optString("execution_mode") != "interpreted_android_sh") return@records
            val packagePath = runCatching { File(record.getString("package_path")).canonicalFile }.getOrNull()
                ?: return@records
            val entrypoints = record.optJSONObject("entrypoints") ?: return@records
            entrypoints.keys().asSequence().sorted().forEach entries@ { command ->
                if (!RuntimePackageRules.commandName.matches(command)) return@entries
                val entrypoint = entrypoints.optJSONObject(command) ?: return@entries
                val script = runCatching { resolvePackagePath(packagePath, entrypoint.getString("path")) }.getOrNull()
                    ?: return@entries
                if (!script.isFile) return@entries
                append(command)
                append("() { ")
                append(shellQuote(SYSTEM_SHELL))
                append(' ')
                append(shellQuote(script.path))
                val arguments = entrypoint.optJSONArray("arguments") ?: JSONArray()
                for (index in 0 until arguments.length()) {
                    append(' ')
                    append(shellQuote(arguments.getString(index)))
                }
                append(" \"${'$'}@\"; }\n")
            }
        }
    }

    internal fun validatePackageRequest(request: RuntimePackageRequest) {
        require(RuntimePackageRules.packageName.matches(request.name)) { "Nome de pacote invalido." }
        require(RuntimePackageRules.packageVersion.matches(request.version)) { "Versao de pacote invalida." }
        require(RuntimePackageRules.sha256.matches(request.sha256)) { "SHA-256 deve ter 64 caracteres hexadecimais." }
        require(request.format in setOf("raw", "zip")) { "Formato deve ser raw ou zip." }
        require(request.entrypoints.isNotEmpty() && request.entrypoints.size <= MAX_ENTRYPOINTS) {
            "Pacote deve declarar de 1 a $MAX_ENTRYPOINTS comandos."
        }
        require(request.maxDownloadBytes in MIN_DOWNLOAD_BYTES..MAX_DOWNLOAD_BYTES)
        if (request.format == "raw") {
            require(request.entrypoints.values.all { it.path == "payload" }) {
                "Pacote raw deve usar path=payload em todos os entrypoints."
            }
        }
        request.entrypoints.forEach { (command, entrypoint) ->
            require(RuntimePackageRules.commandName.matches(command)) { "Comando invalido: $command" }
            requireSafeRelativePath(entrypoint.path)
        }
    }

    internal fun packageMetadata(request: RuntimePackageRequest, bytes: Long): JSONObject = JSONObject()
        .put("name", request.name)
        .put("version", request.version)
        .put("source", request.url)
        .put("sha256", request.sha256.lowercase())
        .put("format", request.format)
        .put("download_bytes", bytes)
        .put("installed_at", System.currentTimeMillis())
        .put("entrypoints", JSONObject().apply {
            request.entrypoints.forEach { (command, entrypoint) ->
                put(command, JSONObject().put("path", entrypoint.path).put("arguments", JSONArray(entrypoint.arguments)))
            }
        })

    private fun systemCommands(): JSONArray {
        val candidates = listOf(
            "sh", "toybox", "awk", "sed", "grep", "find", "xargs", "tar", "gzip", "unzip",
            "sha256sum", "base64", "sort", "diff", "patch", "make", "clang", "python", "python3",
            "node", "java", "git", "curl", "wget",
        )
        val interpretedCommands = activeDirectory.listFiles().orEmpty().flatMap { recordFile ->
            val record = runCatching { JSONObject(recordFile.readText(Charsets.UTF_8)) }.getOrNull()
            if (record?.optString("execution_mode") != "interpreted_android_sh") emptyList()
            else record.optJSONObject("entrypoints")?.keys()?.asSequence()?.toList().orEmpty()
        }.toSet()
        val search = listOf(File("/system/bin"), File("/system/xbin"))
        return JSONArray((candidates + interpretedCommands).distinct().sorted().map { command ->
            val path = search.firstNotNullOfOrNull { directory ->
                File(directory, command).takeIf { it.isFile && it.canExecute() }?.path
            }
            val interpreted = command in interpretedCommands
            JSONObject()
                .put("command", command)
                .put("available", path != null || interpreted)
                .put("path", path ?: JSONObject.NULL)
                .put("execution_mode", if (interpreted) "interpreted_android_sh" else "system_binary")
        })
    }

    private fun controlledEnvironment(target: MutableMap<String, String>, requested: Map<String, String>) {
        val inheritedPath = target["PATH"].orEmpty()
        target.clear()
        target["PATH"] = listOf("/system/bin", "/system/xbin", inheritedPath)
            .filter(String::isNotBlank).distinct().joinToString(":")
        target["HOME"] = homeDirectory.path
        target["TMPDIR"] = tempDirectory.path
        target["XDG_CACHE_HOME"] = File(homeDirectory, ".cache").apply { mkdirs() }.path
        target["LANG"] = "C.UTF-8"
        target["LC_ALL"] = "C.UTF-8"
        requested.forEach { (name, value) ->
            require(ENV_NAME.matches(name) && name !in PROTECTED_ENVIRONMENT) { "Variavel de ambiente proibida: $name" }
            require(value.length <= MAX_ENVIRONMENT_VALUE && '\u0000' !in value) { "Valor de ambiente invalido." }
            target[name] = value
        }
    }

    private fun validateCommand(request: RuntimeCommandRequest) {
        require(request.id.matches(ID_PATTERN)) { "ID de processo invalido." }
        require(request.command.isNotBlank() && request.command.length <= MAX_COMMAND_CHARS) { "Comando vazio ou grande demais." }
        require('\u0000' !in request.command) { "Comando contem byte nulo." }
        require(request.timeoutMillis in 1_000..MAX_TIMEOUT_MS) { "Timeout invalido." }
        require(request.outputLimitBytes in 1_024..MAX_OUTPUT_BYTES) { "Limite de saida invalido." }
        require(request.environment.size <= MAX_ENVIRONMENT_ENTRIES) { "Variaveis de ambiente demais." }
        resolveWorkingDirectory(request.workingDirectory)
    }

    private fun resolveWorkingDirectory(value: String): File {
        val candidate = if (value.isBlank() || value == ".") workspace else File(workspace, value)
        val canonical = candidate.canonicalFile
        require(canonical == workspace || canonical.path.startsWith(workspace.path + File.separator)) {
            "Diretorio de trabalho escaparia do workspace."
        }
        require(canonical.isDirectory) { "Diretorio de trabalho nao existe." }
        return canonical
    }

    private fun resolvePackagePath(root: File, relative: String): File {
        requireSafeRelativePath(relative)
        val target = File(root, relative).canonicalFile
        require(target.path.startsWith(root.canonicalPath + File.separator)) { "Caminho escaparia do pacote." }
        return target
    }

    internal fun requireSafeRelativePath(value: String) {
        RuntimePackageRules.requireSafeRelativePath(value, MAX_RELATIVE_PATH)
    }

    private fun copyBounded(input: InputStream, output: BoundedOutput, process: Process) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            if (output.truncated) {
                terminate(process)
                break
            }
        }
    }

    private fun terminate(process: Process) {
        synchronized(process) {
            if (!process.isAlive) return
            process.destroy()
            if (!process.waitFor(STOP_GRACE_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(STOP_GRACE_MS, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.let { check(it.mkdirs() || it.isDirectory) }
        val temporary = File.createTempFile(".runtime-", ".tmp", target.parentFile)
        try {
            temporary.writeBytes(bytes)
            atomicMove(temporary, target)
        } finally {
            temporary.delete()
        }
    }

    internal fun atomicMove(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    internal fun moveToRuntimeTrash(source: File, reason: String): File {
        val destination = File(trashDirectory, "${source.name}-${System.currentTimeMillis()}-$reason")
        atomicMove(source, destination)
        return destination
    }

    private fun cleanupAbandonedStaging() {
        stagingDirectory.listFiles().orEmpty().forEach { candidate ->
            if (candidate.isDirectory && System.currentTimeMillis() - candidate.lastModified() > STAGING_MAX_AGE_MS) {
                candidate.deleteRecursively()
            }
        }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    private data class ActiveProcess(val process: Process, val cancelled: AtomicBoolean = AtomicBoolean(false))

    private class BoundedOutput(
        private val limit: Int,
        private val onOutput: (String) -> Unit,
    ) : ByteArrayOutputStream(minOf(limit, 16_384)) {
        @Volatile var truncated = false
            private set

        @Synchronized
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            val accepted = minOf(length, limit - size())
            if (accepted > 0) {
                super.write(buffer, offset, accepted)
                onOutput(buffer.copyOfRange(offset, offset + accepted).toString(Charsets.UTF_8))
            }
            if (accepted < length) truncated = true
        }

        @Synchronized fun text(): String = toByteArray().toString(Charsets.UTF_8)
    }

    private companion object {
        const val SYSTEM_SHELL = "/system/bin/sh"
        const val PACKAGE_METADATA = "package.json"
        const val MAX_COMMAND_CHARS = 65_536
        const val MAX_TIMEOUT_MS = 1_800_000L
        const val MAX_OUTPUT_BYTES = 4_194_304
        const val MAX_ENVIRONMENT_ENTRIES = 32
        const val MAX_ENVIRONMENT_VALUE = 4_096
        const val MAX_ENTRYPOINTS = 128
        const val MAX_ENTRYPOINT_ARGUMENTS = 16
        const val MAX_ENTRYPOINT_ARGUMENT_LENGTH = 1_024
        const val MAX_ENTRYPOINT_BYTES = 256L * 1024 * 1024
        const val MIN_DOWNLOAD_BYTES = 1_024L
        const val MAX_DOWNLOAD_BYTES = 256L * 1024 * 1024
        const val MAX_RELATIVE_PATH = 512
        const val STOP_GRACE_MS = 500L
        const val STREAM_JOIN_TIMEOUT_MS = 2_000L
        const val STAGING_MAX_AGE_MS = 24 * 60 * 60 * 1_000L
        val ID_PATTERN = Regex("[A-Za-z0-9._-]{1,100}")
        val ENV_NAME = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")
        val PROTECTED_ENVIRONMENT = setOf("PATH", "HOME", "TMPDIR", "LD_PRELOAD", "LD_LIBRARY_PATH")
    }
}
