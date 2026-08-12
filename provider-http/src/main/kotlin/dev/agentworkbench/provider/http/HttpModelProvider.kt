package dev.agentworkbench.provider.http

import dev.agentworkbench.core.MessagePart
import dev.agentworkbench.core.ModelCapability
import dev.agentworkbench.core.ModelProvider
import dev.agentworkbench.core.ProviderDescriptor
import dev.agentworkbench.core.ProviderEvent
import dev.agentworkbench.core.ProviderRequest
import dev.agentworkbench.core.ServerSentEvent
import dev.agentworkbench.core.ServerSentEventDecoder
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.Reader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class HttpModelProvider(
    private val config: HttpProviderConfig,
) : ModelProvider {
    private val active = ConcurrentHashMap<String, ActiveCall>()

    override val descriptor = ProviderDescriptor(
        id = config.providerId,
        displayName = config.displayName,
        locality = config.locality,
        capabilities = setOf(
            ModelCapability.TEXT,
            ModelCapability.VISION,
            ModelCapability.TOOL_CALLING,
            ModelCapability.JSON_SCHEMA,
            ModelCapability.REASONING_CONTROL,
        ),
        healthy = EndpointPolicy.validate(config) is EndpointValidation.Allowed,
        costTier = if (config.locality == dev.agentworkbench.core.ProviderLocality.REMOTE) 2 else 0,
        priority = 50,
    )

    override fun stream(request: ProviderRequest): Flow<ProviderEvent> = channelFlow {
        val validation = EndpointPolicy.validate(config)
        if (validation is EndpointValidation.Rejected) {
            send(ProviderEvent.Failed(false, validation.reason))
            return@channelFlow
        }
        validation as EndpointValidation.Allowed

        val call = ActiveCall()
        if (active.putIfAbsent(request.sessionId, call) != null) {
            send(ProviderEvent.Failed(false, "A request for this session is already running"))
            return@channelFlow
        }

        try {
            withContext(Dispatchers.IO) {
                executeRequest(
                    uri = validation.uri,
                    request = request,
                    call = call,
                    emit = { send(it) },
                )
            }
        } catch (error: Exception) {
            if (call.cancelled.get()) {
                send(ProviderEvent.Cancelled())
            } else {
                send(
                    ProviderEvent.Failed(
                        retryable = isRetryable(error),
                        safeMessage = safeFailure(error),
                    ),
                )
            }
        } finally {
            active.remove(request.sessionId, call)
            call.connection?.disconnect()
        }
    }

    override suspend fun cancel(sessionId: String): Boolean {
        val call = active[sessionId] ?: return false
        call.cancelled.set(true)
        call.connection?.disconnect()
        return true
    }

    private suspend fun executeRequest(
        uri: URI,
        request: ProviderRequest,
        call: ActiveCall,
        emit: suspend (ProviderEvent) -> Unit,
    ) {
        val body = buildRequestBody(request).toByteArray(StandardCharsets.UTF_8)
        val connection = uri.toURL().openConnection() as HttpURLConnection
        call.connection = connection
        connection.requestMethod = "POST"
        connection.instanceFollowRedirects = false
        connection.connectTimeout = config.connectTimeoutMillis
        connection.readTimeout = config.readTimeoutMillis
        connection.doOutput = true
        connection.useCaches = false
        connection.setFixedLengthStreamingMode(body.size)
        connection.setRequestProperty("Accept", "text/event-stream")
        connection.setRequestProperty("Cache-Control", "no-cache")
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        config.authorizationToken?.let { token ->
            connection.setRequestProperty("Authorization", "Bearer $token")
        }

        connection.outputStream.use { output ->
            output.write(body)
        }

        val status = connection.responseCode
        if (status !in 200..299) {
            val errorBody = connection.errorStream
                ?.bufferedReader(StandardCharsets.UTF_8)
                ?.use { readBounded(it, MAX_ERROR_BODY_CHARS) }
                .orEmpty()
            emit(
                ProviderEvent.Failed(
                    retryable = status in RETRYABLE_HTTP_STATUSES || status >= 500,
                    safeMessage = safeHttpError(status, errorBody),
                ),
            )
            return
        }

        val contentType = connection.contentType.orEmpty().lowercase()
        if (!contentType.contains("text/event-stream")) {
            emit(ProviderEvent.Failed(false, "Provider did not return an SSE stream"))
            return
        }

        val mapper = ProviderEventMapper(config.protocol)
        val decoder = ServerSentEventDecoder()
        InputStreamReader(connection.inputStream, StandardCharsets.UTF_8)
            .use { input ->
                val reader = BoundedLineReader(input, MAX_SSE_LINE_CHARS)
                while (true) {
                    if (call.cancelled.get()) {
                        emit(ProviderEvent.Cancelled())
                        return
                    }
                    val line = reader.readLine() ?: break
                    decoder.acceptLine(line)?.let { event ->
                        mapper.map(event).forEach { emit(it) }
                    }
                }
                decoder.finish()?.let { event ->
                    mapper.map(event).forEach { emit(it) }
                }
            }

        when {
            call.cancelled.get() -> emit(ProviderEvent.Cancelled())
            !mapper.terminalEmitted ->
                emit(ProviderEvent.Failed(true, "Provider stream ended before completion"))
        }
    }

    private fun buildRequestBody(request: ProviderRequest): String =
        when (config.protocol) {
            HttpProviderProtocol.OPENAI_RESPONSES -> buildResponsesBody(request)
            HttpProviderProtocol.OPENAI_COMPATIBLE_CHAT -> buildChatCompletionsBody(request)
        }.toString()

    private fun buildResponsesBody(request: ProviderRequest): JSONObject =
        JSONObject()
            .put("model", request.modelId)
            .put("stream", true)
            .put("store", false)
            .put("input", responsesInputJson(request))
            .put(
                "reasoning",
                // Sem "summary" a Responses API trata o raciocínio como interno e nunca
                // transmite os eventos response.reasoning_*.delta — o parser abaixo ficava
                // esperando um evento que a API não tinha motivo pra mandar.
                JSONObject()
                    .put("effort", "medium")
                    .put("summary", "auto"),
            )
            .put(
                "text",
                JSONObject().put("verbosity", "medium"),
            )
            .apply {
                config.safetyIdentifier?.takeIf { it.isNotBlank() }?.let {
                    put("safety_identifier", it)
                }
                if (request.tools.isNotEmpty()) {
                    put(
                        "tools",
                        JSONArray().apply {
                            request.tools.forEach { tool ->
                                put(
                                    JSONObject()
                                        .put("type", "function")
                                        .put("name", tool.name)
                                        .put("description", tool.description)
                                        .put("parameters", JSONObject(tool.inputJsonSchema))
                                        .put("strict", false),
                                )
                            }
                        },
                    )
                }
            }

    private fun buildChatCompletionsBody(request: ProviderRequest): JSONObject =
        JSONObject()
            .put("model", request.modelId)
            .put("stream", true)
            .put(
                "stream_options",
                JSONObject().put("include_usage", true),
            )
            .put("messages", chatMessagesJson(request))
            .apply {
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
                                                .put(
                                                    "parameters",
                                                    JSONObject(tool.inputJsonSchema),
                                                ),
                                        ),
                                )
                            }
                        },
                    )
                }
            }

    private fun chatMessagesJson(request: ProviderRequest): JSONArray =
        JSONArray().apply {
            request.messages.forEach { message ->
                require(message.role in SUPPORTED_ROLES) {
                    "Unsupported provider message role"
                }
                val value = JSONObject().put("role", message.role)
                val text = messageText(message.parts)
                if (message.role == "assistant" && message.toolCalls.isNotEmpty()) {
                    value.put("content", text.takeIf(String::isNotBlank) ?: JSONObject.NULL)
                    value.put(
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
                } else {
                    value.put("content", chatContent(message.parts))
                }
                if (message.role == "tool") {
                    val callId = message.toolCallId
                        ?.takeIf(String::isNotBlank)
                        ?: throw IllegalArgumentException("Tool result call id is required")
                    value.put("tool_call_id", callId)
                }
                put(value)
            }
        }

    private fun responsesInputJson(request: ProviderRequest): JSONArray =
        JSONArray().apply {
            request.messages.forEach { message ->
                require(message.role in SUPPORTED_ROLES) {
                    "Unsupported provider message role"
                }
                if (message.role == "tool") {
                    val callId = message.toolCallId
                        ?.takeIf(String::isNotBlank)
                        ?: throw IllegalArgumentException("Tool result call id is required")
                    put(
                        JSONObject()
                            .put("type", "function_call_output")
                            .put("call_id", callId)
                            .put("output", messageText(message.parts)),
                    )
                } else {
                    val text = messageText(message.parts)
                    if (message.parts.isNotEmpty() || message.toolCalls.isEmpty()) {
                        put(
                            JSONObject()
                                .put("role", message.role)
                                .put("content", responsesContent(message.role, message.parts)),
                        )
                    }
                    message.toolCalls.forEach { call ->
                        put(
                            JSONObject()
                                .put("type", "function_call")
                                .put("call_id", call.callId)
                                .put("name", call.toolName)
                                .put("arguments", call.argumentsJson),
                        )
                    }
                }
            }
        }

    private fun messageText(parts: List<MessagePart>): String =
        parts.mapNotNull { part ->
            when (part) {
                is MessagePart.Text -> part.value
                is MessagePart.ToolResult -> part.payload
                is MessagePart.ImageReference -> null
            }
        }.joinToString("\n")

    private fun chatContent(parts: List<MessagePart>): Any {
        if (parts.none { it is MessagePart.ImageReference }) return messageText(parts)
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
                            .put(
                                "image_url",
                                JSONObject()
                                    .put("url", imageDataUrl(part))
                                    .put("detail", "auto"),
                            ),
                    )
                }
            }
        }
    }

    private fun responsesContent(role: String, parts: List<MessagePart>): Any {
        if (parts.none { it is MessagePart.ImageReference }) return messageText(parts)
        val textType = if (role == "assistant") "output_text" else "input_text"
        return JSONArray().apply {
            parts.forEach { part ->
                when (part) {
                    is MessagePart.Text -> if (part.value.isNotBlank()) {
                        put(JSONObject().put("type", textType).put("text", part.value))
                    }
                    is MessagePart.ToolResult -> if (part.payload.isNotBlank()) {
                        put(JSONObject().put("type", textType).put("text", part.payload))
                    }
                    is MessagePart.ImageReference -> put(
                        JSONObject()
                            .put("type", "input_image")
                            .put("image_url", imageDataUrl(part)),
                    )
                }
            }
        }
    }

    private fun imageDataUrl(part: MessagePart.ImageReference): String {
        require(part.mimeType.lowercase() in SUPPORTED_IMAGE_MIME_TYPES) {
            "Unsupported image type: ${part.mimeType}"
        }
        val uri = URI.create(part.uri)
        require(uri.scheme.equals("file", ignoreCase = true)) {
            "Only app-imported image files can be sent"
        }
        val file = File(uri)
        require(file.isFile) { "Attached image is no longer available" }
        require(file.length() in 1..MAX_IMAGE_BYTES) {
            "Attached image must be between 1 byte and ${MAX_IMAGE_BYTES / (1024 * 1024)} MiB"
        }
        val encoded = Base64.getEncoder().encodeToString(file.readBytes())
        return "data:${part.mimeType.lowercase()};base64,$encoded"
    }

    private fun safeHttpError(status: Int, body: String): String {
        val detail = runCatching {
            val root = JSONObject(body)
            val error = root.optJSONObject("error")
            when {
                error != null -> error.optString("message")
                else -> root.optString("message")
            }
        }.getOrNull()
            ?.let(::sanitizeProviderError)
            .orEmpty()
        return if (detail.isBlank()) {
            "Provider returned HTTP $status"
        } else {
            "Provider returned HTTP $status: $detail"
        }
    }

    private fun safeFailure(error: Exception): String =
        when (error) {
            is SocketTimeoutException -> "Provider request timed out"
            is UnknownHostException -> "Provider host could not be resolved"
            is SSLException -> "Secure connection to provider failed"
            is JSONException -> "Provider returned invalid JSON"
            is IOException -> "Provider connection failed"
            is IllegalArgumentException -> error.message?.let(::sanitizeProviderError)
                ?: "Provider request was invalid"

            else -> "Provider request failed"
        }

    private fun isRetryable(error: Exception): Boolean =
        error !is SSLException &&
            (
                error is SocketTimeoutException ||
                    error is UnknownHostException ||
                    error is IOException
                )

    private fun readBounded(reader: BufferedReader, maxChars: Int): String {
        val output = StringBuilder()
        val buffer = CharArray(1_024)
        while (output.length < maxChars) {
            val count = reader.read(
                buffer,
                0,
                minOf(buffer.size, maxChars - output.length),
            )
            if (count < 0) break
            output.append(buffer, 0, count)
        }
        return output.toString()
    }

    private fun sanitizeProviderError(value: String): String {
        var safe = PROVIDER_SECRET_PREFIX.replace(value, "[REDACTED]")
        safe = PROVIDER_BEARER_SECRET.replace(safe, "Bearer [REDACTED]")
        safe = PROVIDER_LABELLED_SECRET.replace(safe) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}[REDACTED]"
        }
        return safe.replace(Regex("\\s+"), " ").trim().take(MAX_SAFE_ERROR_CHARS)
    }

    private class ActiveCall(
        val cancelled: AtomicBoolean = AtomicBoolean(false),
        @Volatile var connection: HttpURLConnection? = null,
    )

    private companion object {
        const val MAX_ERROR_BODY_CHARS = 4_096
        const val MAX_SAFE_ERROR_CHARS = 320
        const val MAX_SSE_LINE_CHARS = 1_048_576
        const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
        val PROVIDER_SECRET_PREFIX = Regex("(?i)\\b(?:gsk_|nvapi-|sk-|kn-)[A-Za-z0-9_-]{12,}")
        val PROVIDER_BEARER_SECRET = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]{12,}=*")
        val PROVIDER_LABELLED_SECRET = Regex(
            "(?i)\\b(api[_ -]?key|token|secret|password|senha)(\\s*[:=]\\s*)([^\\s,;]{3,})",
        )
        val RETRYABLE_HTTP_STATUSES = setOf(408, 409, 425, 429)
        val SUPPORTED_ROLES = setOf("system", "developer", "user", "assistant", "tool")
        val SUPPORTED_IMAGE_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif")
    }
}

