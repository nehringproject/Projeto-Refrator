package dev.agentworkbench

import android.content.Context
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import dev.agentworkbench.core.MessagePart
import dev.agentworkbench.core.ModelCapability
import dev.agentworkbench.core.ModelProvider
import dev.agentworkbench.core.ProviderDescriptor
import dev.agentworkbench.core.ProviderEvent
import dev.agentworkbench.core.ProviderLocality
import dev.agentworkbench.core.ProviderRequest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

class LocalGgufModelProvider(
    context: Context,
    private val model: LocalGgufInfo,
) : ModelProvider {
    private val appContext = context.applicationContext
    private val cancelled = AtomicBoolean(false)
    private val microModel = LocalGgufPromptPolicy.isMicroModel(model.displayName, model.bytes)

    override val descriptor = ProviderDescriptor(
        id = "local-gguf-${model.sha256.take(12)}",
        displayName = "Local GGUF",
        locality = ProviderLocality.ON_DEVICE,
        capabilities = if (microModel) {
            setOf(ModelCapability.TEXT)
        } else {
            setOf(ModelCapability.TEXT, ModelCapability.TOOL_CALLING, ModelCapability.REASONING_CONTROL)
        },
        healthy = true,
        costTier = 0,
        priority = 200,
    )

    override fun stream(request: ProviderRequest): Flow<ProviderEvent> = flow {
        try {
            val runtimeProfile = LocalGgufRuntimePlanner.forDevice(appContext, model)
            LocalGgufRuntime.withEngine(
                context = appContext,
                model = model,
                runtimeProfile = runtimeProfile,
                sessionId = request.sessionId,
                systemPrompt = systemPrompt(request, runtimeProfile.contextTokens),
            ) { engine ->
            cancelled.set(false)
            try {
                emit(ProviderEvent.ModelResolved(model.displayName))
                val prompt = LocalGgufPromptPolicy.prepareUserPrompt(
                    prompt = nextPrompt(request),
                    modelName = model.displayName,
                    constrained = LocalGgufPromptPolicy.isConstrained(model.bytes, runtimeProfile.contextTokens),
                )
                val generated = StringBuilder()
                val pendingPrefix = StringBuilder()
                val reasoningParser = LocalReasoningStreamParser()
                var emittingText = false
                engine.sendUserPrompt(
                    prompt,
                    LocalGgufPromptPolicy.predictionBudget(
                        prompt = prompt,
                        modelBytes = model.bytes,
                        contextTokens = runtimeProfile.contextTokens,
                    ),
                ).collect { token ->
                    if (cancelled.get()) return@collect
                    generated.append(token)
                    if (emittingText) {
                        reasoningParser.feed(token).forEach { chunk ->
                            emit(chunk.toProviderEvent())
                        }
                    } else {
                        pendingPrefix.append(token)
                        val normalized = pendingPrefix.toString().trimStart()
                        if (normalized.isNotEmpty() && !LocalGgufProtocol.shouldHoldPrefix(normalized)) {
                            emittingText = true
                            reasoningParser.feed(pendingPrefix.toString()).forEach { chunk ->
                                emit(chunk.toProviderEvent())
                            }
                            pendingPrefix.clear()
                        }
                    }
                }
                if (cancelled.get()) {
                    emit(ProviderEvent.Cancelled())
                    return@withEngine
                }
                val response = generated.toString().trim()
                val toolCall = if (emittingText) {
                    null
                } else {
                    LocalGgufProtocol.parseToolCall(response, request.tools.mapTo(hashSetOf()) { it.name })
                }
                if (toolCall != null) {
                    val callId = "local-${UUID.randomUUID()}"
                    emit(ProviderEvent.ToolCallStarted(callId, toolCall.first))
                    emit(ProviderEvent.ToolArgumentsDelta(callId, toolCall.second))
                    emit(ProviderEvent.ToolCallCompleted(callId))
                    emit(ProviderEvent.Completed("tool_calls"))
                } else {
                    if (!emittingText && pendingPrefix.isNotEmpty()) {
                        emit(ProviderEvent.TextDelta(pendingPrefix.toString()))
                    } else if (emittingText) {
                        reasoningParser.finish().forEach { chunk ->
                            emit(chunk.toProviderEvent())
                        }
                    }
                    emit(ProviderEvent.Completed("stop"))
                }
            } catch (error: OutOfMemoryError) {
                runCatching { engine.cleanUp() }
                LocalGgufRuntime.invalidate()
                emit(ProviderEvent.Failed(false, "Memoria insuficiente para este GGUF. Tente Q4 menor ou contexto reduzido."))
            } catch (error: Exception) {
                emit(ProviderEvent.Failed(false, error.message?.take(240) ?: "Falha na inferencia GGUF local."))
            }
            }
        } catch (error: OutOfMemoryError) {
            LocalGgufRuntime.invalidate()
            emit(ProviderEvent.Failed(false, "Memória insuficiente para carregar este GGUF."))
        } catch (error: Exception) {
            emit(ProviderEvent.Failed(false, error.message?.take(240) ?: "Falha ao iniciar o runtime GGUF local."))
        }
    }

    override suspend fun cancel(sessionId: String): Boolean {
        cancelled.set(true)
        return LocalGgufRuntime.cancelActive()
    }

    private fun systemPrompt(request: ProviderRequest, contextTokens: Int): String {
        val inheritedRaw = request.messages
            .filter { it.role == "system" }
            .flatMap { it.parts.filterIsInstance<MessagePart.Text>() }
            .joinToString("\n") { it.value }
        val recentUserText = recentUserText(request)
        val constrained = LocalGgufPromptPolicy.isConstrained(model.bytes, contextTokens)
        val explicitInstructions = LocalGgufPromptPolicy.taggedBlocks(inheritedRaw, "instruction")
            .joinToString("\n\n")
        val instructionBudget = (contextTokens * 2).coerceIn(2_000, MAX_INSTRUCTION_CHARS)
        require(explicitInstructions.length <= instructionBudget) {
            "As instruções explícitas ocupam ${explicitInstructions.length} caracteres e não cabem no contexto local. " +
            "Reduza o hard prompt ou escolha um provider com janela maior."
        }
        val inherited = if (constrained) {
            LocalGgufPromptPolicy.compactSystem(
                source = inheritedRaw,
                userText = recentUserText,
                includeLearnedMemory = !microModel,
            )
        } else {
            inheritedRaw
        }
        val historyBudget = if (constrained) 320 else (contextTokens / 2).coerceIn(600, 2_000)
        val history = request.messages
            .filter { it.role != "system" }
            .dropLast(1)
            .takeLast(if (constrained) 2 else MAX_HISTORY_MESSAGES)
            .joinToString("\n") { message ->
                val content = message.parts.joinToString("\n") { part ->
                    when (part) {
                        is MessagePart.Text -> part.value
                        is MessagePart.ToolResult -> "TOOL_RESULT ${part.callId}: ${part.payload}"
                        is MessagePart.ImageReference -> "[imagem local não incluída no modelo textual]"
                    }
                }
                "${message.role.uppercase()}: $content"
            }
            .takeLast(historyBudget)
        val selectedTools = selectTools(request, contextTokens, constrained)
        val tools = JSONArray().apply {
            selectedTools.forEach { tool ->
                put(JSONObject().put("name", tool.name).put("description", tool.description).put("parameters", JSONObject(tool.inputJsonSchema)))
            }
        }
        return buildString {
            append(inherited.trim())
            if (history.isNotBlank()) {
                append("\n\nHistórico recente para retomada:\n").append(history)
            }
            if (selectedTools.isNotEmpty()) {
                append("\n\nFerramentas pertinentes:\n").append(tools)
                append("\nPara chamar uma ferramenta, responda somente com ")
                append("<tool_call>{\"name\":\"nome_exato\",\"arguments\":{}}</tool_call>. ")
                append("Não invente resultados.")
            }
            if (constrained) {
                append("\n\nResponda diretamente. Não exponha nem prolongue raciocínio interno.")
            } else {
                append("\n\nSeja direto; raciocínio longo somente quando a tarefa exigir.")
            }
        }
    }

    private fun selectTools(
        request: ProviderRequest,
        contextTokens: Int,
        constrained: Boolean,
    ): List<dev.agentworkbench.core.ToolDefinition> {
        if (microModel) return emptyList()
        val haystack = recentUserText(request)
        if (!LocalGgufPromptPolicy.hasToolIntent(haystack)) return emptyList()
        val schemaBudget = if (constrained) 640 else contextTokens.coerceIn(1_200, MAX_TOOL_SCHEMA_CHARS)
        val ranked = request.tools.asSequence()
        .map { tool ->
            val terms = (tool.name + " " + tool.description.lowercase())
                .split(Regex("[^a-z0-9áàâãéêíóôõúç]+"))
                .filter { it.length >= 4 }
            val intent = intentScore(tool.name, haystack)
            val explicitName = haystack.contains(tool.name.lowercase())
            val score = terms.count(haystack::contains) + intent + if (explicitName) 10 else 0
            tool to score
        }
        .filter { (tool, score) ->
            score > 0 && (intentScore(tool.name, haystack) > 0 || haystack.contains(tool.name.lowercase()))
        }
        .sortedWith(compareByDescending<Pair<dev.agentworkbench.core.ToolDefinition, Int>> { it.second }.thenBy { it.first.name })
        .toList()
        val selected = mutableListOf<dev.agentworkbench.core.ToolDefinition>()
        var usedChars = 0
        ranked.forEach { (tool, _) ->
            val size = tool.name.length + tool.description.length + tool.inputJsonSchema.length
            val maximumTools = if (constrained) 2 else MAX_TOOLS_IN_PROMPT
            if (selected.size < maximumTools && usedChars + size <= schemaBudget) {
                selected += tool
                usedChars += size
            }
        }
        return selected
    }

    private fun intentScore(toolName: String, text: String): Int {
        val groups = listOf(
            listOf("arquivo", "pasta", "workspace", "projeto", "código", "codigo") to
                listOf("workspace_", "external_tree_", "publish_to_downloads"),
            listOf("comando", "shell", "terminal", "python", "compilar", "runtime") to
                listOf("runtime_", "process_", "python_", "shell_"),
            listOf("site", "página", "pagina", "internet", "web", "navegador", "url") to
                listOf("web_", "browser_", "http_"),
            listOf("pesquisar", "pesquisa", "buscar", "procure", "download", "baixar") to
                listOf("web_", "browser_", "http_", "download_"),
            listOf("android", "tela", "aplicativo", "app") to
                listOf("android_", "accessibility_", "shadow_"),
        )
        return if (groups.any { (words, prefixes) ->
                words.any(text::contains) && prefixes.any(toolName::startsWith)
            }) 3 else 0
    }

    private fun recentUserText(request: ProviderRequest): String = request.messages
        .filter { it.role == "user" }
        .takeLast(4)
        .flatMap { it.parts.filterIsInstance<MessagePart.Text>() }
        .joinToString(" ") { it.value.lowercase() }

    private fun nextPrompt(request: ProviderRequest): String {
        val last = request.messages.lastOrNull { it.role != "system" } ?: return "Continue."
        return when (last.role) {
            "tool" -> {
                val result = last.parts.filterIsInstance<MessagePart.ToolResult>().joinToString("\n") { it.payload }
                "TOOL_RESULT (${last.toolCallId.orEmpty()}):\n$result\nContinue a tarefa usando esse resultado."
            }
            else -> last.parts.filterIsInstance<MessagePart.Text>().joinToString("\n") { it.value }
        }.ifBlank { "Continue." }
    }

    private companion object {
        const val MAX_INSTRUCTION_CHARS = 8_000
        const val MAX_HISTORY_MESSAGES = 4
        const val MAX_TOOLS_IN_PROMPT = 4
        const val MAX_TOOL_SCHEMA_CHARS = 4_000
    }
}

