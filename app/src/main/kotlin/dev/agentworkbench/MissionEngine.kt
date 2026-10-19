package dev.agentworkbench

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.agentworkbench.core.MessagePart
import dev.agentworkbench.core.ProviderEvent
import dev.agentworkbench.core.ProviderMessage
import dev.agentworkbench.core.ProviderRequest
import dev.agentworkbench.core.ProviderToolCall
import dev.agentworkbench.core.ToolDefinition
import dev.agentworkbench.core.ToolEffect
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Mission mode, in the spirit of OpenAI Dots / Grok Bots: the group chat is for discussion and
 * reports, while the actual work runs in the background. Each autonomous task on the board gets a
 * worker — its owner prisma — that works with tools on its own screen and folder, feeds in group
 * and private messages as they arrive, keeps the board updated, sleeps and wakes itself
 * (wake_me), continues in bounded sessions for hours or days, survives app restarts, and posts
 * to the group only when it finishes, gets blocked or needs someone.
 */
internal object MissionEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = mutableMapOf<String, Job>()
    private val runningFlow = MutableStateFlow<Set<String>>(emptySet())

    /** Keys "groupId:taskId" of the workers running right now. */
    val running: StateFlow<Set<String>> = runningFlow.asStateFlow()

    /** Tool rounds per work session; then the worker checkpoints and continues in a new session. */
    private const val ROUNDS_PER_SESSION = 30

    /** Safety stop: sessions per task per day without the user writing in the group. */
    private const val MAX_SESSIONS_PER_DAY = 40
    private const val LOG_SIZE = 40
    private const val DONE = "[PRONTO]"
    private const val BLOCKED = "[TRAVADA]"

    /** Tools that always ask the user, even with auto-approval on. */
    private val SENSITIVE = setOf(
        "whatsapp_send", "sms_draft", "phone_dial", "email_compose", "calendar_add", "alarm_set", "timer_set",
        "prisma_create", "routine_delete", "app_open", "settings_open",
        "accessibility_action", "accessibility_gesture", "accessibility_global", "adb_shell",
    )

    fun key(groupId: String, taskId: String) = "$groupId:$taskId"

    fun anyRunning(): Boolean = runningFlow.value.isNotEmpty()

    fun isRunning(groupId: String, taskId: String): Boolean = key(groupId, taskId) in runningFlow.value

    /** Starts (or resumes) the background worker of a task. */
    fun start(context: Context, groupId: String, taskId: String): Job? {
        val key = key(groupId, taskId)
        jobs[key]?.takeIf { it.isActive }?.let { return it }
        val app = context.applicationContext
        val repository = GroupRepository(app)
        GroupStore.ensureLoaded(repository)
        GroupStore.update(repository, groupId) { group ->
            group.copy(tasks = group.tasks.map { if (it.id == taskId) it.copy(autonomous = true, status = TaskStatus.DOING, wakeAtMillis = 0) else it })
        }
        WorkManager.getInstance(app).cancelUniqueWork(workName(groupId, taskId))
        TeamKeepAliveService.ensure(app)
        val job = scope.launch {
            runningFlow.update { it + key }
            try {
                session(app, groupId, taskId)
            } finally {
                runningFlow.update { it - key }
                jobs.remove(key)
            }
        }
        jobs[key] = job
        return job
    }

    /** Stops a worker; the task stays on the board with what was done. */
    fun stop(context: Context, groupId: String, taskId: String) {
        jobs.remove(key(groupId, taskId))?.cancel()
        runningFlow.update { it - key(groupId, taskId) }
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(workName(groupId, taskId))
        GroupStore.update(GroupRepository(context), groupId) { group ->
            group.copy(tasks = group.tasks.map { if (it.id == taskId) it.copy(autonomous = false, wakeAtMillis = 0) else it })
        }
        log(context, groupId, taskId, "■ Parado pelo usuário.")
    }

    /** After an app restart: resume every autonomous task that was working and is not sleeping. */
    fun resumeAll(context: Context) {
        val repository = GroupRepository(context)
        GroupStore.ensureLoaded(repository)
        val now = System.currentTimeMillis()
        GroupStore.groups.value.forEach { group ->
            group.tasks.filter { it.autonomous && it.status == TaskStatus.DOING && it.wakeAtMillis <= now }.forEach { task ->
                start(context, group.id, task.id)
            }
        }
    }

    /** Schedules the worker to wake up again (survives app death through WorkManager). */
    fun wakeLater(context: Context, groupId: String, taskId: String, minutes: Int, reason: String) {
        val delayMs = minutes.coerceIn(1, 24 * 60) * 60_000L
        GroupStore.update(GroupRepository(context), groupId) { group ->
            group.copy(tasks = group.tasks.map { if (it.id == taskId) it.copy(wakeAtMillis = System.currentTimeMillis() + delayMs) else it })
        }
        val request = OneTimeWorkRequestBuilder<MissionWakeWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("group" to groupId, "task" to taskId))
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(workName(groupId, taskId), ExistingWorkPolicy.REPLACE, request)
        val at = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(System.currentTimeMillis() + delayMs))
        log(context, groupId, taskId, "💤 Dormindo até $at: $reason")
    }

    private fun workName(groupId: String, taskId: String) = "mission-$groupId-$taskId"

    private fun log(context: Context, groupId: String, taskId: String, line: String) {
        val stamp = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date())
        GroupStore.update(GroupRepository(context), groupId) { group ->
            group.copy(tasks = group.tasks.map {
                if (it.id == taskId) it.copy(log = (it.log + "$stamp $line").takeLast(LOG_SIZE), updatedAtMillis = System.currentTimeMillis()) else it
            })
        }
    }

    private fun updateTask(context: Context, groupId: String, taskId: String, change: (GroupTask) -> GroupTask) {
        GroupStore.update(GroupRepository(context), groupId) { group ->
            group.copy(tasks = group.tasks.map { if (it.id == taskId) change(it).copy(updatedAtMillis = System.currentTimeMillis()) else it })
        }
    }

    private fun post(context: Context, groupId: String, authorId: String, text: String, activity: List<String> = emptyList()) {
        GroupStore.update(GroupRepository(context), groupId) {
            it.withMessage(GroupMessage(authorId = authorId, text = text, activity = activity))
        }
    }

    /** Whether [tool] may run without asking, given the group's auto-approval setting. */
    fun autoApproved(group: PrismaGroup, tool: String, effect: ToolEffect): Boolean =
        group.autoApprove && tool !in SENSITIVE && effect != ToolEffect.DESTRUCTIVE

    /** One bounded work session; ends finished, blocked, asleep, or with a continuation queued. */
    private suspend fun session(context: Context, groupId: String, taskId: String) {
        val repository = GroupRepository(context)
        val bots = BotRepository(context)
        val group = GroupStore.get(groupId) ?: return
        val task = group.tasks.firstOrNull { it.id == taskId } ?: return
        val admin = bots.ensureAdmin()
        val owner = bots.byId(task.ownerId) ?: admin
        val members = group.memberIds.mapNotNull(bots::byId).filterNot(PrismaAdmin::isAdmin)
        val today = SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date())
        val counters = context.getSharedPreferences("mission-sessions", Context.MODE_PRIVATE)
        val counterKey = "$groupId-$taskId-$today"
        val usedToday = counters.getInt(counterKey, 0)
        if (usedToday >= MAX_SESSIONS_PER_DAY && group.lastUserAtMillis < System.currentTimeMillis() - 3_600_000L) {
            updateTask(context, groupId, taskId) { it.copy(status = TaskStatus.BLOCKED, note = "Pausada: muitas sessões hoje sem o usuário. Fale no grupo para continuar.") }
            post(context, groupId, owner.id, "⏸️ Pausei a tarefa \"${task.title}\": já trabalhei $usedToday sessões hoje. @você, quer que eu continue?")
            PrismaNotifier.notify(context, "${owner.name} pausou uma tarefa", task.title, groupId = groupId, kind = PrismaNotifications.Kind.GROUP)
            return
        }
        counters.edit().putInt(counterKey, usedToday + 1).apply()
        updateTask(context, groupId, taskId) { it.copy(sessions = it.sessions + 1) }

        val settingsRepository = ProviderSettingsRepository(context)
        val settings = settingsRepository.settingsForPrisma(owner, settingsRepository.load())
        val allowCleartext = TransportSecurityPreferences(context).allowLocalCleartext()
        val route = settingsRepository.routesForPrisma(owner, settings, allowCleartext).firstOrNull()
        if (route == null) {
            log(context, groupId, taskId, "⚠️ Modelo indisponível — " + settingsRepository.prismaRouteProblem(settings, allowCleartext))
            return
        }
        val conversationId = "grupo-$groupId-${owner.id}"
        val toolbox = AgentToolbox(
            appContext = context,
            profile = DistributionBindings.profile(),
            executionMode = settings.executionMode,
            workspaceRoot = java.io.File(context.filesDir, "workspace").apply { mkdirs() },
            activity = null,
            conversationId = conversationId,
            callerBotId = owner.id,
        )
        val tools: List<ToolDefinition> = toolbox.definitions.filterNot { it.name in setOf("ask_user", "group_read", "group_post") } + WAKE_TOOL
        val handles = Mentions.handles(listOf(admin) + members)
        val names = (members + admin).associate { it.id to it.name }
        // Same folder as the Prisma's own computer (computer_shell's /workspace).
        val myFolder = "prismas/${PrismaComputer.placeFor(context, owner).handle}"
        java.io.File(context.filesDir, "workspace/$myFolder").mkdirs()
        val system = buildString {
            append(owner.systemPrompt())
            append(runCatching { groupSocietyMemory(context, owner, group) }.getOrDefault(""))
            append(pluginsPrompt(context))
            append("\n\n<missao>\nVocê está trabalhando EM SEGUNDO PLANO numa tarefa do quadro do grupo \"").append(group.name).append("\". ")
            append("Ninguém está esperando cada passo seu: trabalhe direto, use as ferramentas, decida sozinho o que é do seu escopo e só interrompa ")
            append("o usuário se for indispensável. Seus colegas: ").append(Mentions.legend(listOf(admin) + members, handles, group.roles)).append(".\n")
            append("Seu PC próprio: computer_shell (Ubuntu só seu, guarda estado; /workspace é sua pasta; background=true para coisas longas) ")
            append("e seu navegador com logins salvos: web_open/web_read/web_act (página como texto numerado, vários passos por chamada), ")
            append("web_login quando precisar que o usuário entre numa conta.\n")
            append("Trabalhe rápido como uma pessoa: sites no seu navegador (web_*), apps na sua tela com shadow_actions ")
            append("(várias ações por chamada, com captura que você vê), endereços com shadow_open_url (nunca digite na barra), leitura com shadow_screen_text.\n")
            append("Seu computador: a tela paralela é SÓ SUA (shadow_*), sua pasta é ").append(myFolder).append("/ e a pasta do time é times/")
                .append(group.name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-")).append("/. Para abrir páginas/jogos use web_serve.\n")
            append("Mantenha o quadro em dia (task_board update com nota e progress). Para falar com um colega use prisma_dm. ")
            append("Se precisar esperar algo (build, resposta, horário), chame wake_me e pare; você acorda sozinho.\n")
            append("Ferramentas rodam sem pedir permissão, exceto ações sensíveis (mensagens, ligações, compras, apagar, tela principal).\n")
            append("Quando terminar de verdade, responda começando com $DONE e um relatório curto (o que fez, onde estão os arquivos, como conferir). ")
            append("Se não houver como avançar, comece com $BLOCKED e diga exatamente o que falta e de quem.\n</missao>")
            append(AccountUsage.awareness(context, route.settings))
        }
        val recent = group.messages.filter { !it.streaming && it.text.isNotBlank() }.takeLast(12).joinToString("\n") {
            "[${names[it.authorId] ?: if (it.authorId == GroupAuthors.USER) "Usuário" else "Sistema"}]: ${it.text.trim().take(500)}"
        }
        var ledger = listOf(
            ProviderMessage("system", listOf(MessagePart.Text(system))),
            ProviderMessage(
                "user",
                listOf(
                    MessagePart.Text(
                        buildString {
                            append("Sua tarefa [").append(task.id).append("]: ").append(task.title).append('\n')
                            if (task.criteria.isNotBlank()) append("Critério de pronto: ").append(task.criteria).append('\n')
                            if (task.note.isNotBlank()) append("Última nota/progresso: ").append(task.note).append('\n')
                            if (task.log.isNotEmpty()) append("O que você já fez (log):\n").append(task.log.takeLast(15).joinToString("\n")).append('\n')
                            group.memory.takeIf(String::isNotBlank)?.let { append("\nAta do grupo:\n").append(it.take(1_500)).append('\n') }
                            append("\nMensagens recentes do grupo:\n").append(recent).append('\n')
                            PrismaDms.takeUnread(context, owner.id).takeIf { it.isNotEmpty() }?.let { dms ->
                                append("\nMensagens privadas para você:\n").append(dms.joinToString("\n") { "${names[it.fromId] ?: "Prisma"}: ${it.text}" }).append('\n')
                            }
                            append("\nContinue a tarefa a partir daqui.")
                        },
                    ),
                ),
            ),
        )
        var lastSeen = group.messages.lastOrNull()?.id
        var finalText = ""
        try {
            for (round in 0 until ROUNDS_PER_SESSION) {
                val names2 = linkedMapOf<String, String>()
                val args = linkedMapOf<String, StringBuilder>()
                val text = StringBuilder()
                var failure: String? = null
                route.provider.stream(
                    ProviderRequest(
                        sessionId = "mission-$groupId-$taskId",
                        modelId = route.settings.modelId,
                        messages = ledger,
                        tools = tools,
                        reasoningEffort = route.settings.reasoningEffort,
                    ),
                ).collect { event ->
                    when (event) {
                        is ProviderEvent.TextDelta -> text.append(event.value)
                        is ProviderEvent.ToolCallStarted -> {
                            if (event.toolName.isNotBlank()) names2[event.callId] = event.toolName
                            args.getOrPut(event.callId) { StringBuilder() }
                        }
                        is ProviderEvent.ToolArgumentsDelta -> args.getOrPut(event.callId) { StringBuilder() }.append(event.jsonDelta)
                        is ProviderEvent.Usage -> {
                            val spent = event.inputTokens + event.outputTokens
                            updateTask(context, groupId, taskId) { it.copy(tokens = it.tokens + spent) }
                            GroupStore.update(repository, groupId, persist = false) { it.copy(tokensTotal = it.tokensTotal + spent, tokensSinceUser = it.tokensSinceUser + spent) }
                        }
                        is ProviderEvent.RateLimits ->
                            parseRateLimits(event.headers).takeIf { it.isNotEmpty() }?.let { RateLimitMemory.record(route.settings.preset.name, it) }
                        is ProviderEvent.Failed -> failure = event.safeMessage
                        is ProviderEvent.Cancelled -> failure = event.reason
                        else -> Unit
                    }
                }
                failure?.let { throw PrismaModel.Failure(it) }
                finalText = text.toString().trim()
                val invocations = args.keys.map { id -> AgentToolInvocation(id, names2[id].orEmpty(), args[id].toString().ifBlank { "{}" }) }
                    .filter { it.toolName.isNotBlank() }
                if (invocations.isEmpty()) break
                ledger = ledger + ProviderMessage(
                    role = "assistant",
                    parts = finalText.takeIf(String::isNotBlank)?.let { listOf(MessagePart.Text(it)) }.orEmpty(),
                    toolCalls = invocations.map { ProviderToolCall(it.callId, it.toolName, it.argumentsJson) },
                )
                var sleeping = false
                for (invocation in invocations) {
                    if (invocation.toolName == "wake_me") {
                        val json = runCatching { org.json.JSONObject(invocation.argumentsJson) }.getOrDefault(org.json.JSONObject())
                        wakeLater(context, groupId, taskId, json.optInt("minutes", 10), json.optString("reason").ifBlank { "continuar" })
                        ledger = ledger + AgentToolResult(invocation.callId, invocation.toolName, "{\"ok\":true}", false).providerMessage()
                        sleeping = true
                        continue
                    }
                    val result = runMissionTool(context, toolbox, group, owner, invocation)
                    val headline = toolActivityHeadline(
                        ToolActivity(invocation.callId, invocation.toolName, "", if (result.isError) ToolActivityStatus.FAILED else ToolActivityStatus.COMPLETE),
                    )
                    log(context, groupId, taskId, (if (result.isError) "⚠️ " else "• ") + headline)
                    ledger = ledger + result.providerMessage()
                    missionImage(context, invocation.toolName, result, route.settings)?.let { ledger = ledger + it }
                }
                if (sleeping) return
                // Messages that arrived meanwhile (group and private) reach the worker right away.
                val current = GroupStore.get(groupId)
                val index = current?.messages?.indexOfFirst { it.id == lastSeen } ?: -1
                val fresh = current?.messages?.drop(index + 1)?.filter { !it.streaming && it.authorId != owner.id && it.text.isNotBlank() }.orEmpty()
                current?.messages?.lastOrNull()?.let { lastSeen = it.id }
                val dms = PrismaDms.takeUnread(context, owner.id)
                if (fresh.isNotEmpty() || dms.isNotEmpty()) {
                    ledger = ledger + ProviderMessage(
                        "user",
                        listOf(
                            MessagePart.Text(
                                buildString {
                                    if (fresh.isNotEmpty()) append("[Chegou no grupo]\n").append(fresh.joinToString("\n") { "${names[it.authorId] ?: if (it.authorId == GroupAuthors.USER) "Usuário" else "Sistema"}: ${it.text.take(600)}" }).append('\n')
                                    if (dms.isNotEmpty()) append("[Mensagens privadas]\n").append(dms.joinToString("\n") { "${names[it.fromId] ?: "Prisma"}: ${it.text.take(800)}" }).append('\n')
                                    append("Leve isso em conta e continue.")
                                },
                            ),
                        ),
                    )
                }
                if (round == ROUNDS_PER_SESSION - 1) {
                    // Long task: checkpoint and continue in a fresh session (keeps the context small).
                    log(context, groupId, taskId, "↻ Sessão longa: continuo em seguida.")
                    wakeLater(context, groupId, taskId, 1, "continuar a sessão")
                    return
                }
            }
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            val message = error.message ?: "erro"
            log(context, groupId, taskId, "⚠️ $message")
            val limit = "limite" in message.lowercase() || "rate" in message.lowercase()
            wakeLater(context, groupId, taskId, if (limit) 30 else 5, if (limit) "esperar o limite do plano" else "tentar de novo")
            return
        }
        // The worker answered without tools: finished, blocked, or just a progress report.
        val report = finalText.removePrefix(DONE).removePrefix(BLOCKED).trim()
        when {
            finalText.startsWith(DONE) -> {
                updateTask(context, groupId, taskId) { it.copy(status = TaskStatus.REVIEW, progress = 100, note = report.take(600), autonomous = false) }
                post(context, groupId, owner.id, "✅ Terminei \"${task.title}\".\n$report")
                log(context, groupId, taskId, "✅ Pronta para revisão.")
            }
            finalText.startsWith(BLOCKED) -> {
                updateTask(context, groupId, taskId) { it.copy(status = TaskStatus.BLOCKED, note = report.take(600)) }
                post(context, groupId, owner.id, "⛔ Travei em \"${task.title}\": $report")
                log(context, groupId, taskId, "⛔ Travada.")
                if (Mentions.mentionsUser(report)) PrismaNotifications.groupMention(context, groupId, owner, report)
            }
            else -> {
                if (report.isNotBlank()) log(context, groupId, taskId, "💬 ${report.take(200)}")
                wakeLater(context, groupId, taskId, 1, "seguir trabalhando")
            }
        }
        GroupEngine.ensureLive(context, groupId)
    }

    private suspend fun runMissionTool(context: Context, toolbox: AgentToolbox, group: PrismaGroup, owner: Bot, invocation: AgentToolInvocation): AgentToolResult {
        return when (val preparation = toolbox.prepare(invocation)) {
            is AgentToolPreparation.Rejected -> preparation.result
            is AgentToolPreparation.Ready -> {
                val prepared = preparation.prepared
                val approved = if (!prepared.requiresApproval) {
                    true
                } else if (autoApproved(group, invocation.toolName, prepared.request.effect)) {
                    true
                } else {
                    GroupEngine.requestApproval(context, group, owner, prepared.request.summary)
                }
                toolbox.execute(prepared, approved)
            }
        }
    }

    private fun missionImage(context: Context, tool: String, result: AgentToolResult, settings: ProviderSettings): ProviderMessage? {
        if (result.isError || !settings.preset.capabilities.images) return null
        if (!(tool.contains("screenshot") || tool == "capture_app_screen" || tool == "look_at_image" || tool == "shadow_actions")) return null
        val workspace = java.io.File(context.filesDir, "workspace").canonicalFile
        val file = Regex("[\\w./-]+\\.(png|jpe?g|webp)", RegexOption.IGNORE_CASE).findAll(result.payload).map { it.value }
            .mapNotNull { path -> java.io.File(workspace, path.removePrefix("/")).takeIf { it.isFile && it.length() in 1..(8L * 1024 * 1024) } }
            .firstOrNull() ?: return null
        val mime = if (file.extension.lowercase() in setOf("jpg", "jpeg")) "image/jpeg" else if (file.extension.lowercase() == "webp") "image/webp" else "image/png"
        return ProviderMessage("user", listOf(MessagePart.Text("Imagem de $tool (${file.name}):"), MessagePart.ImageReference(file.toURI().toString(), mime)))
    }

    private val WAKE_TOOL = ToolDefinition(
        "wake_me",
        "Dorme e acorda sozinho depois de alguns minutos para continuar a tarefa (esperar build, resposta de colega, horário). Depois de chamar, pare.",
        """{"type":"object","properties":{"minutes":{"type":"integer","minimum":1,"maximum":1440},"reason":{"type":"string","maxLength":200}},"required":["minutes"],"additionalProperties":false}""",
    )
}

/** Wakes a sleeping mission worker (scheduled by wake_me or a continuation). */
class MissionWakeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val groupId = inputData.getString("group") ?: return Result.success()
        val taskId = inputData.getString("task") ?: return Result.success()
        val job = withContext(Dispatchers.Main) {
            val repository = GroupRepository(applicationContext)
            GroupStore.ensureLoaded(repository)
            val task = GroupStore.get(groupId)?.tasks?.firstOrNull { it.id == taskId }
            if (task != null && task.autonomous && task.status != TaskStatus.DONE) MissionEngine.start(applicationContext, groupId, taskId) else null
        }
        // Keep this worker (and the process) alive while the session runs.
        kotlinx.coroutines.withTimeoutOrNull(9 * 60_000L) { job?.join() }
        return Result.success()
    }
}