private class BoundedLineReader(
    private val reader: Reader,
    private val maxLineChars: Int,
) {
    private val buffer = CharArray(8_192)
    private var position = 0
    private var limit = 0

    fun readLine(): String? {
        val line = StringBuilder()
        while (true) {
            val value = readChar()
            if (value < 0) return line.takeIf { it.isNotEmpty() }?.toString()
            when (val char = value.toChar()) {
                '\n' -> return line.toString()
                '\r' -> Unit
                else -> {
                    if (line.length >= maxLineChars) {
                        throw IOException("Provider stream line exceeded the safety limit")
                    }
                    line.append(char)
                }
            }
        }
    }

    private fun readChar(): Int {
        if (position >= limit) {
            limit = reader.read(buffer)
            position = 0
            if (limit < 0) return -1
        }
        return buffer[position++].code
    }
}

internal class ProviderEventMapper(
    private val protocol: HttpProviderProtocol,
) {
    var terminalEmitted: Boolean = false
        private set

    private val responseCallIds = mutableMapOf<String, String>()
    private val chatCallIds = mutableMapOf<Int, String>()
    private val chatCallNames = mutableMapOf<Int, StringBuilder>()
    private val chatPendingArguments = mutableMapOf<Int, StringBuilder>()
    private val chatStartedCallIds = mutableSetOf<String>()
    private var chatFinishReason: String? = null
    private var resolvedModelId: String? = null

    fun map(event: ServerSentEvent): List<ProviderEvent> {
        if (event.data == "[DONE]") {
            if (terminalEmitted) return emptyList()
            terminalEmitted = true
            return listOf(ProviderEvent.Completed(chatFinishReason ?: "stop"))
        }
        val root = JSONObject(event.data)
        return when (protocol) {
            HttpProviderProtocol.OPENAI_RESPONSES -> mapResponses(root)
            HttpProviderProtocol.OPENAI_COMPATIBLE_CHAT -> mapChat(root)
        }
    }

    private fun mapResponses(root: JSONObject): List<ProviderEvent> =
        when (root.stringOrNull("type").orEmpty()) {
            "response.created" -> {
                val response = root.optJSONObject("response")
                modelResolved(response?.stringOrNull("model").orEmpty()) +
                    continuation(response)
            }

            "response.output_text.delta" ->
                root.stringOrNull("delta").orEmpty()
                    .takeIf { it.isNotEmpty() }
                    ?.let { listOf(ProviderEvent.TextDelta(it)) }
                    .orEmpty()

            "response.reasoning_text.delta",
            "response.reasoning_summary_text.delta",
            ->
                root.stringOrNull("delta").orEmpty()
                    .takeIf { it.isNotEmpty() }
                    ?.let { listOf(ProviderEvent.ReasoningDelta(it)) }
                    .orEmpty()

            "response.output_item.added" -> {
                val item = root.optJSONObject("item")
                if (item?.stringOrNull("type") != "function_call") return emptyList()
                val itemId = item.stringOrNull("id").orEmpty()
                val callId = item.stringOrNull("call_id") ?: itemId
                val name = item.stringOrNull("name").orEmpty()
                if (itemId.isNotBlank()) responseCallIds[itemId] = callId
                if (callId.isBlank() || name.isBlank()) {
                    emptyList()
                } else {
                    listOf(ProviderEvent.ToolCallStarted(callId, name))
                }
            }

            "response.function_call_arguments.delta" -> {
                val itemId = root.stringOrNull("item_id").orEmpty()
                val callId = responseCallIds[itemId].orEmpty()
                val delta = root.stringOrNull("delta").orEmpty()
                if (callId.isBlank() || delta.isEmpty()) {
                    emptyList()
                } else {
                    listOf(ProviderEvent.ToolArgumentsDelta(callId, delta))
                }
            }

            "response.function_call_arguments.done" -> {
                val callId = responseCallIds[root.stringOrNull("item_id").orEmpty()].orEmpty()
                if (callId.isBlank()) {
                    emptyList()
                } else {
                    listOf(ProviderEvent.ToolCallCompleted(callId))
                }
            }

            "response.completed" -> {
                terminalEmitted = true
                val response = root.optJSONObject("response")
                buildList {
                    addAll(modelResolved(response?.stringOrNull("model").orEmpty()))
                    addAll(continuation(response))
                    usage(response?.optJSONObject("usage"))?.let(::add)
                    add(
                        ProviderEvent.Completed(
                            response?.stringOrNull("status")
                                ?.takeIf { it.isNotBlank() }
                                ?: "completed",
                        ),
                    )
                }
            }

            "response.failed", "error" -> {
                terminalEmitted = true
                listOf(
                    ProviderEvent.Failed(
                        retryable = false,
                        safeMessage = safeProviderError(root),
                    ),
                )
            }

            else -> emptyList()
        }

    private fun mapChat(root: JSONObject): List<ProviderEvent> {
        root.optJSONObject("error")?.let {
            terminalEmitted = true
            return listOf(
                ProviderEvent.Failed(
                    retryable = false,
                    safeMessage = safeProviderError(root),
                ),
            )
        }

        val events = mutableListOf<ProviderEvent>()
        events += modelResolved(root.stringOrNull("model").orEmpty())
        usage(root.optJSONObject("usage"))?.let(events::add)
        val choices = root.optJSONArray("choices") ?: return events
        for (choiceIndex in 0 until choices.length()) {
            val choice = choices.optJSONObject(choiceIndex) ?: continue
            val delta = choice.optJSONObject("delta")
            listOf("reasoning", "reasoning_content")
                .mapNotNull { field ->
                    delta?.stringOrNull(field)
                        ?.takeIf { it.isNotEmpty() }
                }
                .distinct()
                .forEach { reasoning ->
                    events += ProviderEvent.ReasoningDelta(reasoning)
                }
            delta?.stringOrNull("content")
                ?.takeIf { it.isNotEmpty() }
                ?.let { events += ProviderEvent.TextDelta(it) }

            val toolCalls = delta?.optJSONArray("tool_calls")
            if (toolCalls != null) {
                for (index in 0 until toolCalls.length()) {
                    val call = toolCalls.optJSONObject(index) ?: continue
                    val callIndex = call.optInt("index", index)
                    val id = call.stringOrNull("id").orEmpty()
                    if (id.isNotBlank()) chatCallIds[callIndex] = id
                    val callId = chatCallIds[callIndex].orEmpty()
                    val function = call.optJSONObject("function")
                    val name = function?.stringOrNull("name").orEmpty()
                    if (name.isNotBlank()) {
                        chatCallNames.getOrPut(callIndex, ::StringBuilder).append(name)
                    }
                    val arguments = function?.stringOrNull("arguments").orEmpty()
                    if (arguments.isNotEmpty()) {
                        if (callId.isBlank()) {
                            chatPendingArguments.getOrPut(callIndex, ::StringBuilder).append(arguments)
                        } else {
                            val assembledName = chatCallNames[callIndex]?.toString().orEmpty()
                            if (assembledName.isNotBlank() && chatStartedCallIds.add(callId)) {
                                events += ProviderEvent.ToolCallStarted(callId, assembledName)
                            }
                            val pending = chatPendingArguments.remove(callIndex)?.toString().orEmpty()
                            if (pending.isNotEmpty()) {
                                events += ProviderEvent.ToolArgumentsDelta(callId, pending)
                            }
                            events += ProviderEvent.ToolArgumentsDelta(callId, arguments)
                        }
                    } else if (callId.isNotBlank()) {
                        chatPendingArguments.remove(callIndex)?.toString()?.takeIf(String::isNotEmpty)?.let {
                            events += ProviderEvent.ToolArgumentsDelta(callId, it)
                        }
                    }
                }
            }

            val finishReason = choice.stringOrNull("finish_reason").orEmpty()
            if (
                finishReason.isNotBlank() &&
                finishReason != "null" &&
                chatFinishReason == null
            ) {
                chatCallIds.forEach { (callIndex, callId) ->
                    val assembledName = chatCallNames[callIndex]?.toString().orEmpty()
                    if (assembledName.isNotBlank() && chatStartedCallIds.add(callId)) {
                        events += ProviderEvent.ToolCallStarted(callId, assembledName)
                    }
                    chatPendingArguments.remove(callIndex)?.toString()
                        ?.takeIf(String::isNotEmpty)
                        ?.let { events += ProviderEvent.ToolArgumentsDelta(callId, it) }
                    events += ProviderEvent.ToolCallCompleted(callId)
                }
                chatFinishReason = finishReason
            }
        }
        return events
    }

    private fun modelResolved(modelId: String): List<ProviderEvent> {
        if (modelId.isBlank() || modelId == resolvedModelId) return emptyList()
        resolvedModelId = modelId
        return listOf(ProviderEvent.ModelResolved(modelId))
    }

    private fun continuation(response: JSONObject?): List<ProviderEvent> {
        val responseId = response?.stringOrNull("id").orEmpty()
        return if (responseId.isBlank()) {
            emptyList()
        } else {
            listOf(ProviderEvent.Continuation(mapOf("response_id" to responseId)))
        }
    }

    private fun usage(value: JSONObject?): ProviderEvent.Usage? {
        value ?: return null
        val input = value.optLong("input_tokens", value.optLong("prompt_tokens", -1))
        val output = value.optLong("output_tokens", value.optLong("completion_tokens", -1))
        if (input < 0 || output < 0) return null
        val cached = value
            .optJSONObject("input_tokens_details")
            ?.optLong("cached_tokens", -1)
            ?.takeIf { it >= 0 }
            ?: value
                .optJSONObject("prompt_tokens_details")
                ?.optLong("cached_tokens", -1)
                ?.takeIf { it >= 0 }
        return ProviderEvent.Usage(input, output, cached)
    }

    private fun safeProviderError(root: JSONObject): String {
        val error = root.optJSONObject("error")
            ?: root.optJSONObject("response")?.optJSONObject("error")
        val message = error?.stringOrNull("message")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(320)
            .orEmpty()
        return message.ifBlank { "Provider reported an error" }
    }
}

private fun JSONObject.stringOrNull(name: String): String? =
    if (!has(name) || isNull(name)) null else optString(name)