internal object LocalGgufPromptPolicy {
    private const val CONSTRAINED_MODEL_BYTES = 512L * 1_048_576L
    private const val MICRO_MODEL_BYTES = 400L * 1_048_576L

    fun isConstrained(modelBytes: Long, contextTokens: Int): Boolean =
        modelBytes <= CONSTRAINED_MODEL_BYTES || contextTokens <= 2_048

    fun isMicroModel(modelName: String, modelBytes: Long): Boolean {
        val normalized = modelName.lowercase()
        val explicitMicroSize = listOf("270m", "300m", "360m", "0.3b", "0.35b", "0.4b")
            .any(normalized::contains)
        return explicitMicroSize || modelBytes <= MICRO_MODEL_BYTES
    }

    fun prepareUserPrompt(prompt: String, modelName: String, constrained: Boolean): String {
        val qwenThinkingModel = modelName.lowercase().let { "qwen3" in it || "qwen-3" in it }
        return if (constrained && qwenThinkingModel && "/no_think" !in prompt) {
            "$prompt\n/no_think"
        } else {
            prompt
        }
    }

    fun predictionBudget(prompt: String, modelBytes: Long, contextTokens: Int): Int =
        if (isConstrained(modelBytes, contextTokens)) {
            when {
                prompt.length < 200 -> 96
                prompt.length < 1_000 -> 192
                else -> 256
            }
        } else {
            when {
                prompt.length < 200 -> 256
                prompt.length < 1_000 -> 384
                else -> 512
            }
        }

