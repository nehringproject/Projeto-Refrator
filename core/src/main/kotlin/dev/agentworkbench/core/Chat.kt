package dev.agentworkbench.core

enum class ChatRole {
    USER,
    ASSISTANT,
}

enum class ChatMessageStatus {
    COMPLETE,
    STREAMING,
    FAILED,
    CANCELLED,
}

data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val text: String,
    val status: ChatMessageStatus,
    val providerId: String? = null,
    val providerDisplayName: String? = null,
    val requestedModelId: String? = null,
    val resolvedModelId: String? = null,
    val error: String? = null,
    /** Raciocínio/"thinking" transmitido separadamente do texto final da resposta. */
    val reasoning: String = "",
)

data class ChatUsage(
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long?,
)

data class ChatState(
    val messages: List<ChatMessage> = emptyList(),
    val activeTurnId: String? = null,
    val activeProviderId: String? = null,
    val usage: ChatUsage? = null,
    val continuation: Map<String, String> = emptyMap(),
) {
    val isStreaming: Boolean
        get() = activeTurnId != null
}

object ChatReducer {
    fun submit(
        state: ChatState,
        turnId: String,
        userMessageId: String,
        assistantMessageId: String,
        text: String,
        providerId: String,
        providerDisplayName: String? = null,
        requestedModelId: String? = null,
    ): ChatState {
        require(!state.isStreaming) { "A chat turn is already streaming" }
        require(turnId.isNotBlank()) { "Turn id is required" }
        require(text.isNotBlank()) { "Message text is required" }
        require(providerId.isNotBlank()) { "Provider id is required" }

        return state.copy(
            messages = state.messages + listOf(
                ChatMessage(
                    id = userMessageId,
                    role = ChatRole.USER,
                    text = text,
                    status = ChatMessageStatus.COMPLETE,
                ),
                ChatMessage(
                    id = assistantMessageId,
                    role = ChatRole.ASSISTANT,
                    text = "",
                    status = ChatMessageStatus.STREAMING,
                    providerId = providerId,
                    providerDisplayName = providerDisplayName,
                    requestedModelId = requestedModelId,
                ),
            ),
            activeTurnId = turnId,
            activeProviderId = providerId,
            usage = null,
            continuation = emptyMap(),
        )
    }

    fun applyProviderEvent(
        state: ChatState,
        turnId: String,
        event: ProviderEvent,
    ): ChatState {
        if (state.activeTurnId != turnId) return state

        return when (event) {
            is ProviderEvent.ModelResolved ->
                state.updateAssistant { message ->
                    message.copy(resolvedModelId = event.modelId)
                }

            is ProviderEvent.TextDelta ->
                state.updateAssistant { message ->
                    message.copy(text = message.text + event.value)
                }

            is ProviderEvent.ReasoningDelta ->
                state.updateAssistant { message ->
                    message.copy(reasoning = message.reasoning + event.value)
                }

            is ProviderEvent.Usage ->
                state.copy(
                    usage = ChatUsage(
                        inputTokens = event.inputTokens,
                        outputTokens = event.outputTokens,
                        cachedInputTokens = event.cachedInputTokens,
                    ),
                )

            is ProviderEvent.Continuation ->
                state.copy(continuation = state.continuation + event.values)

            is ProviderEvent.Completed ->
                state.finish(ChatMessageStatus.COMPLETE)

            is ProviderEvent.Cancelled ->
                state
                    .updateAssistant { message ->
                        message.copy(error = event.reason)
                    }
                    .finish(ChatMessageStatus.CANCELLED)

            is ProviderEvent.Failed ->
                state
                    .updateAssistant { message ->
                        message.copy(error = event.safeMessage)
                    }
                    .finish(ChatMessageStatus.FAILED)

            is ProviderEvent.ToolCallStarted,
            is ProviderEvent.ToolArgumentsDelta,
            is ProviderEvent.ToolCallCompleted,
            -> state
        }
    }

    fun clear(state: ChatState): ChatState {
        require(!state.isStreaming) { "Cannot clear chat while a turn is streaming" }
        return ChatState()
    }

    fun continueAfterTool(
        state: ChatState,
        turnId: String,
        assistantMessageId: String,
        providerId: String,
        providerDisplayName: String? = null,
        requestedModelId: String? = null,
    ): ChatState {
        require(!state.isStreaming) { "A chat turn is already streaming" }
        require(turnId.isNotBlank()) { "Turn id is required" }
        require(providerId.isNotBlank()) { "Provider id is required" }
        return state.copy(
            messages = state.messages + ChatMessage(
                id = assistantMessageId,
                role = ChatRole.ASSISTANT,
                text = "",
                status = ChatMessageStatus.STREAMING,
                providerId = providerId,
                providerDisplayName = providerDisplayName,
                requestedModelId = requestedModelId,
            ),
            activeTurnId = turnId,
            activeProviderId = providerId,
            usage = null,
        )
    }

    fun retryWithProvider(
        state: ChatState,
        currentTurnId: String,
        nextTurnId: String,
        providerId: String,
        providerDisplayName: String? = null,
        requestedModelId: String? = null,
    ): ChatState {
        require(state.activeTurnId == currentTurnId) { "Turn is no longer active" }
        require(nextTurnId.isNotBlank()) { "Turn id is required" }
        require(providerId.isNotBlank()) { "Provider id is required" }
        return state.updateAssistant { message ->
            message.copy(
                providerId = providerId,
                providerDisplayName = providerDisplayName,
                requestedModelId = requestedModelId,
                resolvedModelId = null,
                error = null,
                reasoning = "",
            )
        }.copy(
            activeTurnId = nextTurnId,
            activeProviderId = providerId,
            usage = null,
            continuation = emptyMap(),
        )
    }

    private fun ChatState.updateAssistant(
        transform: (ChatMessage) -> ChatMessage,
    ): ChatState {
        val index = messages.indexOfLast {
            it.role == ChatRole.ASSISTANT && it.status == ChatMessageStatus.STREAMING
        }
        if (index < 0) return this
        val updated = messages.toMutableList()
        updated[index] = transform(updated[index])
        return copy(messages = updated)
    }

    private fun ChatState.finish(status: ChatMessageStatus): ChatState =
        updateAssistant { message ->
            message.copy(status = status)
        }.copy(
            activeTurnId = null,
            activeProviderId = null,
        )
}
