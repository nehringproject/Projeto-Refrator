package dev.agentworkbench

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import org.json.JSONObject

/** Trusted, fixed LiteLLM runtime. Workspace packages are never placed on this process' sys.path. */
class LiteLlmRuntimeService : Service() {
    private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "agent-litellm").apply { isDaemon = true }
    }
    private val active = ConcurrentHashMap<String, Future<*>>()

    @Volatile
    private var runtimeModule: PyObject? = null

    private val binder = object : ILiteLlmRuntimeService.Stub() {
        override fun status(): String = runCatching {
            module().callAttr("litellm_status").toString()
        }.getOrElse(::safeFailure)

        override fun streamCompletion(
            requestId: String,
            requestPipe: ParcelFileDescriptor,
            callback: ILiteLlmCallback,
        ) {
            if (!requestId.matches(REQUEST_ID_PATTERN)) {
                runCatching { requestPipe.close() }
                callback.onError(safeFailure(IllegalArgumentException("Invalid LiteLLM request")))
                return
            }
            active.remove(requestId)?.cancel(true)
            val future = executor.submit {
                try {
                    val requestJson = readRequest(requestPipe)
                    module().callAttr("litellm_stream", requestJson, callback)
                } catch (error: Throwable) {
                    runCatching { callback.onError(safeFailure(error)) }
                } finally {
                    active.remove(requestId)
                }
            }
            active[requestId] = future
        }

        override fun cancel(requestId: String): Boolean {
            if (!requestId.matches(REQUEST_ID_PATTERN)) return false
            runCatching { module().callAttr("litellm_cancel", requestId) }
            return active[requestId]?.cancel(true) ?: false
        }

        override fun shutdown() {
            active.values.forEach { it.cancel(true) }
            active.clear()
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        active.values.forEach { it.cancel(true) }
        active.clear()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun module(): PyObject = runtimeModule ?: synchronized(this) {
        runtimeModule ?: run {
            if (!Python.isStarted()) Python.start(AndroidPlatform(this))
            Python.getInstance().getModule("agent_runtime").also { runtimeModule = it }
        }
    }

    private fun readRequest(pipe: ParcelFileDescriptor): String =
        ParcelFileDescriptor.AutoCloseInputStream(pipe)
            .bufferedReader(Charsets.UTF_8)
            .use { reader ->
                val output = StringBuilder()
                val buffer = CharArray(16 * 1024)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (output.length + count > MAX_REQUEST_CHARS) {
                        throw IllegalArgumentException("LiteLLM request exceeds the provider context envelope")
                    }
                    output.append(buffer, 0, count)
                }
                output.toString()
            }

    private fun safeFailure(error: Throwable): String = JSONObject()
        .put("ok", false)
        .put(
            "error",
            RemoteContextRedactor.redactText(error.message ?: "LiteLLM worker failed").take(500),
        )
        .put("retryable", true)
        .toString()

    private companion object {
        val REQUEST_ID_PATTERN = Regex("[A-Za-z0-9._:-]{1,128}")
        const val MAX_REQUEST_CHARS = 64 * 1024 * 1024
    }
}
