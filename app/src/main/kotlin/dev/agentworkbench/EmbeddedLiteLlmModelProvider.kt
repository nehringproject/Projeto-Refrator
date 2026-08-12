package dev.agentworkbench

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import dev.agentworkbench.core.MessagePart
import dev.agentworkbench.core.ModelCapability
import dev.agentworkbench.core.ModelProvider
import dev.agentworkbench.core.ProviderDescriptor
import dev.agentworkbench.core.ProviderEvent
import dev.agentworkbench.core.ProviderLocality
import dev.agentworkbench.core.ProviderMessage
import dev.agentworkbench.core.ProviderRequest
import java.io.File
import java.net.URI
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** ModelProvider adapter backed by the in-APK LiteLLM SDK over Binder, never a LAN URL. */
class EmbeddedLiteLlmModelProvider(
    context: Context,
    private val configuredModel: String,
    private val apiKey: String?,
    private val deployments: List<ManagedLiteLlmDeployment>,
) : ModelProvider {
    private val appContext = context.applicationContext
    private val activeServices = ConcurrentHashMap<String, ILiteLlmRuntimeService>()

    override val descriptor = ProviderDescriptor(
        id = "managed-litellm",
        displayName = "LiteLLM integrado",
        // The gateway is local, but its configured deployments may transmit prompts remotely.
        locality = ProviderLocality.REMOTE,
        capabilities = setOf(
            ModelCapability.TEXT,
            ModelCapability.VISION,
            ModelCapability.TOOL_CALLING,
            ModelCapability.PARALLEL_TOOLS,
            ModelCapability.REASONING_CONTROL,
        ),
        healthy = true,
        costTier = 1,
        priority = 100,
    )

    override fun stream(request: ProviderRequest): Flow<ProviderEvent> = flow {
        var madeProgress = false
        var deferredFailure: ProviderEvent.Failed? = null
        var malformedToolStream = false
        val pendingText = StringBuilder()
        val toolNames = linkedMapOf<String, String>()
        val toolArguments = linkedMapOf<String, StringBuilder>()
        val completedToolCalls = mutableSetOf<String>()
        val allowedTools = request.tools.mapTo(hashSetOf()) { it.name }
        try {
            managedStream(request).collect { event ->
                when (event) {
                    is ProviderEvent.TextDelta -> {
                        if (pendingText.length + event.value.length > MAX_BUFFERED_TEXT_CHARS) {
                            throw IllegalArgumentException("Resposta do provider excedeu o limite seguro.")
                        }
                        pendingText.append(event.value)
                    }

                    is ProviderEvent.ToolCallStarted -> {
                        if (event.toolName !in allowedTools) {
                            malformedToolStream = true
                        } else {
                            toolNames[event.callId] = event.toolName
                            toolArguments.getOrPut(event.callId, ::StringBuilder)
                        }
                    }

                    is ProviderEvent.ToolArgumentsDelta -> {
                        val arguments = toolArguments[event.callId]
                        if (arguments == null) {
                            malformedToolStream = true
                        } else if (arguments.length + event.jsonDelta.length > MAX_TOOL_ARGUMENT_CHARS) {
                            malformedToolStream = true
                        } else {
                            arguments.append(event.jsonDelta)
                        }
                    }

                    is ProviderEvent.ToolCallCompleted -> {
                        if (event.callId in toolNames) completedToolCalls += event.callId
                        else malformedToolStream = true
                    }

                    is ProviderEvent.Completed -> {
                        val validCalls = toolNames.all { (callId, _) ->
                            callId in completedToolCalls &&
                                isCompleteJsonObject(toolArguments[callId].toString())
                        }
                        malformedToolStream = malformedToolStream || !validCalls
                        if (malformedToolStream) {
                            pendingText.clear()
                            deferredFailure = ProviderEvent.Failed(
                                retryable = true,
                                safeMessage = "O provider devolveu uma chamada de ferramenta incompleta; tentando outra rota.",
                            )
                        } else {
                            if (toolNames.isNotEmpty()) {
                                pendingText.clear()
                                toolNames.forEach { (callId, name) ->
                                    emit(ProviderEvent.ToolCallStarted(callId, name))
                                    emit(
                                        ProviderEvent.ToolArgumentsDelta(
                                            callId,
                                            toolArguments[callId].toString().ifBlank { "{}" },
                                        ),
                                    )
                                    emit(ProviderEvent.ToolCallCompleted(callId))
                                }
                                madeProgress = true
                            } else if (pendingText.isNotEmpty()) {
                                emit(ProviderEvent.TextDelta(pendingText.toString()))
                                pendingText.clear()
                                madeProgress = true
                            }
                            emit(event)
                        }
                    }

                    is ProviderEvent.Failed -> {
                        if (event.retryable && !madeProgress) {
                            pendingText.clear()
                            deferredFailure = event
                        } else {
                            emit(event)
                        }
                    }

                    else -> emit(event)
                }
            }
        } catch (error: Throwable) {
            if (madeProgress) throw error
            deferredFailure = deferredFailure ?: ProviderEvent.Failed(
                true,
                error.message ?: "LiteLLM worker died",
            )
        }
        val failure = deferredFailure ?: return@flow
        if (!shouldUseKotlinFallback(failure)) {
            emit(failure)
        } else {
            val deployment = resolveDeployment(request.modelId)
            if (deployment == null) {
                emit(failure)
                return@flow
            }
            deployment.fallbackProvider.stream(
                request.copy(modelId = deployment.providerModel),
            ).collect { emit(it) }
        }
    }

    private fun isCompleteJsonObject(raw: String): Boolean {
        val value = raw.trim().ifBlank { "{}" }
        return value.startsWith('{') && value.endsWith('}') &&
            runCatching { JSONObject(value) }.isSuccess
    }

    private fun managedStream(request: ProviderRequest): Flow<ProviderEvent> = callbackFlow {
        val requestId = request.sessionId.take(128)
        val connection = try {
            bind()
        } catch (error: Throwable) {
            trySend(ProviderEvent.Failed(true, error.message ?: "LiteLLM worker unavailable"))
            close(error)
            return@callbackFlow
        }
        val service = connection.service
        activeServices[requestId] = service
        val terminal = AtomicBoolean(false)
        val callback = object : ILiteLlmCallback.Stub() {
            override fun onEvent(eventJson: String) {
                runCatching { parseEvent(eventJson) }
                    .onSuccess { event ->
                        if (event != null) {
                            if (event is ProviderEvent.Completed || event is ProviderEvent.Cancelled) {
                                terminal.set(true)
                            }
                            trySend(event)
                        }
                    }
                    .onFailure { error ->
                        if (terminal.compareAndSet(false, true)) {
                            trySend(ProviderEvent.Failed(false, "LiteLLM returned an invalid event"))
                            close(error)
                        }
                    }
            }

            override fun onCompleted(resultJson: String) {
                if (terminal.compareAndSet(false, true)) {
                    trySend(ProviderEvent.Completed("completed"))
                }
                close()
            }

            override fun onError(errorJson: String) {
                val value = runCatching { JSONObject(errorJson) }.getOrNull()
                if (terminal.compareAndSet(false, true)) {
                    trySend(
                        ProviderEvent.Failed(
                            retryable = value?.optBoolean("retryable", true) ?: true,
                            safeMessage = safeLiteLlmError(value?.optString("error")),
                        ),
                    )
                }
                close()
            }
        }

        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        val writer = launch(Dispatchers.IO) {
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { output ->
                    requestJson(request).byteInputStream(Charsets.UTF_8).use { input ->
                        input.copyTo(output, bufferSize = 32 * 1024)
                    }
                }
            }.onFailure { error ->
                if (terminal.compareAndSet(false, true)) {
                    trySend(ProviderEvent.Failed(true, "LiteLLM request pipe failed"))
                    close(error)
                }
            }
        }
        runCatching {
            service.streamCompletion(requestId, readEnd, callback)
            readEnd.close()
        }.onFailure { error ->
            runCatching { readEnd.close() }
            runCatching { writeEnd.close() }
            if (terminal.compareAndSet(false, true)) {
                trySend(ProviderEvent.Failed(true, error.message ?: "LiteLLM worker died"))
            }
            close(error)
        }

        awaitClose {
            writer.cancel()
            runCatching { readEnd.close() }
            runCatching { writeEnd.close() }
            activeServices.remove(requestId)
            runCatching { appContext.unbindService(connection) }
        }
    }

    override suspend fun cancel(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        activeServices[sessionId]?.cancel(sessionId) ?: run {
            val connection = runCatching { bind() }.getOrNull() ?: return@withContext false
            try {
                connection.service.cancel(sessionId)
            } finally {
                runCatching { appContext.unbindService(connection) }
            }
        }
    }

    private fun requestJson(request: ProviderRequest): String {
        val requestedModel = request.modelId.ifBlank { configuredModel }
        val automatic = deployments.isNotEmpty()
        val routerModel = if (automatic) MANAGED_LITELLM_AUTO_MODEL else requestedModel
        return JSONObject()
        .put("request_id", request.sessionId)
        .put("model", routerModel)
        .put("messages", JSONArray().apply { request.messages.forEach { put(messageJson(it)) } })
        .apply {
            if (deployments.isEmpty()) {
                apiKey?.takeIf(String::isNotBlank)?.let { put("api_key", it) }
            } else {
                put(
                    "deployments",
                    JSONArray().apply {
                        deployments.forEach { profile ->
                            put(
                                JSONObject()
                                    // Equivalent deployments deliberately share one alias. LiteLLM's
                                    // Router can then rotate, cool down and retry them without rebuilding
                                    // a different Router for every request.
                                    .put("model_name", MANAGED_LITELLM_AUTO_MODEL)
                                    .put(
                                        "litellm_params",
                                        JSONObject()
                                            .put("model", profile.litellmModel)
                                            .put("api_base", profile.apiBase)
                                            .apply {
                                                profile.apiKey?.takeIf(String::isNotBlank)
                                                    ?.let { put("api_key", it) }
                                            },
                                    ),
                            )
                        }
                    },
                )
            }
            if (request.tools.isNotEmpty()) {
                put(
                    "tools",
                    JSONArray().apply {
                        request.tools.forEach { tool ->
                            put(
                                JSONObject()
                                    .put("type", "function")
                                    .put(
                                        "function",
                                        JSONObject()
                                            .put("name", tool.name)
                                            .put("description", tool.description)
                                            .put("parameters", JSONObject(tool.inputJsonSchema)),
                                    ),
                            )
                        }
                    },
                )
                put("tool_choice", "auto")
            }
        }
        .toString()
    }

    private fun resolveDeployment(requestedModel: String): ManagedLiteLlmDeployment? =
        deployments.firstOrNull { profile ->
            requestedModel == profile.alias || requestedModel == profile.providerModel
        } ?: deployments.firstOrNull()

    private fun shouldUseKotlinFallback(failure: ProviderEvent.Failed): Boolean {
        val message = failure.safeMessage.lowercase()
        return failure.retryable ||
            message.contains("litellm worker") ||
            message.contains("binder") ||
            message.contains("service died")
    }

    private fun safeLiteLlmError(raw: String?): String {
        val message = raw.orEmpty().trim()
        val lower = message.lowercase()
        return when {
            message.isBlank() -> "O LiteLLM não conseguiu concluir a solicitação."
            "status': 410" in lower || "error code: 410" in lower || "end of life" in lower ->
                "O modelo selecionado foi retirado pelo provider. O pool será atualizado para usar um modelo ativo."
            "status': 429" in lower || "error code: 429" in lower || "rate limit" in lower ->
                "O provider atingiu o limite temporário. O roteador tentará outro deployment disponível."
            "unauthorized" in lower || "error code: 401" in lower ->
                "O provider recusou a credencial configurada. Revise a chave nas configurações."
            else -> message
                .replace(Regex("(?i)(nvapi-|gsk_|sk-)[A-Za-z0-9_\\-]{8,}"), "<redacted>")
                .lineSequence()
                .firstOrNull()
                ?.take(280)
                ?.ifBlank { "O LiteLLM não conseguiu concluir a solicitação." }
                ?: "O LiteLLM não conseguiu concluir a solicitação."
        }
    }

    private fun messageJson(message: ProviderMessage): JSONObject = JSONObject()
        .put("role", message.role)
        .put("content", messageContent(message.parts))
        .apply {
            message.toolCallId?.let { put("tool_call_id", it) }
            if (message.toolCalls.isNotEmpty()) {
                put(
                    "tool_calls",
                    JSONArray().apply {
                        message.toolCalls.forEach { call ->
                            put(
                                JSONObject()
                                    .put("id", call.callId)
                                    .put("type", "function")
                                    .put(
                                        "function",
                                        JSONObject()
                                            .put("name", call.toolName)
                                            .put("arguments", call.argumentsJson),
                                    ),
                            )
                        }
                    },
                )
            }
        }

    private fun messageContent(parts: List<MessagePart>): Any {
        if (parts.none { it is MessagePart.ImageReference }) {
            return parts.joinToString("\n") { part ->
                when (part) {
                    is MessagePart.Text -> part.value
                    is MessagePart.ToolResult -> part.payload
                    is MessagePart.ImageReference -> error("unreachable")
                }
            }
        }
        return JSONArray().apply {
            parts.forEach { part ->
                when (part) {
                    is MessagePart.Text -> if (part.value.isNotBlank()) {
                        put(JSONObject().put("type", "text").put("text", part.value))
                    }
                    is MessagePart.ToolResult -> if (part.payload.isNotBlank()) {
                        put(JSONObject().put("type", "text").put("text", part.payload))
                    }
                    is MessagePart.ImageReference -> put(
                        JSONObject()
                            .put("type", "image_url")
                            .put("image_url", JSONObject().put("url", imageDataUrl(part))),
                    )
                }
            }
        }
    }

    private fun imageDataUrl(part: MessagePart.ImageReference): String {
        require(part.mimeType.lowercase() in SUPPORTED_IMAGE_MIME_TYPES) {
            "Tipo de imagem não suportado: ${part.mimeType}"
        }
        val uri = URI.create(part.uri)
        require(uri.scheme.equals("file", ignoreCase = true)) { "Referência de imagem inválida." }
        val file = File(uri)
        require(file.isFile && file.length() in 1..MAX_IMAGE_BYTES) {
            "Imagem anexada ausente, vazia ou maior que 20 MiB."
        }
        return "data:${part.mimeType.lowercase()};base64," +
            Base64.getEncoder().encodeToString(file.readBytes())
    }

    private fun parseEvent(json: String): ProviderEvent? {
        val value = JSONObject(json)
        return when (value.getString("type")) {
            "model" -> ProviderEvent.ModelResolved(value.getString("model_id"))
            "reasoning_delta" -> ProviderEvent.ReasoningDelta(value.getString("value"))
            "text_delta" -> ProviderEvent.TextDelta(value.getString("value"))
            "tool_started" -> ProviderEvent.ToolCallStarted(
                value.getString("call_id"),
                value.getString("name"),
            )
            "tool_arguments" -> ProviderEvent.ToolArgumentsDelta(
                value.getString("call_id"),
                value.getString("value"),
            )
            "tool_completed" -> ProviderEvent.ToolCallCompleted(value.getString("call_id"))
            "usage" -> value.getJSONObject("usage").let { usage ->
                ProviderEvent.Usage(
                    inputTokens = usage.optLong("prompt_tokens", usage.optLong("input_tokens", 0)),
                    outputTokens = usage.optLong("completion_tokens", usage.optLong("output_tokens", 0)),
                    cachedInputTokens = null,
                )
            }
            "completed" -> ProviderEvent.Completed(value.optString("finish_reason", "completed"))
            "cancelled" -> ProviderEvent.Cancelled(value.optString("reason", "cancelled"))
            else -> null
        }
    }

    private suspend fun bind(): BoundLiteLlmService = suspendCancellableCoroutine { continuation ->
        lateinit var connection: BoundLiteLlmService
        connection = BoundLiteLlmService(
            onConnected = { service ->
                if (continuation.isActive) continuation.resume(connection.apply { this.service = service })
            },
            onFailure = { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            },
        )
        val bound = appContext.bindService(
            Intent(appContext, LiteLlmRuntimeService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        if (!bound && continuation.isActive) {
            continuation.resumeWithException(IllegalStateException("LiteLLM worker could not be bound"))
        }
        continuation.invokeOnCancellation {
            if (bound) runCatching { appContext.unbindService(connection) }
        }
    }

    private companion object {
        const val MAX_BUFFERED_TEXT_CHARS = 2_000_000
        const val MAX_TOOL_ARGUMENT_CHARS = 2_000_000
        const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
        val SUPPORTED_IMAGE_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif")
    }

    private class BoundLiteLlmService(
        private val onConnected: (ILiteLlmRuntimeService) -> Unit,
        private val onFailure: (Throwable) -> Unit,
    ) : ServiceConnection {
        lateinit var service: ILiteLlmRuntimeService

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val resolved = ILiteLlmRuntimeService.Stub.asInterface(binder)
            if (resolved == null) onFailure(IllegalStateException("LiteLLM returned no binder"))
            else onConnected(resolved)
        }

        override fun onServiceDisconnected(name: ComponentName?) = Unit
        override fun onBindingDied(name: ComponentName?) =
            onFailure(IllegalStateException("LiteLLM worker process died"))
        override fun onNullBinding(name: ComponentName?) =
            onFailure(IllegalStateException("LiteLLM worker refused binding"))
    }
}
