package dev.agentworkbench.runner.safe

import dev.agentworkbench.core.Capability
import dev.agentworkbench.core.CommandSpec
import dev.agentworkbench.core.EnvironmentTrust
import dev.agentworkbench.core.ExecutionRunner
import dev.agentworkbench.core.RunnerDescriptor
import dev.agentworkbench.core.RunnerEvent
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Executes a very small, immutable set of Android system diagnostics.
 *
 * This is deliberately not a general shell. Commands are matched by absolute
 * executable path and exact argument list before ProcessBuilder is reached.
 * Downloaded executables, shell parsing, caller-provided environment variables,
 * and arbitrary working directories are rejected.
 */
class AndroidSafeRunner(
    workspaceRoot: File,
) : ExecutionRunner {
    private val canonicalWorkspaceRoot = workspaceRoot.canonicalFile
    private val active = ConcurrentHashMap<String, ActiveProcess>()

    override val descriptor = RunnerDescriptor(
        id = "android-safe",
        displayName = "Android bounded diagnostics",
        environmentTrust = EnvironmentTrust.ANDROID_APP,
        capabilities = setOf(
            Capability.PROCESS_INSPECT,
            Capability.SHELL_EXECUTE,
        ),
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
            registered = ActiveProcess(process)
            if (active.putIfAbsent(command.id, registered) != null) {
                terminate(process)
                send(RunnerEvent.Rejected("A command with this id is already running"))
                return@channelFlow
            }

            val emittedBytes = AtomicLong(0)
            val outputLimitReached = AtomicBoolean(false)

            fun pump(stream: InputStream, stdout: Boolean) = launch(Dispatchers.IO) {
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

                !finished ->
                    send(RunnerEvent.Interrupted("Command timed out"))

                else ->
                    send(RunnerEvent.Completed(process.exitValue()))
            }
        } catch (error: Exception) {
            send(
                RunnerEvent.Interrupted(
                    "Diagnostic failed: ${error.message ?: error::class.java.simpleName}",
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
            return "Environment injection is not available to bounded diagnostics"
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
            return "Bounded diagnostics can only run in the app workspace"
        }

        val signature = CommandSignature(command.executable, command.arguments)
        if (signature !in ALLOWED_COMMANDS) {
            return "Command is outside the immutable diagnostic allowlist"
        }
        return null
    }

    private fun terminate(process: Process) {
        if (!process.isAlive) return
        process.destroy()
        if (!process.waitFor(GRACEFUL_STOP_MILLIS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor(GRACEFUL_STOP_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    private data class ActiveProcess(
        val process: Process,
        val cancelled: AtomicBoolean = AtomicBoolean(false),
    )

    private data class CommandSignature(
        val executable: String,
        val arguments: List<String>,
    )

    private companion object {
        const val MAX_TIMEOUT_MILLIS = 5_000L
        const val MAX_OUTPUT_BYTES = 32_768L
        const val STREAM_BUFFER_BYTES = 4_096
        const val GRACEFUL_STOP_MILLIS = 250L

        val ALLOWED_COMMANDS = setOf(
            CommandSignature("/system/bin/id", emptyList()),
            CommandSignature("/system/bin/uname", listOf("-a")),
            CommandSignature("/system/bin/pwd", emptyList()),
            CommandSignature("/system/bin/getprop", listOf("ro.product.model")),
            CommandSignature("/system/bin/getprop", listOf("ro.build.version.release")),
            CommandSignature("/system/bin/getprop", listOf("ro.build.version.sdk")),
        )
    }
}
