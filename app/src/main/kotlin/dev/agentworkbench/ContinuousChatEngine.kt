package dev.agentworkbench

import dev.agentworkbench.core.MessagePart
import dev.agentworkbench.core.ModelProvider
import dev.agentworkbench.core.ProviderEvent
import dev.agentworkbench.core.ProviderMessage
import dev.agentworkbench.core.ProviderRequest
import dev.agentworkbench.core.ToolDefinition
import java.util.UUID
import kotlinx.coroutines.flow.collect
import org.json.JSONObject

data class ContextCompactionResult(
    val ledger: List<ProviderMessage>,
    val estimatedTokensBefore: Int,
    val estimatedTokensAfter: Int,
    val usedModelSummary: Boolean,
)

object ContinuousChatEngine {
    private const val MEMORY_OPEN = "<continuous_chat_memory>"
    private const val MEMORY_CLOSE = "</continuous_chat_memory>"

    /**
     * Keeps the durable local ledger intact while producing a small, valid provider payload.
     * This applies even when automatic compaction is disabled: providers should never receive an
     * unbounded transcript or multi-megabyte tool output just because the user wants local history.
     */
    fun prepareForProvider(
        ledger: List<ProviderMessage>,
        settings: ContinuousChatSettings,
    ): List<ProviderMessage> {
        if (ledger.isEmpty()) return emptyList()
        val containsMalformedToolHistory = ledger.any { message ->
            message.toolCalls.any { call ->
                !isCompleteJsonObject(call.argumentsJson)
            }
        }
        val qualityFilteredLedger = if (containsMalformedToolHistory) {
            ledger.filter { message ->
                message.role == "user" ||
                    (
                        message.role == "system" && message.parts.any { part ->
                            part is MessagePart.Text && part.value.contains(MEMORY_OPEN)
                        }
                    )
            }
        } else {
            ledger
        }
        val keep = settings.recentMessagesToKeep.coerceIn(MIN_PROVIDER_MESSAGES, MAX_PROVIDER_MESSAGES)
        val compactedMemory = qualityFilteredLedger.firstOrNull { message ->
            message.role == "system" && message.parts.any { part ->
                part is MessagePart.Text && part.value.contains(MEMORY_OPEN)
            }
        }
        val recent = qualityFilteredLedger.takeLast(keep).toMutableList()
        while (recent.firstOrNull()?.containsToolResult() == true) recent.removeAt(0)

        val prepared = buildList {
            compactedMemory?.takeIf { it !in recent }?.let(::add)
            addAll(recent)
        }.map(::sanitizeForProvider)

        val bounded = prepared.toMutableList()
        val maxCharacters = (settings.contextWindowTokens.toLong() * PROVIDER_HISTORY_CHARS_PER_TOKEN)
            .coerceIn(MIN_PROVIDER_HISTORY_CHARS, MAX_PROVIDER_HISTORY_CHARS)
        while (bounded.size > 1 && estimatedMessageCharacters(bounded) > maxCharacters) {
            val removable = bounded.indexOfFirst { it !== compactedMemory && it.role != "system" }
            if (removable < 0) break
            bounded.removeAt(removable)
            while (bounded.firstOrNull()?.containsToolResult() == true) bounded.removeAt(0)
        }
        return bounded
    }

    private fun isCompleteJsonObject(raw: String): Boolean {
        val value = raw.trim().ifBlank { "{}" }
        return value.startsWith('{') && value.endsWith('}') &&
            runCatching { JSONObject(value) }.isSuccess
    }

    private fun sanitizeForProvider(message: ProviderMessage): ProviderMessage = message.copy(
        parts = message.parts.map { part ->
            when (part) {
                is MessagePart.Text -> part.copy(value = boundedText(part.value, MAX_TEXT_PART_CHARS))
                is MessagePart.ToolResult -> part.copy(
                    payload = boundedText(part.payload, MAX_TOOL_RESULT_CHARS),
                )
                is MessagePart.ImageReference -> part
            }
        },
        toolCalls = message.toolCalls.map { call ->
            if (call.argumentsJson.length <= MAX_TOOL_ARGUMENT_CONTEXT_CHARS) call
            else call.copy(
                argumentsJson = "{\"_context\":\"argumentos antigos omitidos; consulte o resultado associado\"}",
            )
        },
    )