    fun hasToolIntent(text: String): Boolean {
        val normalized = text.lowercase()
        return TOOL_INTENT_WORDS.any(normalized::contains) ||
            Regex("\\b[a-z][a-z0-9]+_[a-z0-9_]+\\b").containsMatchIn(normalized)
    }

    fun compactSystem(
        source: String,
        userText: String,
        includeLearnedMemory: Boolean = true,
    ): String = buildString {
        append("Você é o Refrator no Android. Responda diretamente em português e nunca invente ações.")

        val instructions = taggedBlocks(source, "instruction")
        if (instructions.isNotEmpty()) {
            append("\n\n").append(instructions.joinToString("\n\n"))
        }

        val queryTerms = keywords(userText)
        val relevantSkills = taggedBlocks(source, "skill")
            .filter { block -> queryTerms.isNotEmpty() && keywords(block).any(queryTerms::contains) }
            .take(1)
        if (relevantSkills.isNotEmpty()) {
            append("\n\n").append(relevantSkills.joinToString("\n"))
        }

        if (includeLearnedMemory) {
            val learnedMemory = taggedBlocks(source, "learned_memory").firstOrNull()
            if (learnedMemory != null) {
                append("\n\n").append(learnedMemory.take(600))
            }
        }
    }

    fun taggedBlocks(source: String, tag: String): List<String> {
        val result = mutableListOf<String>()
        val opening = "<$tag"
        val closing = "</$tag>"
        var cursor = 0
        while (cursor < source.length) {
            val start = source.indexOf(opening, cursor)
            if (start < 0) break
            val end = source.indexOf(closing, start + opening.length)
            if (end < 0) break
            val exclusiveEnd = end + closing.length
            result += source.substring(start, exclusiveEnd)
            cursor = exclusiveEnd
        }
        return result
    }

