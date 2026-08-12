package dev.agentworkbench

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class EmbeddedPythonRuntimeBridge(
    context: Context,
    workspaceRoot: File,
) : PythonRuntimeBridge {
    private val appContext = context.applicationContext
    private val workspace = workspaceRoot.canonicalFile
    private val workspaceId = MessageDigest.getInstance("SHA-256")
        .digest(workspace.path.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    override suspend fun status(): String = call { it.status() }

    override suspend fun execute(source: String, timeoutMillis: Long): String =
        call { it.execute(workspaceId, workspace.path, source, timeoutMillis) }

    override suspend fun runFile(path: String, timeoutMillis: Long): String =
        call { it.runFile(workspaceId, workspace.path, path, timeoutMillis) }

    override suspend fun replOpen(): String =
        call { it.replOpen(workspaceId, workspace.path) }

    override suspend fun replWrite(sessionId: String, source: String): String =
        call { it.replWrite(workspaceId, workspace.path, sessionId, source) }

    override suspend fun replInterrupt(sessionId: String): String =
        call { it.replInterrupt(sessionId) }

    override suspend fun replClose(sessionId: String): String =
        call { it.replClose(sessionId) }

    override suspend fun packageInstall(requirement: String): String =
        call { it.packageInstall(workspaceId, requirement) }

    override suspend fun packageList(): String = call { it.packageList(workspaceId) }

    override suspend fun packageRemove(distribution: String): String =
        call { it.packageRemove(workspaceId, distribution) }

    override suspend fun environmentStatus(): String =
        call { it.environmentStatus(workspaceId) }

    override suspend fun environmentReset(): String =
        call { it.environmentReset(workspaceId) }

    override suspend fun test(): String =
        call { it.test(workspaceId, workspace.path) }

    private suspend fun <T> call(block: (IPythonRuntimeService) -> T): T {
        val connection = bind()
        return try {
            withContext(Dispatchers.IO) { block(connection.service) }
        } finally {
            runCatching { appContext.unbindService(connection) }
        }
    }

    private suspend fun bind(): BoundPythonService = suspendCancellableCoroutine { continuation ->
        lateinit var connection: BoundPythonService
        connection = BoundPythonService(
            onConnected = { service ->
                if (continuation.isActive) continuation.resume(connection.apply { this.service = service })
            },
            onFailure = { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            },
        )
        val bound = appContext.bindService(
            Intent(appContext, PythonRuntimeService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        if (!bound && continuation.isActive) {
            continuation.resumeWithException(IllegalStateException("Python worker could not be bound"))
        }
        continuation.invokeOnCancellation {
            if (bound) runCatching { appContext.unbindService(connection) }
        }
    }

    private class BoundPythonService(
        private val onConnected: (IPythonRuntimeService) -> Unit,
        private val onFailure: (Throwable) -> Unit,
    ) : ServiceConnection {
        lateinit var service: IPythonRuntimeService

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val resolved = IPythonRuntimeService.Stub.asInterface(binder)
            if (resolved == null) {
                onFailure(IllegalStateException("Python worker returned no binder"))
            } else {
                onConnected(resolved)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) = Unit

        override fun onBindingDied(name: ComponentName?) {
            onFailure(IllegalStateException("Python worker process died"))
        }

        override fun onNullBinding(name: ComponentName?) {
            onFailure(IllegalStateException("Python worker refused binding"))
        }
    }
}
