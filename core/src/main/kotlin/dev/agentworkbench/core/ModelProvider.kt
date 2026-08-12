package dev.agentworkbench.core

import kotlinx.coroutines.flow.Flow

data class ProviderMessage(
    val role: String,
    val parts: List<MessagePart>,
    val toolCalls: List<ProviderToolCall> = emptyList(),
    val toolCallId: String? = null,
)

data class ProviderToolCall(
    val callId: String,
    val toolName: String,
    val argumentsJson: String,
)

sealed interface MessagePart {
    data class Text(val value: String) : MessagePart
    data class ImageReference(val uri: String, val mimeType: String) : MessagePart
    data class ToolResult(
        val callId: String,
        val payload: String,
        val isError: Boolean,
    ) : MessagePart
}

data class ProviderRequest(
    val sessionId: String,
    val modelId: String,
    val messages: List<ProviderMessage>,
    val tools: List<ToolDefinition>,
    val providerContinuation: Map<String, String> = emptyMap(),
)

data class ToolDefinition(
    val name: String,
    val description: String,
    val inputJsonSchema: String,
)

sealed interface ProviderEvent {
    data class ModelResolved(val modelId: String) : ProviderEvent
    data class ReasoningDelta(val value: String) : ProviderEvent
    data class TextDelta(val value: String) : ProviderEvent
    data class ToolCallStarted(val callId: String, val toolName: String) : ProviderEvent
    data class ToolArgumentsDelta(val callId: String, val jsonDelta: String) : ProviderEvent
    data class ToolCallCompleted(val callId: String) : ProviderEvent
    data class Usage(
        val inputTokens: Long,
        val outputTokens: Long,
        val cachedInputTokens: Long?,
    ) : ProviderEvent

    /**
     * Opaque provider state is persisted but never interpreted by another
     * adapter. This avoids lossy conversion of response IDs and cache handles.
     */
    data class Continuation(val values: Map<String, String>) : ProviderEvent
    data class Completed(val finishReason: String) : ProviderEvent
    data class Cancelled(val reason: String = "Cancelled by user") : ProviderEvent
    data class Failed(val retryable: Boolean, val safeMessage: String) : ProviderEvent
}

interface ModelProvider {
    val descriptor: ProviderDescriptor

    fun stream(request: ProviderRequest): Flow<ProviderEvent>

    suspend fun cancel(sessionId: String): Boolean
}
