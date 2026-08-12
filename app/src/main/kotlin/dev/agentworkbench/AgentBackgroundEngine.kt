package dev.agentworkbench

import android.content.Context
import dev.agentworkbench.core.ChatMessageStatus
import dev.agentworkbench.core.ChatReducer
import dev.agentworkbench.core.ChatRole
import dev.agentworkbench.core.ChatState
import dev.agentworkbench.core.MessagePart
import dev.agentworkbench.core.ProviderEvent
import dev.agentworkbench.core.ProviderMessage
import dev.agentworkbench.core.ProviderRequest
import dev.agentworkbench.core.ProviderToolCall
import dev.agentworkbench.core.ToolEffect
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Durable, Activity-independent agent loop used by [AgentExecutionService].
 *
 * UI-only tools aren't advertised here. Every model/tool boundary is checkpointed in Room, and
 * the visible transcript is atomically persisted after every provider event.
 */
class AgentBackgroundEngine(
    context: Context,
    private val executions: ExecutionRepository,
) {
    private val appContext = context.applicationContext
    private val sessions = ChatSessionRepository(appContext)
    private val providers = ProviderSettingsRepository(appContext)
    private val workspace = File(appContext.filesDir, "workspace").apply { mkdirs() }
    private val contextMemory = ContextMemoryRepository(appContext)
    private val agentPlatform = AgentPlatformRepository(appContext)
    private val hookEngine = HookEngine(agentPlatform)
    private val workspaceId = ContextMemoryRepository.workspaceId(workspace.absolutePath)

    suspend fun execute(run: AgentRunEntity) {
        val reconciliation = executions.stepNeedingReconciliation(run.id)
        if (reconciliation != null) {
            executions.checkpointRun(
                run.id,
                AgentRunState.WAITING_INPUT,
                "A etapa mutável ${reconciliation.kind} foi interrompida e exige reconciliação do estado real.",
            )
            return
        }

        val preset = runCatching { ProviderPreset.valueOf(run.providerPreset) }.getOrNull()
            ?: return executions.checkpointRun(
                run.id,
                AgentRunState.FAILED,
                "Provider persistido não existe nesta versão.",
            )
        val settings = executions.settingsFor(run)
            ?: providers.defaults(preset).copy(modelId = run.modelId)
        val allowLocalCleartext = TransportSecurityPreferences(appContext).allowLocalCleartext()
        val routes = providers.buildProviderRoutes(settings, allowLocalCleartext)
        if (routes.isEmpty()) {
            executions.checkpointRun(run.id, AgentRunState.FAILED, "Nenhum provider configurado está disponível.")
            return
        }
        val initial = sessions.load(run.sessionId)
            ?: return executions.checkpointRun(run.id, AgentRunState.FAILED, "Conversa persistida não foi encontrada.")
        val initialTurnId = run.goalId ?: UUID.randomUUID().toString()
        val transcriptStreaming = initial.messages.any {
            it.role == ChatRole.ASSISTANT && it.status == ChatMessageStatus.STREAMING
        }
        var state = ChatState(
            messages = initial.messages,
            activeTurnId = initialTurnId.takeIf { transcriptStreaming },
            activeProviderId = routes.first().provider.descriptor.id.takeIf { transcriptStreaming },
        )
        var ledger = initial.providerLedger
        var activities = initial.toolActivities
        val toolbox = AgentToolbox(
            appContext = appContext,
            profile = DistributionBindings.profile(),
            executionMode = settings.executionMode,
            workspaceRoot = workspace,
            activity = null,
            conversationId = run.sessionId,
        )
        var routeIndex = 0
        var route = routes[routeIndex]
        var turnId = initialTurnId
        var ordinal = executions.steps(run.id).maxOfOrNull(AgentStepEntity::ordinal)?.plus(1) ?: 0
        var toolRounds = 0
        var noProgressCycles = run.noProgressCycles
        var lastStreamPersistAt = 0L
        var previousRoundSignature: String? = null
        var repeatedRoundCount = 0

        suspend fun persistStreamingCheckpoint(force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (force || now - lastStreamPersistAt >= STREAM_CHECKPOINT_INTERVAL_MS) {
                persist(run, settings, state, ledger, activities)
                lastStreamPersistAt = now
            }
        }

        if (run.pendingInteractionJson != null && run.resumePayloadJson != null) {
            val pending = JSONObject(run.pendingInteractionJson)
            val response = JSONObject(run.resumePayloadJson)
            val invocation = AgentToolInvocation(
                callId = pending.getString("call_id"),
                toolName = pending.getString("tool_name"),
                argumentsJson = pending.optString("arguments", "{}"),
            )
            val resumedResult = if (pending.getString("type") == "question") {
                AgentToolResult(
                    callId = invocation.callId,
                    toolName = invocation.toolName,
                    payload = JSONObject()
                        .put("question", pending.optString("question"))
                        .put("answer", response.opt("answer") ?: JSONObject.NULL)
                        .put("declined", response.optBoolean("declined", false))
                        .toString(),
                    isError = response.optBoolean("declined", false),
                )
            } else {
                val approved = response.optBoolean("approved", false)
                when (val preparation = toolbox.prepare(invocation)) {
                    is AgentToolPreparation.Rejected -> preparation.result
                    is AgentToolPreparation.Ready -> {
                        if (!approved) {
                            AgentToolResult(
                                invocation.callId,
                                invocation.toolName,
                                "Ação recusada pelo usuário.",
                                true,
                            )
                        } else {
                            val effect = when (preparation.prepared.request.effect) {
                                ToolEffect.READ_ONLY -> AgentStepEffect.READ_ONLY
                                ToolEffect.WORKSPACE_MUTATION,
                                ToolEffect.EXTERNAL_MUTATION,
                                ToolEffect.DESTRUCTIVE,
                                -> AgentStepEffect.MUTATION
                            }
                            val step = executions.beginStep(
                                run.id,
                                ordinal++,
                                "tool:${invocation.toolName}:approved",
                                effect,
                                invocation.argumentsJson,
                            )
                            val result = toolbox.execute(preparation.prepared, approved = true)
                            executions.finishStep(
                                step.id,
                                succeeded = !result.isError,
                                resultRef = sha256(result.payload),
                                error = result.payload.takeIf { result.isError },
                            )
                            result
                        }
                    }
                }
            }
            ledger = ledger + resumedResult.providerMessage()
            activities = activities.map { activity ->
                if (activity.callId == invocation.callId) {
                    activity.copy(
                        status = when {
                            response.optBoolean("declined", false) ||
                                !response.optBoolean("approved", true) -> ToolActivityStatus.DENIED
                            resumedResult.isError -> ToolActivityStatus.FAILED
                            else -> ToolActivityStatus.COMPLETE
                        },
                        resultPreview = resumedResult.payload.take(600),
                    )
                } else activity
            }
            executions.clearInteraction(run.id)
            turnId = UUID.randomUUID().toString()
            state = ChatReducer.continueAfterTool(
                state,
                turnId,
                UUID.randomUUID().toString(),
                route.provider.descriptor.id,
                route.provider.descriptor.displayName,
                route.settings.modelId,
            )
            persist(run, settings, state, ledger, activities)
        }

        executions.checkpointRun(run.id, AgentRunState.RUNNING)
        try {
            while (toolRounds <= MAX_TOOL_ROUNDS) {
                publishStatus(run, "Consultando modelo", route.settings.modelId)
                val beforeModel = hookEngine.evaluate(
                    HookEvent.BEFORE_MODEL,
                    JSONObject()
                        .put("run_id", run.id)
                        .put("provider", route.settings.preset.name)
                        .put("model", route.settings.modelId),
                )
                if (!beforeModel.allowed) {
                    executions.checkpointRun(run.id, AgentRunState.FAILED, "Bloqueado por hook BEFORE_MODEL.")
                    return
                }
                val modelStep = executions.beginStep(
                    runId = run.id,
                    ordinal = ordinal++,
                    kind = "model:${route.settings.preset.name}",
                    effect = AgentStepEffect.READ_ONLY,
                    payload = providerPayloadFingerprint(route.settings, ledger),
                    idempotencyKey = "${run.id}:model:$toolRounds:${route.settings.preset.name}",
                )
                val calls = linkedMapOf<String, BackgroundToolCall>()
                var terminal: ProviderEvent? = null
                var textProgress = false
                var reasoningProgress = false
                try {
                    val request = ProviderRequest(
                        sessionId = if (route.settings.preset == ProviderPreset.LOCAL_GGUF) run.sessionId else turnId,
                        modelId = route.settings.modelId,
                        messages = listOf(environment(toolbox, run.sessionId, route.settings, ledger)) +
                            ContinuousChatEngine.prepareForProvider(
                                ledger,
                                route.settings.continuousChat,
                            ),
                        tools = toolbox.definitions,
                        providerContinuation = state.continuation,
                    )
                    route.provider.stream(request).collect { event ->
                        when (event) {
                            is ProviderEvent.ToolCallStarted -> {
                                val current = calls.getOrPut(event.callId) {
                                    BackgroundToolCall(event.toolName)
                                }
                                if (event.toolName.isNotBlank()) current.name = event.toolName
                            }
                            is ProviderEvent.ToolArgumentsDelta -> {
                                val current = calls.getOrPut(event.callId) {
                                    BackgroundToolCall("unknown")
                                }
                                if (current.arguments.length + event.jsonDelta.length > MAX_TOOL_ARGUMENT_CHARS) {
                                    throw IllegalArgumentException("Argumentos de ferramenta excederam o limite.")
                                }
                                current.arguments.append(event.jsonDelta)
                            }
                            is ProviderEvent.Completed,
                            is ProviderEvent.Cancelled,
                            is ProviderEvent.Failed,
                            -> terminal = event
                            is ProviderEvent.TextDelta -> {
                                textProgress = textProgress || event.value.isNotEmpty()
                                state = ChatReducer.applyProviderEvent(state, turnId, event)
                                persistStreamingCheckpoint()
                            }
                            is ProviderEvent.ReasoningDelta -> {
                                reasoningProgress = reasoningProgress || event.value.isNotBlank()
                                state = ChatReducer.applyProviderEvent(state, turnId, event)
                                persistStreamingCheckpoint()
                            }
                            is ProviderEvent.ModelResolved,
                            is ProviderEvent.Usage,
                            is ProviderEvent.Continuation,
                            -> {
                                state = ChatReducer.applyProviderEvent(state, turnId, event)
                                // Model/usage events can arrive for every provider chunk. Persisting
                                // each one caused hundreds of full transcript writes per answer.
                                persistStreamingCheckpoint()
                            }
                            is ProviderEvent.ToolCallCompleted -> Unit
                        }
                    }
                    persistStreamingCheckpoint(force = true)
                    executions.finishStep(modelStep.id, succeeded = true, resultRef = terminal?.javaClass?.simpleName)
                    hookEngine.evaluate(
                        HookEvent.AFTER_MODEL,
                        JSONObject()
                            .put("run_id", run.id)
                            .put("model", route.settings.modelId)
                            .put("terminal", terminal?.javaClass?.simpleName),
                    )
                } catch (cancelled: CancellationException) {
                    executions.finishStep(modelStep.id, succeeded = false, error = "cancelled")
                    throw cancelled
                } catch (error: Throwable) {
                    executions.finishStep(modelStep.id, succeeded = false, error = error.message)
                    terminal = ProviderEvent.Failed(true, error.message?.take(500) ?: "Falha interna do provider.")
                }

                val failure = terminal as? ProviderEvent.Failed
                // Sem rede nenhum provider tem como responder, então percorrer a lista de rotas
                // só queima todas elas em sequência e termina em falha definitiva por um motivo
                // temporário. Melhor parar em RECOVERING: o AgentRecoveryWorker já é agendado com
                // restrição de rede, então quem retoma isso é o próprio Android quando a conexão
                // voltar — inclusive com o app fechado.
                if (failure != null && run.requiresNetwork && !ConnectivityMonitor.isOnline(appContext)) {
                    state = ChatReducer.applyProviderEvent(
                        state,
                        turnId,
                        ProviderEvent.Failed(true, ConnectivityMonitor.OFFLINE_MESSAGE),
                    )
                    persist(run, settings, state, ledger, activities)
                    executions.checkpointRun(run.id, AgentRunState.RECOVERING, "Aguardando rede.")
                    DistributionBindings.scheduleExecutionRecovery(appContext)
                    return
                }
                if (failure != null && failure.retryable && routeIndex + 1 < routes.size) {
                    route = routes[++routeIndex]
                    continue
                }
                if (failure != null) {
                    state = ChatReducer.applyProviderEvent(state, turnId, failure)
                    persist(run, settings, state, ledger, activities)
                    executions.checkpointRun(run.id, AgentRunState.FAILED, failure.safeMessage)
                    return
                }
                val cancelled = terminal as? ProviderEvent.Cancelled
                if (cancelled != null) {
                    state = ChatReducer.applyProviderEvent(state, turnId, cancelled)
                    persist(run, settings, state, ledger, activities)
                    executions.checkpointRun(run.id, AgentRunState.PAUSED, "Execução cancelada.")
                    return
                }

                val invocations = calls.map { (callId, value) ->
                    AgentToolInvocation(callId, value.name, value.arguments.toString().ifBlank { "{}" })
                }
                if (invocations.isEmpty()) {
                    if (!textProgress) {
                        if (reasoningProgress) {
                            val incomplete = ProviderEvent.Failed(
                                false,
                                "O modelo local consumiu a resposta apenas em raciocínio e não produziu uma resposta final. " +
                                    "Tente novamente ou use um modelo local maior.",
                            )
                            state = ChatReducer.applyProviderEvent(state, turnId, incomplete)
                            persist(run, settings, state, ledger, activities)
                            executions.checkpointRun(run.id, AgentRunState.FAILED, incomplete.safeMessage)
                            return
                        }
                        noProgressCycles += 1
                        executions.setNoProgressCycles(run.id, noProgressCycles)
                        if (noProgressCycles >= MAX_NO_PROGRESS_CYCLES) {
                            val stalled = ProviderEvent.Failed(false, "Três ciclos consecutivos sem progresso verificável.")
                            state = ChatReducer.applyProviderEvent(state, turnId, stalled)
                            persist(run, settings, state, ledger, activities)
                            executions.checkpointRun(run.id, AgentRunState.FAILED, stalled.safeMessage)
                            return
                        }
                        continue
                    }
                    state = ChatReducer.applyProviderEvent(
                        state,
                        turnId,
                        terminal ?: ProviderEvent.Completed("completed"),
                    )
                    val assistantText = state.messages.lastOrNull {
                        it.role == ChatRole.ASSISTANT && it.text.isNotBlank()
                    }?.text.orEmpty()
                    if (assistantText.isNotBlank()) {
                        ledger = ledger + ProviderMessage(
                            role = "assistant",
                            parts = listOf(MessagePart.Text(assistantText)),
                        )
                    }
                    persist(run, settings, state, ledger, activities)
                    executions.setNoProgressCycles(run.id, 0)
                    executions.checkpointRun(run.id, AgentRunState.SUCCEEDED)
                    return
                }

                noProgressCycles = 0
                executions.setNoProgressCycles(run.id, 0)
                val assistantText = state.messages.lastOrNull {
                    it.role == ChatRole.ASSISTANT && it.status == ChatMessageStatus.STREAMING
                }?.text.orEmpty()
                state = ChatReducer.applyProviderEvent(state, turnId, ProviderEvent.Completed("tool_calls"))
                ledger = ledger + ProviderMessage(
                    role = "assistant",
                    parts = assistantText.takeIf(String::isNotBlank)?.let { listOf(MessagePart.Text(it)) }.orEmpty(),
                    toolCalls = invocations.map { invocation ->
                        ProviderToolCall(invocation.callId, invocation.toolName, invocation.argumentsJson)
                    },
                )
                val roundOutcomes = mutableListOf<String>()

                for (invocation in invocations) {
                    val afterMessageId = state.messages.lastOrNull()?.id
                    if (invocation.toolName == "ask_user") {
                        val question = JSONObject(invocation.argumentsJson)
                        activities = activities + ToolActivity(
                            callId = invocation.callId,
                            toolName = invocation.toolName,
                            summary = "Aguardando resposta do usuário",
                            status = ToolActivityStatus.AWAITING_INPUT,
                            afterMessageId = afterMessageId,
                            resultPreview = invocation.argumentsJson.take(600),
                        )
                        persist(run, settings, state, ledger, activities)
                        executions.waitForInput(
                            run.id,
                            JSONObject()
                                .put("type", "question")
                                .put("call_id", invocation.callId)
                                .put("tool_name", invocation.toolName)
                                .put("arguments", invocation.argumentsJson)
                                .put("question", question.optString("question"))
                                .toString(),
                            "Pergunta do agente pendente.",
                        )
                        return
                    }
                    when (val preparation = toolbox.prepare(invocation)) {
                        is AgentToolPreparation.Rejected -> {
                            ledger = ledger + preparation.result.providerMessage()
                            roundOutcomes += "rejected:${sha256(preparation.result.payload)}"
                            activities = activities + ToolActivity(
                                invocation.callId,
                                invocation.toolName,
                                "Ferramenta rejeitada",
                                ToolActivityStatus.FAILED,
                                afterMessageId,
                                preparation.result.payload.take(600),
                            )
                        }
                        is AgentToolPreparation.Ready -> {
                            val prepared = preparation.prepared
                            if (prepared.requiresApproval) {
                                publishStatus(run, "Aguardando aprovação: ${invocation.toolName}", route.settings.modelId)
                                activities = activities + ToolActivity(
                                    invocation.callId,
                                    invocation.toolName,
                                    prepared.request.summary,
                                    ToolActivityStatus.AWAITING_APPROVAL,
                                    afterMessageId,
                                    "Aprovação obrigatória; abra o app para revisar.",
                                )
                                persist(run, settings, state, ledger, activities)
                                executions.waitForInput(
                                    run.id,
                                    JSONObject()
                                        .put("type", "approval")
                                        .put("call_id", invocation.callId)
                                        .put("tool_name", invocation.toolName)
                                        .put("arguments", invocation.argumentsJson)
                                        .toString(),
                                    "Aprovação de ferramenta pendente.",
                                )
                                return
                            }
                            val effect = when (prepared.request.effect) {
                                ToolEffect.READ_ONLY -> AgentStepEffect.READ_ONLY
                                ToolEffect.WORKSPACE_MUTATION,
                                ToolEffect.EXTERNAL_MUTATION,
                                ToolEffect.DESTRUCTIVE,
                                -> AgentStepEffect.MUTATION
                            }
                            val idempotencyKey =
                                "${run.id}:tool:${sha256(invocation.toolName + "\u0000" + invocation.argumentsJson)}"
                            if (
                                effect == AgentStepEffect.MUTATION &&
                                executions.successfulIdempotentStep(run.id, idempotencyKey) != null
                            ) {
                                val duplicate = AgentToolResult(
                                    invocation.callId,
                                    invocation.toolName,
                                    "Mutação idêntica já concluída nesta execução; repetição suprimida.",
                                    isError = true,
                                )
                                ledger = ledger + duplicate.providerMessage()
                                roundOutcomes += "duplicate:${sha256(duplicate.payload)}"
                                activities = activities + ToolActivity(
                                    invocation.callId,
                                    invocation.toolName,
                                    "Repetição mutável suprimida",
                                    ToolActivityStatus.FAILED,
                                    afterMessageId,
                                    duplicate.payload,
                                )
                                continue
                            }
                            val toolStep = executions.beginStep(
                                run.id,
                                ordinal++,
                                "tool:${invocation.toolName}",
                                effect,
                                invocation.argumentsJson,
                                idempotencyKey,
                            )
                            activities = activities + ToolActivity(
                                invocation.callId,
                                invocation.toolName,
                                prepared.request.summary,
                                ToolActivityStatus.RUNNING,
                                afterMessageId,
                            )
                            publishStatus(run, prepared.request.summary, route.settings.modelId)
                            persist(run, settings, state, ledger, activities)
                            val result = toolbox.execute(prepared, approved = false)
                            roundOutcomes += if (result.isError) {
                                "error:${sha256(result.payload)}"
                            } else {
                                "ok:${sha256(result.payload)}"
                            }
                            executions.finishStep(
                                toolStep.id,
                                succeeded = !result.isError,
                                resultRef = sha256(result.payload),
                                error = result.payload.takeIf { result.isError },
                            )
                            ledger = ledger + result.providerMessage()
                            activities = activities.map { activity ->
                                if (activity.callId == invocation.callId) {
                                    activity.copy(
                                        status = if (result.isError) ToolActivityStatus.FAILED else ToolActivityStatus.COMPLETE,
                                        resultPreview = result.payload.take(600),
                                    )
                                } else activity
                            }
                            persist(run, settings, state, ledger, activities)
                        }
                    }
                }

                val roundSignature = sha256(
                    invocations.joinToString("\u0001") {
                        "${it.toolName}\u0000${it.argumentsJson}"
                    } + "\u0002" + roundOutcomes.joinToString("\u0001"),
                )
                repeatedRoundCount = if (roundSignature == previousRoundSignature) {
                    repeatedRoundCount + 1
                } else {
                    1
                }
                previousRoundSignature = roundSignature
                if (repeatedRoundCount >= MAX_IDENTICAL_TOOL_ROUNDS) {
                    val stalled = ProviderEvent.Failed(
                        false,
                        "O agente repetiu a mesma rodada de ferramentas sem alterar o resultado; execução interrompida.",
                    )
                    state = ChatReducer.continueAfterTool(
                        state,
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(),
                        route.provider.descriptor.id,
                        route.provider.descriptor.displayName,
                        route.settings.modelId,
                    )
                    state.activeTurnId?.let { active ->
                        state = ChatReducer.applyProviderEvent(state, active, stalled)
                    }
                    persist(run, settings, state, ledger, activities)
                    executions.checkpointRun(run.id, AgentRunState.FAILED, stalled.safeMessage)
                    return
                }

                toolRounds += 1
                turnId = UUID.randomUUID().toString()
                state = ChatReducer.continueAfterTool(
                    state,
                    turnId,
                    UUID.randomUUID().toString(),
                    route.provider.descriptor.id,
                    route.provider.descriptor.displayName,
                    route.settings.modelId,
                )
                persist(run, settings, state, ledger, activities)
            }
            executions.checkpointRun(run.id, AgentRunState.FAILED, "Limite de rodadas de ferramentas atingido.")
        } catch (cancelled: CancellationException) {
            // Persistir a transição pra fora de STREAMING precisa rodar mesmo com o job já
            // cancelado, senão a chamada suspend seguinte é abortada antes de gravar nada e a
            // mensagem fica presa em "streaming, texto vazio" pra sempre.
            withContext(NonCancellable) {
                state = ChatReducer.applyProviderEvent(
                    state,
                    turnId,
                    ProviderEvent.Cancelled("Pausado pelo usuário ou pelo Android."),
                )
                persist(run, settings, state, ledger, activities)
                executions.checkpointRun(run.id, AgentRunState.PAUSED, "Execução pausada pelo Android ou usuário.")
            }
            throw cancelled
        } catch (error: Throwable) {
            executions.checkpointRun(run.id, AgentRunState.FAILED, error.message ?: "Falha interna do executor.")
        }
    }

    private suspend fun persist(
        run: AgentRunEntity,
        settings: ProviderSettings,
        state: ChatState,
        ledger: List<ProviderMessage>,
        activities: List<ToolActivity>,
    ) {
        sessions.save(run.sessionId, settings, state.messages, ledger, activities)
        AgentExecutionEvents.sessionChanged(run.sessionId)
    }

    private suspend fun environment(
        toolbox: AgentToolbox,
        conversationId: String,
        settings: ProviderSettings,
        ledger: List<ProviderMessage>,
    ): ProviderMessage {
        val base = ProviderMessage(
        role = "system",
        parts = listOf(
            MessagePart.Text(
                """
                <environment>
                Refrator no Android; execução em segundo plano no modo ${settings.executionMode.name}.
                ${toolbox.definitions.size} ferramentas estão anexadas por schema; recursos que exigem
                Activity visível não estão disponíveis. Afirme ações somente com resultado tool e não
                invente saída, acesso ou identidade de provider.
                </environment>
                """.trimIndent(),
            ),
        ),
        )
        val baseText = (base.parts.firstOrNull() as? MessagePart.Text)?.value.orEmpty()
        val selectedProfile = agentPlatform.selectedProfile(settings)
        return contextMemory.assemble(
            baseEnvironment = baseText + selectedProfile.systemPrompt.takeIf { it.isNotBlank() }
                ?.let { "\n\n<agent_profile id=\"${selectedProfile.id}\">\n$it\n</agent_profile>" }
                .orEmpty(),
            workspaceId = workspaceId,
            conversationId = conversationId,
            contextWindowTokens = providers.effectiveContextWindowTokens(settings),
            queryText = ledger.asReversed()
                .firstOrNull { it.role == "user" }
                ?.parts
                ?.filterIsInstance<MessagePart.Text>()
                ?.joinToString("\n") { it.value }
                .orEmpty(),
        ).message
    }

    private fun providerPayloadFingerprint(
        settings: ProviderSettings,
        ledger: List<ProviderMessage>,
    ): String = "${settings.preset.name}:${settings.modelId}:${ledger.size}:${ledger.lastOrNull()?.hashCode()}"

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun publishStatus(run: AgentRunEntity, operation: String, model: String) {
        AgentExecutionStatusBus.publish(
            AgentExecutionDisplay(run.id, run.summary, operation.take(160), model.take(120)),
        )
    }

    private data class BackgroundToolCall(
        var name: String,
        val arguments: StringBuilder = StringBuilder(),
    )

    private companion object {
        const val MAX_TOOL_ROUNDS = 64
        const val MAX_TOOL_ARGUMENT_CHARS = 2_300_000
        const val MAX_NO_PROGRESS_CYCLES = 3
        const val MAX_IDENTICAL_TOOL_ROUNDS = 3
        const val STREAM_CHECKPOINT_INTERVAL_MS = 350L
    }
}
