package dev.agentworkbench.provider.http

import dev.agentworkbench.core.MessagePart
import dev.agentworkbench.core.ModelCapability
import dev.agentworkbench.core.ModelProvider
import dev.agentworkbench.core.ProviderDescriptor
import dev.agentworkbench.core.ProviderEvent
import dev.agentworkbench.core.ProviderLocality
import dev.agentworkbench.core.ProviderRequest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class DemoModelProvider : ModelProvider {
    private val active = ConcurrentHashMap<String, AtomicBoolean>()

    override val descriptor = ProviderDescriptor(
        id = "demo-local",
        displayName = "Demo local",
        locality = ProviderLocality.ON_DEVICE,
        capabilities = setOf(ModelCapability.TEXT),
        healthy = true,
        costTier = 0,
        priority = 100,
    )

    override fun stream(request: ProviderRequest): Flow<ProviderEvent> = flow {
        val cancelled = AtomicBoolean(false)
        if (active.putIfAbsent(request.sessionId, cancelled) != null) {
            emit(ProviderEvent.Failed(false, "This demo session is already running"))
            return@flow
        }
        try {
            emit(ProviderEvent.ModelResolved(request.modelId))
            val userText = request.messages
                .lastOrNull { it.role == "user" }
                ?.parts
                ?.filterIsInstance<MessagePart.Text>()
                ?.joinToString("\n") { it.value }
                .orEmpty()
            val response = buildString {
                append("Demo local ativo. O streaming funciona sem enviar dados para a rede. ")
                if (userText.isNotBlank()) {
                    append("Recebi: “")
                    append(userText.take(160))
                    append("”.")
                }
            }
            response.chunked(12).forEach { chunk ->
                delay(300)
                if (cancelled.get()) {
                    emit(ProviderEvent.Cancelled())
                    return@flow
                }
                emit(ProviderEvent.TextDelta(chunk))
            }
            emit(
                ProviderEvent.Usage(
                    inputTokens = roughTokenCount(userText),
                    outputTokens = roughTokenCount(response),
                    cachedInputTokens = null,
                ),
            )
            emit(ProviderEvent.Completed("demo_complete"))
        } finally {
            active.remove(request.sessionId, cancelled)
        }
    }

    override suspend fun cancel(sessionId: String): Boolean =
        active[sessionId]?.let {
            it.set(true)
            true
        } ?: false

    private fun roughTokenCount(text: String): Long =
        ((text.length + 3) / 4).toLong()
}
