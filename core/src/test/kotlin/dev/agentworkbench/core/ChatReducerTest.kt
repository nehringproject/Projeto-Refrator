package dev.agentworkbench.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatReducerTest {
    @Test
    fun `streamed turn accumulates text usage and completion`() {
        var state = ChatReducer.submit(
            state = ChatState(),
            turnId = "turn-1",
            userMessageId = "user-1",
            assistantMessageId = "assistant-1",
            text = "hello",
            providerId = "demo",
        )

        state = ChatReducer.applyProviderEvent(
            state,
            "turn-1",
            ProviderEvent.ModelResolved("provider-resolved-model"),
        )
        state = ChatReducer.applyProviderEvent(
            state,
            "turn-1",
            ProviderEvent.TextDelta("hello "),
        )
        state = ChatReducer.applyProviderEvent(
            state,
            "turn-1",
            ProviderEvent.TextDelta("back"),
        )
        state = ChatReducer.applyProviderEvent(
            state,
            "turn-1",
            ProviderEvent.Usage(4, 2, 1),
        )
        state = ChatReducer.applyProviderEvent(
            state,
            "turn-1",
            ProviderEvent.Completed("stop"),
        )

        assertFalse(state.isStreaming)
        assertEquals("hello back", state.messages.last().text)
        assertEquals("provider-resolved-model", state.messages.last().resolvedModelId)
        assertEquals(ChatMessageStatus.COMPLETE, state.messages.last().status)
        assertEquals(ChatUsage(4, 2, 1), state.usage)
    }

    @Test
    fun `reasoning deltas accumulate separately from the answer text`() {
        var state = ChatReducer.submit(
            state = ChatState(),
            turnId = "turn-1",
            userMessageId = "user-1",
            assistantMessageId = "assistant-1",
            text = "explain",
            providerId = "demo",
        )

        state = ChatReducer.applyProviderEvent(
            state,
            "turn-1",
            ProviderEvent.ReasoningDelta("Vou pensar "),
        )
        state = ChatReducer.applyProviderEvent(
            state,
            "turn-1",
            ProviderEvent.ReasoningDelta("passo a passo."),
        )
        state = ChatReducer.applyProviderEvent(
            state,
            "turn-1",
            ProviderEvent.TextDelta("Resposta final."),
        )

        assertEquals("Vou pensar passo a passo.", state.messages.last().reasoning)
        assertEquals("Resposta final.", state.messages.last().text)
    }

    @Test
    fun `cancelled turn remains in transcript and ignores late deltas`() {
        val streaming = ChatReducer.submit(
            state = ChatState(),
            turnId = "turn-1",
            userMessageId = "user-1",
            assistantMessageId = "assistant-1",
            text = "long task",
            providerId = "demo",
        )
        val cancelled = ChatReducer.applyProviderEvent(
            streaming,
            "turn-1",
            ProviderEvent.Cancelled(),
        )
        val afterLateDelta = ChatReducer.applyProviderEvent(
            cancelled,
            "turn-1",
            ProviderEvent.TextDelta("must be ignored"),
        )

        assertFalse(afterLateDelta.isStreaming)
        assertEquals(ChatMessageStatus.CANCELLED, afterLateDelta.messages.last().status)
        assertEquals("", afterLateDelta.messages.last().text)
    }

    @Test
    fun `new submission creates user and streaming assistant messages`() {
        val state = ChatReducer.submit(
            state = ChatState(),
            turnId = "turn-1",
            userMessageId = "user-1",
            assistantMessageId = "assistant-1",
            text = "hello",
            providerId = "openai",
        )

        assertTrue(state.isStreaming)
        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT), state.messages.map { it.role })
        assertEquals(ChatMessageStatus.STREAMING, state.messages.last().status)
    }

    @Test
    fun `tool result continuation adds only a new assistant placeholder`() {
        var state = ChatReducer.submit(
            state = ChatState(),
            turnId = "turn-1",
            userMessageId = "user-1",
            assistantMessageId = "assistant-1",
            text = "inspect device",
            providerId = "provider",
        )
        state = ChatReducer.applyProviderEvent(
            state,
            "turn-1",
            ProviderEvent.Completed("tool_calls"),
        )
        state = ChatReducer.continueAfterTool(
            state = state,
            turnId = "turn-2",
            assistantMessageId = "assistant-2",
            providerId = "provider",
            providerDisplayName = "Provider",
            requestedModelId = "model",
        )

        assertTrue(state.isStreaming)
        assertEquals(3, state.messages.size)
        assertEquals(ChatRole.ASSISTANT, state.messages.last().role)
        assertEquals(ChatMessageStatus.STREAMING, state.messages.last().status)
        assertEquals("turn-2", state.activeTurnId)
    }

    @Test
    fun `provider retry keeps the same assistant slot and changes identity`() {
        var state = ChatReducer.submit(
            state = ChatState(),
            turnId = "turn-1",
            userMessageId = "user-1",
            assistantMessageId = "assistant-1",
            text = "continue even if the provider fails",
            providerId = "provider-a",
            providerDisplayName = "Provider A",
            requestedModelId = "model-a",
        )

        state = ChatReducer.retryWithProvider(
            state = state,
            currentTurnId = "turn-1",
            nextTurnId = "turn-2",
            providerId = "provider-b",
            providerDisplayName = "Provider B",
            requestedModelId = "model-b",
        )

        assertEquals(2, state.messages.size)
        assertEquals("turn-2", state.activeTurnId)
        assertEquals("provider-b", state.activeProviderId)
        assertEquals("Provider B", state.messages.last().providerDisplayName)
        assertEquals("model-b", state.messages.last().requestedModelId)
        assertTrue(state.isStreaming)
    }
}