    private fun keywords(value: String): Set<String> = value.lowercase()
        .split(Regex("[^a-z0-9áàâãéêíóôõúç]+"))
        .filterTo(linkedSetOf()) { it.length >= 4 && it !in STOP_WORDS }

    private val TOOL_INTENT_WORDS = setOf(
        "arquivo", "pasta", "workspace", "projeto", "código", "codigo",
        "comando", "shell", "terminal", "python", "compilar", "runtime",
        "site", "página", "pagina", "internet", "navegador", "url",
        "pesquisar", "pesquisa", "buscar", "procure", "download", "baixar",
        "android", "tela", "aplicativo", "screenshot", "ocr", "git",
    )
    private val STOP_WORDS = setOf(
        "para", "como", "este", "esta", "isso", "aquela", "apenas", "somente",
        "responda", "diga", "fazer", "quero", "preciso", "rápido", "rapido",
    )
}

internal object LocalGgufProtocol {
    private const val TOOL_CALL_OPEN = "<tool_call>"
    private const val TOOL_CALL_CLOSE = "</tool_call>"
    private const val THINK_OPEN = "<think>"
    private const val THINK_CLOSE = "</think>"

    fun shouldHoldPrefix(normalized: String): Boolean {
        if (TOOL_CALL_OPEN.startsWith(normalized) || normalized.startsWith(TOOL_CALL_OPEN)) {
            return true
        }
        if (THINK_OPEN.startsWith(normalized)) return true
        if (!normalized.startsWith(THINK_OPEN)) return false

        val thinkEnd = normalized.indexOf(THINK_CLOSE, startIndex = THINK_OPEN.length)
        if (thinkEnd < 0) return true
        val visibleTail = normalized.substring(thinkEnd + THINK_CLOSE.length).trimStart()
        if (visibleTail.isEmpty()) return true
        return TOOL_CALL_OPEN.startsWith(visibleTail) || visibleTail.startsWith(TOOL_CALL_OPEN)
    }

    fun parseToolCall(text: String, allowedToolNames: Set<String>): Pair<String, String>? {
        val openIndex = text.indexOf(TOOL_CALL_OPEN)
        if (openIndex < 0) return null
        val payloadStart = openIndex + TOOL_CALL_OPEN.length
        val closeIndex = text.indexOf(TOOL_CALL_CLOSE, startIndex = payloadStart)
        if (closeIndex < 0) return null
        val payloadText = text.substring(payloadStart, closeIndex).trim()
        val payload = runCatching { JSONObject(payloadText) }.getOrNull() ?: return null
        val name = payload.optString("name")
        if (name !in allowedToolNames) return null
        val arguments = payload.optJSONObject("arguments") ?: JSONObject()
        return name to arguments.toString()
    }
}

