package dev.agentworkbench

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.json.JSONObject

/** Runs untrusted workspace Python in its own app process (`:python`). */
class PythonRuntimeService : Service() {
    private val pythonExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "agent-python").apply { isDaemon = true }
    }

    private val binder = object : IPythonRuntimeService.Stub() {
        override fun status(): String = invoke(DEFAULT_TIMEOUT_MILLIS) {
            module().callAttr("runtime_status").asJson()
        }

        override fun execute(
            workspaceId: String,
            workspacePath: String,
            source: String,
            timeoutMillis: Long,
        ): String = invoke(timeoutMillis) {
            val workspace = requireWorkspace(workspacePath)
            module().callAttr(
                "execute",
                source,
                workspace.path,
                overlay(workspaceId).path,
            ).asJson()
        }

        override fun runFile(
            workspaceId: String,
            workspacePath: String,
            path: String,
            timeoutMillis: Long,
        ): String = invoke(timeoutMillis) {
            val workspace = requireWorkspace(workspacePath)
            module().callAttr(
                "run_file",
                path,
                workspace.path,
                overlay(workspaceId).path,
            ).asJson()
        }

        override fun replOpen(workspaceId: String, workspacePath: String): String =
            invoke(DEFAULT_TIMEOUT_MILLIS) {
                val workspace = requireWorkspace(workspacePath)
                module().callAttr(
                    "repl_open",
                    workspace.path,
                    overlay(workspaceId).path,
                ).asJson()
            }

        override fun replWrite(
            workspaceId: String,
            workspacePath: String,
            sessionId: String,
            source: String,
        ): String = invoke(REPL_TIMEOUT_MILLIS) {
            val workspace = requireWorkspace(workspacePath)
            module().callAttr(
                "repl_write",
                sessionId,
                source,
                workspace.path,
                overlay(workspaceId).path,
            ).asJson()
        }

        override fun replInterrupt(sessionId: String): String = JSONObject()
            .put("ok", true)
            .put("interrupted", true)
            .put("session_id", sessionId.take(128))
            .put("worker_restarting", true)
            .toString()
            .also { restartWorkerProcess() }

        override fun replClose(sessionId: String): String = invoke(DEFAULT_TIMEOUT_MILLIS) {
            module().callAttr("repl_close", sessionId).asJson()
        }

        override fun packageInstall(workspaceId: String, requirement: String): String =
            invoke(PACKAGE_TIMEOUT_MILLIS) {
                module().callAttr(
                    "package_install",
                    requirement,
                    overlay(workspaceId).path,
                    wheelCache().path,
                ).asJson()
            }

        override fun packageList(workspaceId: String): String = invoke(DEFAULT_TIMEOUT_MILLIS) {
            module().callAttr("package_list", overlay(workspaceId).path).asJson()
        }

        override fun packageRemove(workspaceId: String, distribution: String): String =
            invoke(DEFAULT_TIMEOUT_MILLIS) {
                module().callAttr(
                    "package_remove",
                    distribution,
                    overlay(workspaceId).path,
                ).asJson()
            }

        override fun environmentStatus(workspaceId: String): String =
            invoke(DEFAULT_TIMEOUT_MILLIS) {
                module().callAttr("environment_status", overlay(workspaceId).path).asJson()
            }

        override fun environmentReset(workspaceId: String): String =
            invoke(DEFAULT_TIMEOUT_MILLIS) {
                module().callAttr("environment_reset", overlay(workspaceId).path).asJson()
            }

        override fun test(workspaceId: String, workspacePath: String): String =
            invoke(DEFAULT_TIMEOUT_MILLIS) {
                val workspace = requireWorkspace(workspacePath)
                module().callAttr(
                    "runtime_test",
                    workspace.path,
                    overlay(workspaceId).path,
                ).asJson()
            }

        override fun shutdown() {
            stopSelf()
        }
    }

    @Volatile
    private var runtimeModule: PyObject? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        pythonExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun module(): PyObject = runtimeModule ?: synchronized(this) {
        runtimeModule ?: run {
            if (!Python.isStarted()) Python.start(AndroidPlatform(this))
            Python.getInstance().getModule("agent_runtime").also { runtimeModule = it }
        }
    }

    private fun requireWorkspace(path: String): File {
        val root = filesDir.canonicalFile
        val candidate = File(path).canonicalFile
        require(candidate.isDirectory) { "Workspace does not exist" }
        require(candidate.path == root.path || candidate.path.startsWith(root.path + File.separator)) {
            "Workspace is outside app-private storage"
        }
        return candidate
    }

    private fun overlay(workspaceId: String): File {
        require(workspaceId.matches(Regex("[a-f0-9]{64}"))) { "Invalid workspace identifier" }
        return File(filesDir, "python-workspaces/$workspaceId/site-packages").apply { mkdirs() }
    }

    private fun wheelCache(): File = File(noBackupFilesDir, "python-wheel-cache").apply { mkdirs() }

    private fun invoke(timeoutMillis: Long, operation: () -> String): String {
        val boundedTimeout = timeoutMillis.coerceIn(1_000, MAX_TIMEOUT_MILLIS)
        val future = pythonExecutor.submit(Callable(operation))
        return try {
            future.get(boundedTimeout, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            restartWorkerProcess()
            failure("Python execution timed out", restartRequired = true)
        } catch (error: ExecutionException) {
            failure(error.cause?.message ?: "Python worker failed")
        } catch (error: Exception) {
            failure(error.message ?: "Python worker failed")
        }
    }

    private fun PyObject.asJson(): String = toString()

    private fun failure(message: String, restartRequired: Boolean = false): String =
        JSONObject()
            .put("ok", false)
            .put("error", RemoteContextRedactor.redactText(message).take(500))
            .put("restart_required", restartRequired)
            .toString()

    /** A stuck native/Python frame can't be safely reused after Java interruption. */
    private fun restartWorkerProcess() {
        Handler(Looper.getMainLooper()).postDelayed(
            {
                stopSelf()
                Process.killProcess(Process.myPid())
            },
            WORKER_RESTART_DELAY_MILLIS,
        )
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 30_000L
        const val REPL_TIMEOUT_MILLIS = 120_000L
        const val PACKAGE_TIMEOUT_MILLIS = 15L * 60L * 1_000L
        const val MAX_TIMEOUT_MILLIS = 30L * 60L * 1_000L
        const val WORKER_RESTART_DELAY_MILLIS = 350L
    }
}
