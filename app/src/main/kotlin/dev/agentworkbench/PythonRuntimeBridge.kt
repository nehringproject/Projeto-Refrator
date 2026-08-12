package dev.agentworkbench

/**
 * Flavor-neutral contract for the embedded Python worker.
 *
 * Keeps Chaquopy and AIDL details outside callers and preserves the process boundary.
 */
interface PythonRuntimeBridge {
    suspend fun status(): String

    suspend fun execute(source: String, timeoutMillis: Long): String

    suspend fun runFile(path: String, timeoutMillis: Long): String

    suspend fun replOpen(): String

    suspend fun replWrite(sessionId: String, source: String): String

    suspend fun replInterrupt(sessionId: String): String

    suspend fun replClose(sessionId: String): String

    suspend fun packageInstall(requirement: String): String

    suspend fun packageList(): String

    suspend fun packageRemove(distribution: String): String

    suspend fun environmentStatus(): String

    suspend fun environmentReset(): String

    suspend fun test(): String
}
