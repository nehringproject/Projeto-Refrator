package dev.agentworkbench.runner.power

import dev.agentworkbench.core.Capability
import dev.agentworkbench.core.CommandSpec
import dev.agentworkbench.core.ConfirmationMethod
import dev.agentworkbench.core.EnvironmentTrust
import dev.agentworkbench.core.ExecutionRunner
import dev.agentworkbench.core.RunnerDescriptor
import dev.agentworkbench.core.RunnerEvent
import dev.agentworkbench.core.ToolEffect
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Android shell runner with request-bound authorization and strict workspace limits. */
class PowerRunner(
    workspaceRoot: File,
) : ExecutionRunner {
    private val canonicalWorkspaceRoot = workspaceRoot.canonicalFile
    private val active = ConcurrentHashMap<String, ActiveProcess>()
    private val consumedChallenges = ConcurrentHashMap.newKeySet<String>()

    override val descriptor = RunnerDescriptor(
        id = "power-android-shell",
        displayName = "Android system shell",
        environmentTrust = EnvironmentTrust.POWER_USERSPACE,
        capabilities = setOf(
            Capability.FILE_READ,
            Capability.FILE_WRITE,
            Capability.PROCESS_INSPECT,
            Capability.SHELL_EXECUTE,
        ),
    )

    fun probeCommand(id: String): CommandSpec = CommandSpec(
        id = id,
        executable = SHELL_PATH,
        arguments = listOf("-c", PROBE_SCRIPT),
        workingDirectory = canonicalWorkspaceRoot.path,
        timeoutMillis = 5_000,
        outputLimitBytes = 32_768,
        idempotent = true,
    )

    fun shellCommand(
        id: String,
        script: String,
    ): CommandSpec = CommandSpec(
        id = id,
        executable = SHELL_PATH,
        arguments = listOf("-c", script),
        workingDirectory = canonicalWorkspaceRoot.path,
        timeoutMillis = USER_COMMAND_TIMEOUT_MILLIS,
        outputLimitBytes = USER_COMMAND_OUTPUT_BYTES,
        idempotent = false,
    )

    override fun execute(command: CommandSpec): Flow<RunnerEvent> = channelFlow {
        val rejection = rejectionReason(command)
        if (rejection != null) {
            send(RunnerEvent.Rejected(rejection))
            return@channelFlow
        }

        send(RunnerEvent.Started(command.id))
        var registered: ActiveProcess? = null
        try {
            val process = withContext(Dispatchers.IO) {
                ProcessBuilder(listOf(command.executable) + command.arguments)
                    .directory(canonicalWorkspaceRoot)
                    .redirectErrorStream(false)
                    .start()
            }
            val registration = ActiveProcess(process)
            registered = registration
            if (active.putIfAbsent(command.id, registration) != null) {
                terminate(process)
                send(RunnerEvent.Rejected("A command with this id is already running"))
                return@channelFlow
            }

            val emittedBytes = AtomicLong(0)
            val outputLimitReached = AtomicBoolean(false)
            val streamFailure = AtomicReference<String?>(null)

            fun pump(stream: InputStream, stdout: Boolean) = launch(Dispatchers.IO) {
                try {
                    stream.use { input ->
                        val buffer = ByteArray(STREAM_BUFFER_BYTES)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break

                            val previous = emittedBytes.getAndAdd(count.toLong())
                            val remaining = command.outputLimitBytes - previous
                            if (remaining > 0) {
                                val accepted = minOf(count.toLong(), remaining).toInt()
                                val bytes = buffer.copyOf(accepted)
                                if (stdout) {
                                    send(RunnerEvent.StandardOutput(bytes))
                                } else {
                                    send(RunnerEvent.StandardError(bytes))
                                }
                            }
                            if (remaining < count) {
                                outputLimitReached.set(true)
                                terminate(process)
                                break
                            }
                        }
                    }
                } catch (error: IOException) {
                    val expectedClosure =
                        registration.cancelled.get() ||
                            outputLimitReached.get() ||
                            !process.isAlive
                    if (!expectedClosure) {
                        streamFailure.compareAndSet(
                            null,
                            error.message ?: error::class.java.simpleName,
                        )
                        terminate(process)
                    }
                }
            }

            val stdoutJob = pump(process.inputStream, stdout = true)
            val stderrJob = pump(process.errorStream, stdout = false)
            val finished = withContext(Dispatchers.IO) {
                process.waitFor(command.timeoutMillis, TimeUnit.MILLISECONDS)
            }

            if (!finished) terminate(process)
            stdoutJob.join()
            stderrJob.join()

            when {
                registered.cancelled.get() ->
                    send(RunnerEvent.Interrupted("Command cancelled"))

                outputLimitReached.get() ->
                    send(RunnerEvent.Interrupted("Output limit reached"))

                streamFailure.get() != null ->
                    send(RunnerEvent.Interrupted("Command stream failed: ${streamFailure.get()}"))

                !finished ->
                    send(RunnerEvent.Interrupted("Command timed out"))

                else ->
                    send(RunnerEvent.Completed(process.exitValue()))
            }
        } catch (error: Exception) {
            send(
                RunnerEvent.Interrupted(
                    "Android shell failed: ${error.message ?: error::class.java.simpleName}",
                ),
            )
        } finally {
            registered?.let { current ->
                active.remove(command.id, current)
                if (current.process.isAlive) terminate(current.process)
            }
        }
    }

    override suspend fun cancel(commandId: String): Boolean {
        val current = active[commandId] ?: return false
        current.cancelled.set(true)
        withContext(Dispatchers.IO) {
            terminate(current.process)
        }
        return true
    }

    private fun rejectionReason(command: CommandSpec): String? {
        if (command.id.isBlank()) return "Command id is required"
        if (command.environmentHandles.isNotEmpty()) {
            return "Environment injection is disabled for the Android shell"
        }
        if (command.timeoutMillis !in 1..MAX_TIMEOUT_MILLIS) {
            return "Timeout must be between 1 and $MAX_TIMEOUT_MILLIS ms"
        }
        if (command.outputLimitBytes !in 1..MAX_OUTPUT_BYTES) {
            return "Output limit must be between 1 and $MAX_OUTPUT_BYTES bytes"
        }

        val requestedDirectory = runCatching {
            File(command.workingDirectory).canonicalFile
        }.getOrElse {
            return "Working directory is invalid"
        }
        if (requestedDirectory != canonicalWorkspaceRoot) {
            return "Commands can only run in the app workspace"
        }
        if (command.executable != SHELL_PATH) {
            return "Commands must use the Android system shell"
        }
        if (command.arguments == listOf("-c", PROBE_SCRIPT)) {
            return null
        }
        if (command.arguments.size != 2 || command.arguments.firstOrNull() != "-c") {
            return "Android shell arguments are invalid"
        }
        val script = command.arguments[1]
        if (script.isBlank()) return "Android shell command is empty"
        if (script.toByteArray(Charsets.UTF_8).size > MAX_SCRIPT_BYTES) {
            return "Android shell command exceeds $MAX_SCRIPT_BYTES bytes"
        }

        val authorization = command.authorization
            ?: return "A request-bound execution authorization is required"
        if (!authorization.validates(command)) {
            return "Command changed after approval"
        }
        if (
            authorization.request.effect == ToolEffect.DESTRUCTIVE &&
            authorization.permit.method != ConfirmationMethod.STRONG
        ) {
            return "Critical shell commands require strong confirmation"
        }
        if (
            authorization.request.effect !in
            setOf(ToolEffect.EXTERNAL_MUTATION, ToolEffect.DESTRUCTIVE)
        ) {
            return "Arbitrary shell commands require an external-mutation policy class"
        }
        if (
            authorization.request.capabilities.none { it == Capability.SHELL_EXECUTE } ||
            authorization.request.payloadFingerprint == null
        ) {
            return "Authorization does not cover shell execution"
        }
        if (!consumedChallenges.add(authorization.permit.challengeId)) {
            return "Execution authorization was already consumed"
        }
        return null
    }

    private fun terminate(process: Process) {
        synchronized(process) {
            if (!process.isAlive) return
            process.destroy()
            if (!process.waitFor(GRACEFUL_STOP_MILLIS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(GRACEFUL_STOP_MILLIS, TimeUnit.MILLISECONDS)
            }
        }
    }

    private data class ActiveProcess(
        val process: Process,
        val cancelled: AtomicBoolean = AtomicBoolean(false),
    )

    private companion object {
        const val SHELL_PATH = "/system/bin/sh"
        const val MAX_TIMEOUT_MILLIS = 30_000L
        const val MAX_OUTPUT_BYTES = 65_536L
        const val USER_COMMAND_TIMEOUT_MILLIS = 30_000L
        const val USER_COMMAND_OUTPUT_BYTES = 65_536L
        const val MAX_SCRIPT_BYTES = 8_192
        const val STREAM_BUFFER_BYTES = 4_096
        const val GRACEFUL_STOP_MILLIS = 250L

        val PROBE_SCRIPT = """
            set -eu
            probe_file=".refrator-power-probe"
            trap 'rm -f "${'$'}probe_file"' EXIT HUP INT TERM
            printf 'refrator-power\n'
            id
            uname -a
            pwd
            printf 'workspace-write-ok\n' > "${'$'}probe_file"
            cat "${'$'}probe_file"
            rm -f "${'$'}probe_file"
            trap - EXIT HUP INT TERM
            test ! -e "${'$'}probe_file"
            printf 'cleanup-ok\n'
        """.trimIndent()
    }
}
