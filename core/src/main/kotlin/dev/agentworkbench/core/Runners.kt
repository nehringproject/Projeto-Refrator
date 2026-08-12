package dev.agentworkbench.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow

data class RunnerDescriptor(
    val id: String,
    val displayName: String,
    val environmentTrust: EnvironmentTrust,
    val capabilities: Set<Capability>,
)

data class CommandSpec(
    val id: String,
    val executable: String,
    val arguments: List<String>,
    val workingDirectory: String,
    val environmentHandles: Map<String, String> = emptyMap(),
    val timeoutMillis: Long,
    val outputLimitBytes: Long,
    val idempotent: Boolean,
    val authorization: CommandAuthorization? = null,
)

fun CommandSpec.fingerprint(): String {
    val canonical = buildString {
        append(id)
        append('\u0000')
        append(executable)
        append('\u0000')
        arguments.forEach {
            append(it)
            append('\u001f')
        }
        append(workingDirectory)
        append('\u0000')
        environmentHandles.toSortedMap().forEach { (key, value) ->
            append(key)
            append('=')
            append(value)
            append('\u001f')
        }
        append(timeoutMillis)
        append('\u0000')
        append(outputLimitBytes)
        append('\u0000')
        append(idempotent)
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

sealed interface RunnerEvent {
    data class Started(val commandId: String) : RunnerEvent
    data class StandardOutput(val bytes: ByteArray) : RunnerEvent
    data class StandardError(val bytes: ByteArray) : RunnerEvent
    data class Completed(val exitCode: Int) : RunnerEvent
    data class Rejected(val reason: String) : RunnerEvent
    data class Interrupted(val reason: String) : RunnerEvent
}

interface ExecutionRunner {
    val descriptor: RunnerDescriptor

    fun execute(command: CommandSpec): Flow<RunnerEvent>

    suspend fun cancel(commandId: String): Boolean
}
