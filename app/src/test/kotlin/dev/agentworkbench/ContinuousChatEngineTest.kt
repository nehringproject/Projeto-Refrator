package dev.agentworkbench

import dev.agentworkbench.core.MessagePart
import dev.agentworkbench.core.ModelCapability
import dev.agentworkbench.core.ModelProvider
import dev.agentworkbench.core.ProviderDescriptor
import dev.agentworkbench.core.ProviderEvent
import dev.agentworkbench.core.ProviderLocality
import dev.agentworkbench.core.ProviderMessage
import dev.agentworkbench.core.ProviderRequest
import dev.agentworkbench.core.ProviderToolCall
import dev.agentworkbench.core.ToolDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking

class ContinuousChatEngineTest {
    @Test
    fun `token estimate includes tool schemas and payloads`() {
        val messages = listOf(
            ProviderMessage(
                role = "tool",
                parts = listOf(MessagePart.ToolResult("call", "x".repeat(3_000), false)),
            ),
        )
        val tools = listOf(ToolDefinition("shell", "execute", "y".repeat(3_000)))

        assertTrue(ContinuousChatEngine.estimateTokens(messages, tools) >= 2_000)
    }

    @Test
    fun `compaction preserves recent messages and installs model memory`() = runBlocking {
        val ledger = (1..12).map { index ->
            ProviderMessage(
                role = if (index % 2 == 0) "assistant" else "user",
                parts = listOf(MessagePart.Text("message-$index " + "x".repeat(900))),
            )
        }
        val environment = ProviderMessage("system", listOf(MessagePart.Text("environment")))

        val result = ContinuousChatEngine.compactIfNeeded(
            provider = SummaryProvider(),
            modelId = "summary-model",
            sessionId = "session",
            environment = environment,
            ledger = ledger,
            tools = emptyList(),
            settings = ContinuousChatSettings(
                enabled = true,
                contextWindowTokens = 4_096,
                compactionThresholdPercent = 50,
                recentMessagesToKeep = 4,
            ),
        )

        assertNotNull(result)
        assertEquals(5, result.ledger.size)
        assertTrue(result.usedModelSummary)
        assertTrue(
            result.ledger.first().parts.filterIsInstance<MessagePart.Text>()
                .single().value.contains("decisions and pending work"),
        )
        assertEquals(ledger.takeLast(4), result.ledger.takeLast(4))
        assertTrue(result.estimatedTokensAfter < result.estimatedTokensBefore)
    }

    @Test
    fun `provider context is bounded without mutating durable history`() {
        val hugePayload = "app-entry\n".repeat(2_000)
        val ledger = buildList {
            repeat(20) { index ->
                add(ProviderMessage("user", listOf(MessagePart.Text("request-$index"))))
                add(
                    ProviderMessage(
                        role = "tool",
                        parts = listOf(MessagePart.ToolResult("call-$index", hugePayload, false)),
                        toolCallId = "call-$index",
                    ),
                )
            }
        }

        val prepared = ContinuousChatEngine.prepareForProvider(
            ledger,
            ContinuousChatSettings(enabled = false, recentMessagesToKeep = 8),
        )

        assertTrue(prepared.size <= 8)
        assertTrue(prepared.first().role != "tool")
        assertTrue(
            prepared.flatMap { it.parts }.filterIsInstance<MessagePart.ToolResult>()
                .all { it.payload.length <= 4_000 },
        )
        assertEquals(hugePayload, (ledger.last().parts.single() as MessagePart.ToolResult).payload)
    }

    @Test
    fun `malformed tool history is quarantined from the next provider request`() {
        val ledger = listOf(
            ProviderMessage("user", listOf(MessagePart.Text("pesquise o jogo inteiro"))),
            ProviderMessage(
                role = "assistant",
                parts = listOf(MessagePart.Text("fragmento quebrado")),
                toolCalls = listOf(ProviderToolCall("bad", "web_open", "{\"url\": \"https")),
            ),
            ProviderMessage(
                role = "tool",
                parts = listOf(MessagePart.ToolResult("bad", "JSON inválido", true)),
                toolCallId = "bad",
            ),
            ProviderMessage("assistant", listOf(MessagePart.Text("ixo creta"))),
            ProviderMessage("user", listOf(MessagePart.Text("continua"))),
        )

        val prepared = ContinuousChatEngine.prepareForProvider(
            ledger,
            ContinuousChatSettings(enabled = false),
        )

        assertEquals(listOf("user", "user"), prepared.map { it.role })
        assertEquals("pesquise o jogo inteiro", (prepared.first().parts.single() as MessagePart.Text).value)
        assertEquals("continua", (prepared.last().parts.single() as MessagePart.Text).value)
    }

    private class SummaryProvider : ModelProvider {
        override val descriptor = ProviderDescriptor(
            id = "summary",
            displayName = "Summary",
            locality = ProviderLocality.ON_DEVICE,
            capabilities = setOf(ModelCapability.TEXT),
            healthy = true,
            costTier = 0,
            priority = 1,
        )

        override fun stream(request: ProviderRequest): Flow<ProviderEvent> = flow {
            emit(ProviderEvent.TextDelta("decisions and pending work"))
            emit(ProviderEvent.Completed("stop"))
        }

        override suspend fun cancel(sessionId: String): Boolean = false
    }
}