internal data class LocalReasoningChunk(val reasoning: Boolean, val text: String) {
    fun toProviderEvent(): ProviderEvent = if (reasoning) {
        ProviderEvent.ReasoningDelta(text)
    } else {
        ProviderEvent.TextDelta(text)
    }
}

internal class LocalReasoningStreamParser {
    private val pending = StringBuilder()
    private var reasoning = false

    fun feed(value: String): List<LocalReasoningChunk> {
        if (value.isEmpty()) return emptyList()
        pending.append(value)
        return drain(final = false)
    }

    fun finish(): List<LocalReasoningChunk> = drain(final = true)

    private fun drain(final: Boolean): List<LocalReasoningChunk> = buildList {
        while (pending.isNotEmpty()) {
            val marker = if (reasoning) THINK_CLOSE else THINK_OPEN
            val index = pending.indexOf(marker)
            if (index >= 0) {
                if (index > 0) add(LocalReasoningChunk(reasoning, pending.substring(0, index)))
                pending.delete(0, index + marker.length)
                reasoning = !reasoning
                continue
            }
            val keep = if (final) 0 else longestMarkerPrefixSuffix(pending, marker)
            val emitLength = pending.length - keep
            if (emitLength > 0) {
                add(LocalReasoningChunk(reasoning, pending.substring(0, emitLength)))
                pending.delete(0, emitLength)
            }
            break
        }
    }

    private fun longestMarkerPrefixSuffix(value: StringBuilder, marker: String): Int {
        val maximum = minOf(value.length, marker.length - 1)
        for (length in maximum downTo 1) {
            if (value.substring(value.length - length) == marker.substring(0, length)) return length
        }
        return 0
    }

    private companion object {
        const val THINK_OPEN = "<think>"
        const val THINK_CLOSE = "</think>"
    }
}

private object LocalGgufRuntime {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: InferenceEngine? = null
    private var loadedModelSha: String? = null
    private var loadedContextTokens: Int? = null
    private var loadedSessionId: String? = null
    private var idleUnload: Job? = null

    suspend fun <T> withEngine(
        context: Context,
        model: LocalGgufInfo,
        runtimeProfile: LocalGgufRuntimeProfile,
        sessionId: String,
        systemPrompt: String,
        block: suspend (InferenceEngine) -> T,
    ): T = mutex.withLock {
        idleUnload?.cancel()
        val active = engine ?: AiChat.getInferenceEngine(context).also { engine = it }
        awaitInitialization(active)
        (active.state.value as? InferenceEngine.State.Error)?.let { failed ->
            active.destroy()
            if (engine === active) engine = null
            throw IllegalStateException("Falha ao iniciar o runtime GGUF.", failed.exception)
        }
        if (
            loadedModelSha != model.sha256 ||
            loadedContextTokens != runtimeProfile.contextTokens ||
            loadedSessionId != sessionId ||
            active.state.value !is InferenceEngine.State.ModelReady
        ) {
            if (active.state.value !is InferenceEngine.State.Initialized) {
                runCatching { active.cleanUp() }
            }
            check(active.state.value is InferenceEngine.State.Initialized) {
                "O runtime GGUF não conseguiu voltar ao estado inicial."
            }
            active.loadModel(model.path, runtimeProfile.contextTokens)
            active.setSystemPrompt(systemPrompt)
            loadedModelSha = model.sha256
            loadedContextTokens = runtimeProfile.contextTokens
            loadedSessionId = sessionId
        }
        try {
            block(active)
        } finally {
            scheduleIdleUnload(active)
        }
    }

    fun cancelActive(): Boolean {
        if (engine == null) return false
        engine?.cancelGeneration()
        return true
    }

    fun invalidate() {
        loadedModelSha = null
        loadedContextTokens = null
        loadedSessionId = null
    }

    private suspend fun awaitInitialization(active: InferenceEngine) {
        when (active.state.value) {
            is InferenceEngine.State.Uninitialized,
            is InferenceEngine.State.Initializing -> active.state
                .filter { it is InferenceEngine.State.Initialized || it is InferenceEngine.State.Error }
                .first()
            else -> Unit
        }
    }

    private fun scheduleIdleUnload(active: InferenceEngine) {
        idleUnload = scope.launch {
            delay(IDLE_UNLOAD_MILLIS)
            mutex.withLock {
                runCatching { active.cleanUp() }
                loadedModelSha = null
                loadedContextTokens = null
                loadedSessionId = null
            }
        }
    }

    private const val IDLE_UNLOAD_MILLIS = 90_000L
}