    private fun boundedText(value: String, limit: Int): String {
        if (value.length <= limit) return value
        val marker = "\n\n[conteúdo antigo truncado localmente antes do envio ao provider]\n\n"
        val available = (limit - marker.length).coerceAtLeast(0)
        val head = available * 2 / 3
        return value.take(head) + marker + value.takeLast(available - head)
    }

    private fun estimatedMessageCharacters(messages: List<ProviderMessage>): Long =
        messages.sumOf { message ->
            message.parts.sumOf { part ->
                when (part) {
                    is MessagePart.Text -> part.value.length.toLong()
                    is MessagePart.ToolResult -> part.payload.length.toLong()
                    is MessagePart.ImageReference -> 1_024L
                }
            } + message.toolCalls.sumOf { it.argumentsJson.length.toLong() } + 64L
        }

    fun estimateTokens(
        messages: List<ProviderMessage>,
        tools: List<ToolDefinition> = emptyList(),
    ): Int {
        var characters = 0L
        messages.forEach { message ->
            characters += message.role.length + 24L
            message.parts.forEach { part ->
                characters += when (part) {
                    is MessagePart.Text -> part.value.length.toLong()
                    is MessagePart.ImageReference -> part.uri.length + part.mimeType.length + 1_024L
                    is MessagePart.ToolResult ->
                        part.callId.length + part.payload.length + 48L
                }
            }
            message.toolCalls.forEach { call ->
                characters += call.callId.length + call.toolName.length +
                    call.argumentsJson.length + 64L
            }
        }
        tools.forEach { tool ->
            characters += tool.name.length + tool.description.length +
                tool.inputJsonSchema.length + 48L
        }
        // Three characters per token is deliberately conservative for mixed
        // Portuguese, source code and JSON tool payloads.
        return ((characters + 2L) / 3L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    fun shouldCompact(
        messages: List<ProviderMessage>,
        tools: List<ToolDefinition>,
        settings: ContinuousChatSettings,
    ): Boolean {
        if (!settings.enabled) return false
        val threshold = settings.contextWindowTokens.toLong() *
            settings.compactionThresholdPercent / 100L
        return estimateTokens(messages, tools) >= threshold
    }

    suspend fun compactIfNeeded(
        provider: ModelProvider,
        modelId: String,
        sessionId: String,
        environment: ProviderMessage,
        ledger: List<ProviderMessage>,
        tools: List<ToolDefinition>,
        settings: ContinuousChatSettings,
    ): ContextCompactionResult? {
        val requestMessages = listOf(environment) + ledger
        val before = estimateTokens(requestMessages, tools)
        if (!shouldCompact(requestMessages, tools, settings)) return null

        val cut = safePrefixEnd(ledger, settings.recentMessagesToKeep)
        if (cut < MIN_MESSAGES_TO_COMPACT) return null
        val oldContext = ledger.take(cut)
        val recentContext = ledger.drop(cut)
        val transcript = transcript(oldContext)
        val modelSummary = summarizeWithModel(
            provider = provider,
            modelId = modelId,
            sessionId = sessionId,
            transcript = transcript,
            maxSummaryChars = summaryCharacterLimit(settings.contextWindowTokens),
        )
        val summary = modelSummary ?: deterministicSummary(
            messages = oldContext,
            maxChars = summaryCharacterLimit(settings.contextWindowTokens),
        )
        val compacted = listOf(memoryMessage(summary)) + recentContext
        return ContextCompactionResult(
            ledger = compacted,
            estimatedTokensBefore = before,
            estimatedTokensAfter = estimateTokens(listOf(environment) + compacted, tools),
            usedModelSummary = modelSummary != null,
        )
    }

    private suspend fun summarizeWithModel(
        provider: ModelProvider,
        modelId: String,
        sessionId: String,
        transcript: String,
        maxSummaryChars: Int,
    ): String? {
        val output = StringBuilder()
        var completed = false
        return runCatching {
            provider.stream(
                ProviderRequest(
                    sessionId = "$sessionId-compact-${UUID.randomUUID()}",
                    modelId = modelId,
                    messages = listOf(
                        ProviderMessage(
                            role = "system",
                            parts = listOf(
                                MessagePart.Text(
                                    """
                                    Compacte a conversa abaixo em uma memoria operacional fiel.
                                    Preserve objetivos, requisitos, decisoes, preferencias do usuario,
                                    estado atual, arquivos/caminhos, comandos importantes, resultados de
                                    ferramentas, erros, restricoes, itens pendentes e proximos passos.
                                    Remova repeticao e conversa social. Nao invente fatos. Produza texto
                                    estruturado e autocontido, sem markdown ornamental, com no maximo
                                    $maxSummaryChars caracteres. Essa memoria substituirá as mensagens antigas.
                                    """.trimIndent(),
                                ),
                            ),
                        ),
                        ProviderMessage(
                            role = "user",
                            parts = listOf(MessagePart.Text(transcript)),
                        ),
                    ),
                    tools = emptyList(),
                ),
            ).collect { event ->
                when (event) {
                    is ProviderEvent.TextDelta -> {
                        val remaining = maxSummaryChars - output.length
                        if (remaining > 0) output.append(event.value.take(remaining))
                    }
                    is ProviderEvent.Completed -> completed = true
                    is ProviderEvent.Cancelled,
                    is ProviderEvent.Failed,
                    -> completed = false
                    else -> Unit
                }
            }
            output.toString().trim().takeIf { completed && it.isNotBlank() }
        }.getOrNull()
    }

    private fun safePrefixEnd(messages: List<ProviderMessage>, keepRecent: Int): Int {
        var cut = (messages.size - keepRecent).coerceAtLeast(0)
        while (cut > 0 && messages.getOrNull(cut)?.containsToolResult() == true) {
            cut -= 1
        }
        return cut
    }

    private fun ProviderMessage.containsToolResult(): Boolean =
        role == "tool" || parts.any { it is MessagePart.ToolResult }

    private fun transcript(messages: List<ProviderMessage>): String = buildString {
        messages.forEachIndexed { index, message ->
            append("\n--- mensagem ")
            append(index + 1)
            append(" · ")
            append(message.role)
            append(" ---\n")
            message.parts.forEach { part ->
                when (part) {
                    is MessagePart.Text -> append(part.value)
                    is MessagePart.ImageReference -> append("[imagem ${part.mimeType}: ${part.uri}]")
                    is MessagePart.ToolResult -> {
                        append("[resultado ferramenta ")
                        append(part.callId)
                        append(if (part.isError) " · erro]\n" else "]\n")
                        append(part.payload)
                    }
                }
                append('\n')
            }
            message.toolCalls.forEach { call ->
                append("[chamada ")
                append(call.toolName)
                append(" · ")
                append(call.callId)
                append("] ")
                append(call.argumentsJson)
                append('\n')
            }
        }
    }

    private fun deterministicSummary(
        messages: List<ProviderMessage>,
        maxChars: Int,
    ): String {
        val rendered = transcript(messages)
        if (rendered.length <= maxChars) return rendered.trim()
        val headSize = (maxChars * 0.38).toInt()
        val tailSize = maxChars - headSize - FALLBACK_GAP.length
        return buildString(maxChars) {
            append(rendered.take(headSize))
            append(FALLBACK_GAP)
            append(rendered.takeLast(tailSize.coerceAtLeast(0)))
        }.trim()
    }

    private fun memoryMessage(summary: String): ProviderMessage = ProviderMessage(
        role = "system",
        parts = listOf(
            MessagePart.Text(
                "$MEMORY_OPEN\nMemoria compactada de mensagens anteriores:\n$summary\n$MEMORY_CLOSE",
            ),
        ),
    )

    private fun summaryCharacterLimit(contextWindowTokens: Int): Int =
        (contextWindowTokens / 3)
            .coerceIn(MIN_SUMMARY_CHARS, MAX_SUMMARY_CHARS)

    private const val MIN_MESSAGES_TO_COMPACT = 4
    private const val MIN_PROVIDER_MESSAGES = 6
    private const val MAX_PROVIDER_MESSAGES = 24
    private const val MAX_TEXT_PART_CHARS = 12_000
    private const val MAX_TOOL_RESULT_CHARS = 4_000
    private const val MAX_TOOL_ARGUMENT_CONTEXT_CHARS = 8_000
    private const val PROVIDER_HISTORY_CHARS_PER_TOKEN = 2L
    private const val MIN_PROVIDER_HISTORY_CHARS = 16_000L
    private const val MAX_PROVIDER_HISTORY_CHARS = 64_000L
    private const val MIN_SUMMARY_CHARS = 2_000
    private const val MAX_SUMMARY_CHARS = 24_000
    private const val FALLBACK_GAP = "\n\n[trecho intermediario removido na compactacao local]\n\n"
}
