package dev.agentworkbench

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.agentworkbench.core.ChatMessage
import dev.agentworkbench.core.ChatMessageStatus
import dev.agentworkbench.core.ChatReducer
import dev.agentworkbench.core.ChatRole
import dev.agentworkbench.core.ChatState
import dev.agentworkbench.core.MessagePart
import dev.agentworkbench.core.ProviderEvent
import dev.agentworkbench.core.ProviderMessage
import dev.agentworkbench.core.ProviderRequest
import dev.agentworkbench.core.ProviderToolCall
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------------
// One-shot model calls (text only) for group speeches, summaries and check-ins.
// ---------------------------------------------------------------------------------------------

internal object PrismaModel {
    class Failure(message: String) : Exception(message)

    /**
     * Streams one text-only answer from the user's current provider. [onDelta] receives the text
     * so far. Throws [Failure] with a readable message when the provider is missing or fails.
     */
    suspend fun complete(
        context: Context,
        system: String,
        user: String,
        onDelta: (String) -> Unit = {},
        /** Who is talking: a prisma with its own model uses it. */
        bot: Bot? = null,
    ): String {
        val repository = ProviderSettingsRepository(context.applicationContext)
        val settings = repository.settingsForPrisma(bot, repository.load())
        val allowCleartext = TransportSecurityPreferences(context.applicationContext).allowLocalCleartext()
        val route = repository.routesForPrisma(bot, settings, allowCleartext).firstOrNull()
            ?: throw Failure(
                if (bot?.providerPreset.isNullOrBlank()) "Nenhum modelo configurado. Abra Perfil → Modelo e contas."
                else "O modelo de ${bot?.name} não está pronto — ${repository.prismaRouteProblem(settings, allowCleartext)}",
            )
        val request = ProviderRequest(
            sessionId = "prisma-" + UUID.randomUUID(),
            modelId = route.settings.modelId,
            messages = listOf(
                ProviderMessage(role = "system", parts = listOf(MessagePart.Text(system))),
                ProviderMessage(role = "user", parts = listOf(MessagePart.Text(user))),
            ),
            tools = emptyList(),
            reasoningEffort = route.settings.reasoningEffort,
        )
        val text = StringBuilder()
        var failure: String? = null
        var lastEmit = 0L
        route.provider.stream(request).collect { event ->
            when (event) {
                is ProviderEvent.TextDelta -> {
                    text.append(event.value)
                    val now = System.currentTimeMillis()
                    if (now - lastEmit > 70) {
                        lastEmit = now
                        onDelta(text.toString())
                    }
                }
                is ProviderEvent.RateLimits ->
                    parseRateLimits(event.headers).takeIf { it.isNotEmpty() }?.let { RateLimitMemory.record(route.settings.preset.name, it) }
                is ProviderEvent.Failed -> failure = event.safeMessage
                is ProviderEvent.Cancelled -> failure = event.reason
                else -> Unit
            }
        }
        failure?.let { throw Failure(it) }
        onDelta(text.toString())
        return text.toString().trim()
    }
}

// ---------------------------------------------------------------------------------------------
// Groups: rounds moderated by Maestro so a room full of prismas never turns into a mess.
// ---------------------------------------------------------------------------------------------

internal object GroupEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** groupId → (prisma id → its listening loop). Every member listens to the group at the same time. */
    private val loops = mutableMapOf<String, MutableMap<String, Job>>()

    /** Maestro's summary/decision jobs, per group. */
    private val notes = mutableMapOf<String, Job>()

    /** groupId → watchdog that wakes the team when board tasks stall. */
    private val watchdogs = mutableMapOf<String, Job>()
    private val typingFlow = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    private val approvalFlow = MutableStateFlow<GroupApproval?>(null)
    private val pendingApprovals = mutableListOf<GroupApproval>()

    /** Groups already told, in this process, that the app restarted. */
    private val restartNoticed = mutableSetOf<String>()

    /** [resumeAfterDeath] runs once per process (the app opening again later is not a restart). */
    private var resumedThisProcess = false

    /** Last time each notice text was posted per group (deduplication). */
    private val noticeTimes = mutableMapOf<String, Long>()

    /** Board nudges since the user last wrote, per group (the watchdog stops after a few). */
    private val nudges = mutableMapOf<String, Int>()

    private const val MAX_TOOL_ROUNDS = 12
    private const val SILENCE = "[silêncio]"
    private const val NEEDS_TOOLS = "[ferramentas]"
    private const val SEND_AS_IS = "ENVIAR"
    private const val IDLE_STOP_MS = 5 * 60_000L
    private const val RESUME_WINDOW_MS = 30 * 60_000L
    private const val MAX_NUDGES = 4
    private const val RESUME_PREFIX = "♻️"
    private const val INTERRUPTED = "Interrompido: o app fechou"
    private const val QUIET_LOG_SIZE = 40

    /** Notice prefixes the prismas must see in the transcript (environment, board, server). */
    private val NOTICE_PREFIXES = listOf("⚠️", "📋", "🌐", "🔄", "🖥️")

    /** groupId → prismas typing right now (several at once, like WhatsApp). */
    val speaking: StateFlow<Map<String, Set<String>>> = typingFlow.asStateFlow()

    /** The first tool request waiting for the user's OK (others queue behind it). */
    val approval: StateFlow<GroupApproval?> = approvalFlow.asStateFlow()

    fun isBusy(groupId: String): Boolean =
        typingFlow.value[groupId].orEmpty().isNotEmpty() || notes[groupId]?.isActive == true

    /** Any group listening or typing (keeps the background service alive). */
    fun anyActive(): Boolean = typingFlow.value.isNotEmpty() || loops.values.any { running -> running.values.any { it.isActive } }

    /** One line for the background notification. */
    fun workingSummary(): String {
        val typing = typingFlow.value.values.sumOf { it.size }
        val groups = loops.count { (_, running) -> running.values.any { it.isActive } }
        return when {
            typing > 0 -> "$typing Prisma(s) trabalhando"
            groups > 0 -> "$groups grupo(s) acompanhando"
            else -> ""
        }
    }

    /** The user posts in the group; every prisma receives it at once and decides by itself. */
    fun send(context: Context, groupId: String, text: String, kind: GroupMessageKind, replyToId: String?) {
        val repository = GroupRepository(context)
        val message = GroupMessage(authorId = GroupAuthors.USER, kind = kind, text = text.trim(), replyToId = replyToId)
        GroupStore.update(repository, groupId) { current ->
            current.withMessage(message).copy(
                pinnedNotice = if (kind == GroupMessageKind.ANNOUNCEMENT) message.text else current.pinnedNotice,
                tokensSinceUser = 0,
                lastUserAtMillis = System.currentTimeMillis(),
            )
        } ?: return
        nudges[groupId] = 0
        if (kind == GroupMessageKind.CONTEMPLATE) contemplate(context, groupId, message) else ensureLive(context, groupId)
    }

    /**
     * "Pensar juntos", inspired by Muse Spark's Contemplating mode: every member thinks about the
     * question alone and in parallel (no one sees the others, so no anchoring and no chorus), then
     * Maestro merges a single answer. The independent answers stay folded under it.
     */
    private fun contemplate(context: Context, groupId: String, question: GroupMessage) {
        if (notes[groupId]?.isActive == true) return
        val appContext = context.applicationContext
        notes[groupId] = scope.launch {
            val repository = GroupRepository(appContext)
            val bots = BotRepository(appContext)
            val group = GroupStore.get(groupId) ?: return@launch
            val admin = bots.ensureAdmin()
            val members = group.memberIds.mapNotNull(bots::byId).filterNot(PrismaAdmin::isAdmin)
            val names = (members + admin).associate { it.id to it.name }
            val placeholder = GroupMessage(authorId = admin.id, kind = GroupMessageKind.SUMMARY, text = "", streaming = true)
            GroupStore.update(repository, groupId, persist = false) { it.copy(messages = it.messages + placeholder) }
            members.forEach { setTyping(groupId, it.id, true) }
            val started = System.currentTimeMillis()
            val context14 = transcript(group, members, admin, window = 14, perMessage = 500) + boardBlock(group, names)
            try {
                val answers = kotlinx.coroutines.coroutineScope {
                    members.map { member ->
                        async {
                            val answer = runCatching {
                                PrismaModel.complete(
                                    appContext,
                                    member.systemPrompt() + (group.roles[member.id]?.let { "\nSua função no time: $it" } ?: "") +
                                        "\nVocê está pensando SOZINHO: os colegas não verão sua resposta até a síntese do Maestro. Seja original.",
                                    context14 + "\n\nPergunta do usuário para o time pensar junto:\n${question.text}\n\n" +
                                        "Dê sua melhor proposta em até 180 palavras, com o principal risco e o que você faria primeiro.",
                                    bot = member,
                                )
                            }.getOrElse { "(não conseguiu responder: ${it.message})" }
                            setTyping(groupId, member.id, false)
                            member to answer
                        }
                    }.awaitAll()
                }
                setTyping(groupId, admin.id, true)
                val independent = answers.joinToString("\n\n") { (member, answer) -> "${member.name}: ${answer.trim()}" }
                val synthesis = PrismaModel.complete(
                    appContext,
                    admin.systemPrompt() + "\nVocê sintetiza o pensamento independente do time numa única resposta.",
                    "Pergunta do usuário:\n${question.text}\n\nRespostas independentes:\n$independent\n\n" +
                        "Escreva UMA resposta final para o usuário: a melhor solução combinando os pontos fortes, onde houve " +
                        "divergência e por quê, e os próximos passos com responsáveis marcados (@). Seja direto. Depois disso, " +
                        "registre as tarefas no quadro se fizer sentido.",
                    bot = admin,
                )
                GroupStore.update(repository, groupId) { current ->
                    current.copy(messages = current.messages.filterNot { it.id == placeholder.id }).withMessage(
                        placeholder.copy(
                            text = synthesis.ifBlank { "Não consegui sintetizar." },
                            streaming = false,
                            reasoning = "Pensamento independente de cada um:\n\n$independent",
                            thinkingMillis = System.currentTimeMillis() - started,
                            createdAtMillis = System.currentTimeMillis(),
                        ),
                    )
                }
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                GroupStore.update(repository, groupId) { current ->
                    current.copy(messages = current.messages.filterNot { it.id == placeholder.id })
                        .withMessage(placeholder.copy(streaming = false, error = error.message ?: "Falha ao pensar junto."))
                }
            } finally {
                members.forEach { setTyping(groupId, it.id, false) }
                setTyping(groupId, admin.id, false)
            }
            // The team may pick up the plan from here.
            ensureLive(appContext, groupId)
        }
    }

    /** Wakes every member's listening loop (no-op for loops already running) and the watchdog. */
    fun ensureLive(context: Context, groupId: String) {
        val group = GroupStore.get(groupId) ?: return
        if (!group.autoReply) return
        val appContext = context.applicationContext
        noticeRestartOnce(appContext, group)
        val host = (context as? android.app.Activity)?.let { java.lang.ref.WeakReference(it) }
        val running = loops.getOrPut(groupId) { mutableMapOf() }
        (group.memberIds.filterNot { it == PrismaAdmin.ID } + PrismaAdmin.ID).forEach { botId ->
            if (running[botId]?.isActive != true) {
                running[botId] = scope.launch { listen(appContext, host, groupId, botId) }
            }
        }
        if (watchdogs[groupId]?.isActive != true) watchdogs[groupId] = scope.launch { watchdog(appContext, groupId) }
        // Keep working with the screen off / app in background.
        TeamKeepAliveService.ensure(appContext)
    }

    /** For routines: post the instruction, let the team talk, return once the group goes quiet. */
    suspend fun roundNow(context: Context, groupId: String, routineInstruction: String? = null) {
        val repository = GroupRepository(context)
        GroupStore.ensureLoaded(repository)
        routineInstruction?.let {
            GroupStore.update(repository, groupId) { group ->
                group.withMessage(GroupMessage(authorId = PrismaAdmin.ID, kind = GroupMessageKind.ANNOUNCEMENT, text = "Rotina: $it"))
            }
        }
        ensureLive(context, groupId)
        var quietSince = System.currentTimeMillis()
        val deadline = System.currentTimeMillis() + 9 * 60_000L
        while (System.currentTimeMillis() < deadline) {
            delay(2_000)
            if (isBusy(groupId)) quietSince = System.currentTimeMillis()
            if (System.currentTimeMillis() - quietSince > 45_000) break
        }
    }

    fun summarize(context: Context, groupId: String) = moderatorNote(context, groupId, decision = false)

    fun decide(context: Context, groupId: String) = moderatorNote(context, groupId, decision = true)

    /** Stops everyone in the group (work in progress is kept); the next message wakes them again. */
    fun stop(groupId: String) {
        loops.remove(groupId)?.values?.forEach { it.cancel() }
        notes.remove(groupId)?.cancel()
        watchdogs.remove(groupId)?.cancel()
        typingFlow.update { it - groupId }
        pendingApprovals.filter { it.groupId == groupId }.forEach { it.answer(false) }
        pendingApprovals.removeAll { it.groupId == groupId }
        approvalFlow.value = pendingApprovals.firstOrNull()
    }

    fun answerApproval(allow: Boolean) {
        val first = pendingApprovals.firstOrNull() ?: return
        first.answer(allow)
        pendingApprovals.remove(first)
        approvalFlow.value = pendingApprovals.firstOrNull()
    }

    /**
     * A system notice in the group ("⚠️ a tela paralela caiu"), so nobody keeps working on a
     * stale picture of the environment. [groupId] null = every group active in the last 6 h.
     * The same text is not repeated within 3 minutes.
     */
    fun environmentNotice(context: Context, text: String, groupId: String? = null) {
        val repository = GroupRepository(context)
        GroupStore.ensureLoaded(repository)
        val now = System.currentTimeMillis()
        val targets = if (groupId != null) listOfNotNull(GroupStore.get(groupId)) else GroupStore.groups.value.filter { now - it.updatedAtMillis < 6 * 3_600_000L }
        targets.forEach { group ->
            val key = group.id + "|" + text
            if (now - (noticeTimes[key] ?: 0L) < 3 * 60_000L) return@forEach
            noticeTimes[key] = now
            GroupStore.update(repository, group.id) {
                it.withMessage(GroupMessage(authorId = GroupAuthors.SYSTEM, kind = GroupMessageKind.SYSTEM, text = text))
            }
        }
    }

    /**
     * After the process died (the system killed it, low memory, an app it depended on crashed):
     * bring back every group that was working recently, without waiting for the user to open it.
     * Called when the app opens and when Android restarts the keep-alive service by itself.
     */
    fun resumeAfterDeath(context: Context) {
        if (resumedThisProcess) return
        resumedThisProcess = true
        val repository = GroupRepository(context)
        GroupStore.ensureLoaded(repository)
        val now = System.currentTimeMillis()
        GroupStore.groups.value
            .filter { it.autoReply && !it.archived && (now - it.updatedAtMillis < RESUME_WINDOW_MS || interruptedIn(it).isNotEmpty()) }
            .forEach { runCatching { ensureLive(context, it.id) } }
    }

    /** Prismas whose turn the dead process cut in the middle (their bubble was saved as interrupted). */
    private fun interruptedIn(group: PrismaGroup): Set<String> {
        val since = group.messages.indexOfLast {
            it.authorId == GroupAuthors.USER || (it.kind == GroupMessageKind.SYSTEM && it.text.startsWith(RESUME_PREFIX))
        }
        val now = System.currentTimeMillis()
        return group.messages.drop(since + 1)
            .filter { it.error?.startsWith(INTERRUPTED) == true && now - it.createdAtMillis < 2 * 3_600_000L }
            .map { it.authorId }
            .toSet()
    }

    /** After the app process restarts, tell active groups that screens, servers and processes are gone. */
    private fun noticeRestartOnce(context: Context, group: PrismaGroup) {
        if (!restartNoticed.add(group.id)) return
        val cut = interruptedIn(group)
        if (cut.isNotEmpty()) {
            val bots = BotRepository(context)
            val everyone = (group.memberIds + PrismaAdmin.ID).distinct().mapNotNull(bots::byId)
            val handles = Mentions.handles(everyone)
            val tags = cut.mapNotNull { handles[it] }.joinToString(" ") { "@$it" }
            if (tags.isNotBlank()) {
                environmentNotice(
                    context,
                    "$RESUME_PREFIX O app reiniciou e cortou o trabalho no meio. $tags: continuem de onde pararam — as ferramentas " +
                        "listadas no seu último balão já rodaram; telas paralelas, servidores e processos abertos antes foram " +
                        "encerrados, então confiram o estado antes de seguir. Não recomecem do zero.",
                    group.id,
                )
                return
            }
        }
        val lastPrisma = group.messages.lastOrNull { it.authorId != GroupAuthors.USER && it.authorId != GroupAuthors.SYSTEM }
        if (lastPrisma == null || System.currentTimeMillis() - lastPrisma.createdAtMillis > 6 * 3_600_000L) return
        if (!group.messages.any { it.activity.isNotEmpty() }) return
        environmentNotice(
            context,
            "🔄 O app foi reiniciado: a tela paralela, servidores e processos iniciados antes foram encerrados. Confiram o estado com as ferramentas antes de continuar; não presumam que nada continua aberto.",
            group.id,
        )
    }

    /**
     * One prisma listening to the group. A message @-addressed to someone else costs it nothing
     * (it just keeps listening); otherwise it waits a human moment — longer when it was not
     * called, so whoever was called answers first and the others can see it — and decides.
     * Private messages from other prismas also wake it.
     */
    private suspend fun listen(context: Context, host: java.lang.ref.WeakReference<android.app.Activity>?, groupId: String, botId: String) {
        val repository = GroupRepository(context)
        val bots = BotRepository(context)
        PrismaDms.ensureLoaded(context)
        var handled: String? = null
        try {
            while (true) {
                withTimeoutOrNull(IDLE_STOP_MS) {
                    kotlinx.coroutines.flow.combine(GroupStore.groups, PrismaDms.all) { list, _ ->
                        val trigger = list.firstOrNull { it.id == groupId }?.let { triggerFor(it, botId) }?.id
                        (trigger != null && trigger != handled) || PrismaDms.unreadCount(botId) > 0
                    }.first { it }
                } ?: break
                val before = GroupStore.get(groupId) ?: break
                if (!before.autoReply || (botId != PrismaAdmin.ID && botId !in before.memberIds)) break
                val bot = bots.byId(botId) ?: break
                val admin = bots.ensureAdmin()
                val members = before.memberIds.mapNotNull(bots::byId).filterNot(PrismaAdmin::isAdmin)
                val latest = triggerFor(before, botId)
                val hasDm = PrismaDms.unreadCount(botId) > 0
                val called = latest != null && bot.id in Mentions.mentioned(latest.text, members + admin)
                val toOthers = latest != null && !called && !mentionsEveryone(latest.text) &&
                    Mentions.mentioned(latest.text, members + admin).isNotEmpty()
                if (latest != null && latest.id != handled && toOthers && !hasDm) {
                    // Addressed to someone else: keep listening without spending tokens.
                    handled = latest.id
                    continue
                }
                delay(if (called || hasDm) Random.nextLong(900, 2_200) else Random.nextLong(3_500, 7_500) + prismaRun(before).coerceAtMost(20) * 400L)
                val group = GroupStore.get(groupId) ?: break
                val now = triggerFor(group, botId)
                handled = now?.id ?: handled
                val mustAnswer = called || hasDm || (now?.authorId == GroupAuthors.USER && members.size == 1)
                if (speak(context, host?.get(), repository, group, bot, members, admin, mustAnswer)) {
                    compactIfNeeded(context, repository, groupId)
                }
            }
        } finally {
            loops[groupId]?.let { running -> if (running[botId] === kotlinx.coroutines.currentCoroutineContext()[Job]) running.remove(botId) }
            setTyping(groupId, botId, false)
        }
    }

    /**
     * The newest finished message that may get a reaction: messages from others and board
     * nudges ("📋"), never this prisma's own and never plain system lines.
     */
    private fun triggerFor(group: PrismaGroup, botId: String): GroupMessage? =
        group.messages.lastOrNull {
            !it.streaming && it.text.isNotBlank() && it.kind != GroupMessageKind.CONTEMPLATE &&
                (it.kind != GroupMessageKind.SYSTEM || it.text.startsWith("📋") || it.text.startsWith(RESUME_PREFIX))
        }
            ?.takeIf { it.authorId != botId }

    private fun mentionsEveryone(text: String): Boolean {
        val normalized = Mentions.normalize(text)
        return listOf("@todos", "@time", "@pessoal", "@geral").any { it in normalized }
    }

    private fun prismaRun(group: PrismaGroup): Int =
        group.messages.asReversed().filter { !it.streaming && it.kind != GroupMessageKind.SYSTEM }
            .takeWhile { it.authorId != GroupAuthors.USER }.size

    private fun setTyping(groupId: String, botId: String, typing: Boolean) {
        typingFlow.update { map ->
            val current = map[groupId].orEmpty()
            val next = if (typing) current + botId else current - botId
            if (next.isEmpty()) map - groupId else map + (groupId to next)
        }
    }

    /**
     * Anti-paralysis: when the room has been quiet for a while but the board still has open
     * tasks, post a short board reminder (which wakes the owners). A few times per user message.
     */
    private suspend fun watchdog(context: Context, groupId: String) {
        val repository = GroupRepository(context)
        val bots = BotRepository(context)
        while (true) {
            delay(60_000)
            val group = GroupStore.get(groupId) ?: return
            if (!group.autoReply) return
            val open = group.tasks.filter { it.status != TaskStatus.DONE && it.ownerId != GroupAuthors.USER }
            val listening = loops[groupId]?.values?.any { it.isActive } == true
            if (open.isEmpty()) {
                if (!listening) return
                continue
            }
            if (isBusy(groupId)) continue
            val last = group.messages.lastOrNull { !it.streaming }?.createdAtMillis ?: 0L
            val idleMin = (System.currentTimeMillis() - last) / 60_000
            if (idleMin < 3) continue
            val used = nudges[groupId] ?: 0
            if (used >= MAX_NUDGES) {
                if (!listening) return
                continue
            }
            nudges[groupId] = used + 1
            val names = bots.list().associate { it.id to it.name }
            val lines = open.take(6).joinToString("; ") { task ->
                "${task.title} — ${names[task.ownerId] ?: "sem dono"} (${task.status.label})"
            }
            val text = when {
                used == 0 -> "📋 Quadro parado há $idleMin min com tarefas abertas: $lines. Responsáveis: continuem agora, " +
                    "peçam ajuda a um colega ou registrem o bloqueio no quadro. Ninguém precisa esperar autorização para a própria tarefa."
                used < MAX_NUDGES - 1 -> "📋 @todos: o time continua parado em: $lines. Quem estiver livre, assuma ou destrave por " +
                    "OUTRO caminho (outra ferramenta, web_serve, navegador interno, pedir arquivo, dividir a tarefa). Esperar não é opção."
                else -> "📋 O time travou em: $lines. Chamei o usuário."
            }
            GroupStore.update(repository, groupId) {
                it.withMessage(GroupMessage(authorId = GroupAuthors.SYSTEM, kind = GroupMessageKind.SYSTEM, text = text))
            }
            if (used >= MAX_NUDGES - 1) {
                PrismaNotifier.notify(context, "O time travou em ${group.name}", "Tarefas paradas: $lines", groupId = groupId, kind = PrismaNotifications.Kind.GROUP)
            }
            ensureLive(context, groupId)
        }
    }

    /**
     * One prisma reads the group and maybe answers, with the full Refrator environment. Cheap
     * first look (recent messages only), full context only when it speaks, new messages fed in
     * while it works, a last check against what arrived meanwhile (no chorus), reasoning kept,
     * and partial work saved on errors. Returns false when it stayed quiet.
     */
    private suspend fun speak(
        context: Context,
        host: android.app.Activity?,
        repository: GroupRepository,
        group: PrismaGroup,
        speaker: Bot,
        members: List<Bot>,
        admin: Bot,
        mustAnswer: Boolean,
    ): Boolean {
        val settingsRepository = ProviderSettingsRepository(context)
        val settings = settingsRepository.settingsForPrisma(speaker, settingsRepository.load())
        val allowCleartext = TransportSecurityPreferences(context).allowLocalCleartext()
        val route = settingsRepository.routesForPrisma(speaker, settings, allowCleartext).firstOrNull()
        val placeholder = GroupMessage(authorId = speaker.id, text = "", streaming = true)
        var typing = false
        fun startTyping() {
            if (typing) return
            typing = true
            GroupStore.update(repository, group.id, persist = false) { it.copy(messages = it.messages + placeholder) }
            setTyping(group.id, speaker.id, true)
        }
        fun finish(message: GroupMessage?) {
            GroupStore.update(repository, group.id) { current ->
                val others = current.messages.filterNot { it.id == placeholder.id }
                if (message == null) current.copy(messages = others) else current.copy(messages = others).withMessage(message)
            }
            setTyping(group.id, speaker.id, false)
        }
        if (route == null) {
            val problem = if (speaker.providerPreset.isNullOrBlank()) "Nenhum modelo configurado. Abra Perfil → Modelo e contas."
            else "O modelo escolhido para ${speaker.name} não está pronto — ${settingsRepository.prismaRouteProblem(settings, allowCleartext)}"
            finish(placeholder.copy(streaming = false, error = problem))
            return true
        }
        val toolbox = AgentToolbox(
            appContext = host ?: context,
            profile = DistributionBindings.profile(),
            executionMode = settings.executionMode,
            workspaceRoot = java.io.File(context.filesDir, "workspace").apply { mkdirs() },
            activity = host,
            conversationId = "grupo-${group.id}-${speaker.id}",
            callerBotId = speaker.id,
        )
        // Reading its own group is pointless (it is in the context).
        val tools = toolbox.definitions.filterNot { it.name == "ask_user" || it.name == "group_read" }
        val system = environmentFor(speaker, members, admin, group, tools.size) +
            runCatching { groupSocietyMemory(context, speaker, group) }.getOrDefault("") + pluginsPrompt(context)
        val latestGroup = GroupStore.get(group.id) ?: group
        val inbox = PrismaDms.takeUnread(context, speaker.id)
        val names = (members + admin).associate { it.id to it.name }
        val shared = boardBlock(latestGroup, names) + teamNotebookBlock(context, group.id) + dmBlock(inbox, names) +
            "\n" + situation(latestGroup) + AccountUsage.awareness(context, route.settings)
        var lastSeenId = latestGroup.messages.lastOrNull { !it.streaming }?.id
        val activity = mutableListOf<String>()
        val reasoning = StringBuilder()
        val startedAt = System.currentTimeMillis()
        var thinkingMillis = 0L
        var finalText = ""

        /** One model answer; returns its text and tool calls. "digitando…" starts on real text. */
        suspend fun pass(ledger: List<ProviderMessage>, withTools: Boolean, silent: Boolean = false): Pair<String, List<AgentToolInvocation>> {
            val toolNames = linkedMapOf<String, String>()
            val args = linkedMapOf<String, StringBuilder>()
            val text = StringBuilder()
            var failure: String? = null
            route.provider.stream(
                ProviderRequest(
                    sessionId = "group-${group.id}-${speaker.id}",
                    modelId = route.settings.modelId,
                    messages = ledger,
                    tools = if (withTools) tools else emptyList(),
                    reasoningEffort = route.settings.reasoningEffort,
                ),
            ).collect { event ->
                when (event) {
                    is ProviderEvent.ReasoningDelta -> if (!silent) reasoning.append(event.value)
                    is ProviderEvent.TextDelta -> {
                        text.append(event.value)
                        val head = text.trimStart()
                        if (!silent && head.length >= 3 && !head.startsWith("[") && !head.startsWith(SEND_AS_IS)) {
                            if (thinkingMillis == 0L) thinkingMillis = System.currentTimeMillis() - startedAt
                            startTyping()
                        }
                    }
                    is ProviderEvent.ToolCallStarted -> {
                        if (thinkingMillis == 0L) thinkingMillis = System.currentTimeMillis() - startedAt
                        startTyping()
                        if (event.toolName.isNotBlank()) toolNames[event.callId] = event.toolName
                        args.getOrPut(event.callId) { StringBuilder() }
                    }
                    is ProviderEvent.ToolArgumentsDelta -> args.getOrPut(event.callId) { StringBuilder() }.append(event.jsonDelta)
                    is ProviderEvent.RateLimits ->
                        parseRateLimits(event.headers).takeIf { it.isNotEmpty() }?.let { RateLimitMemory.record(route.settings.preset.name, it) }
                    is ProviderEvent.Usage -> addUsage(repository, group.id, event.inputTokens + event.outputTokens)
                    is ProviderEvent.Failed -> failure = event.safeMessage
                    is ProviderEvent.Cancelled -> failure = event.reason
                    else -> Unit
                }
            }
            failure?.let { throw PrismaModel.Failure(it) }
            val invocations = args.keys.map { id -> AgentToolInvocation(id, toolNames[id].orEmpty(), args[id].toString().ifBlank { "{}" }) }
                .filter { it.toolName.isNotBlank() }
            return text.toString().trim() to invocations
        }

        /** Finished messages from others that arrived after [lastSeenId], as transcript lines. */
        fun arrivedSinceSeen(): String {
            val current = GroupStore.get(group.id) ?: return ""
            val index = current.messages.indexOfFirst { it.id == lastSeenId }
            val fresh = current.messages.drop(index + 1).filter { !it.streaming && it.authorId != speaker.id && it.text.isNotBlank() }
            current.messages.lastOrNull { !it.streaming }?.let { lastSeenId = it.id }
            return fresh.joinToString("\n") { "[${nameOf(it.authorId, names)}]: ${it.text.trim().take(700)}" }
        }

        fun partial(error: String?) = placeholder.copy(
            text = finalText.replace(SILENCE, "").replace(NEEDS_TOOLS, "").trim().ifBlank { if (activity.isNotEmpty()) "(trabalho interrompido; veja as ferramentas usadas)" else "" },
            streaming = false,
            activity = activity.toList(),
            reasoning = reasoning.toString().trim(),
            thinkingMillis = thinkingMillis,
            error = error,
            createdAtMillis = System.currentTimeMillis(),
        )

        try {
            // 1) Cheap look: only the recent messages. Answer, stay quiet, or ask for tools.
            val ask = if (mustAnswer) {
                "${speaker.name}, falaram com você (ou chegou mensagem privada): responda agora."
            } else {
                "${speaker.name}, chegou mensagem nova. Responda só se tiver algo NOVO e útil (não repita nem concorde por concordar; " +
                    "se a mensagem é para outra pessoa, deixe ela responder). Se não, responda exatamente $SILENCE e, " +
                    "na mesma linha, o motivo em poucas palavras."
            }
            val look = listOf(
                ProviderMessage(role = "system", parts = listOf(MessagePart.Text(system))),
                ProviderMessage(
                    role = "user",
                    parts = listOf(
                        MessagePart.Text(
                            transcript(latestGroup, members, admin, window = 14, perMessage = 600) + shared + "\n\n" + ask +
                                " Se para fazer a sua parte você precisar de ferramentas (arquivos, terminal, web, tela paralela, quadro, " +
                                "caderno, mensagem privada…), responda exatamente $NEEDS_TOOLS.",
                        ),
                    ),
                ),
            )
            finalText = pass(look, withTools = false).first
            if (finalText.trim().startsWith(NEEDS_TOOLS, ignoreCase = true)) {
                // 2) Full environment and full conversation, same tools as a normal chat.
                startTyping()
                var ledger = listOf(
                    look[0],
                    ProviderMessage(
                        role = "user",
                        parts = listOf(
                            MessagePart.Text(
                                transcript(latestGroup, members, admin) + shared + "\n\n${speaker.name}, planeje e use as ferramentas " +
                                    "que precisar para fazer a sua parte, sem esperar autorização de colegas. Mantenha o quadro atualizado " +
                                    "(task_board). Depois escreva sua mensagem para o grupo com o que fez, o resultado e o próximo passo.",
                            ),
                        ),
                    ),
                )
                finalText = ""
                for (round in 0..MAX_TOOL_ROUNDS) {
                    val (text, invocations) = pass(ledger, withTools = round < MAX_TOOL_ROUNDS)
                    finalText = text
                    if (invocations.isEmpty()) break
                    ledger = ledger + ProviderMessage(
                        role = "assistant",
                        parts = text.takeIf(String::isNotBlank)?.let { listOf(MessagePart.Text(it)) }.orEmpty(),
                        toolCalls = invocations.map { ProviderToolCall(it.callId, it.toolName, it.argumentsJson) },
                    )
                    for (invocation in invocations) {
                        activity += toolActivityHeadline(ToolActivity(invocation.callId, invocation.toolName, "", ToolActivityStatus.RUNNING))
                        val running = activity.toList()
                        edit(repository, group.id, placeholder.id) { it.copy(activity = running) }
                        val result = runTool(context, toolbox, group, speaker, invocation)
                        activity[activity.lastIndex] = toolActivityHeadline(
                            ToolActivity(invocation.callId, invocation.toolName, "", if (result.isError) ToolActivityStatus.FAILED else ToolActivityStatus.COMPLETE),
                        )
                        val done = activity.toList()
                        edit(repository, group.id, placeholder.id) { it.copy(activity = done) }
                        ledger = ledger + result.providerMessage()
                        imageMessage(context, invocation.toolName, result, route.settings)?.let { ledger = ledger + it }
                    }
                    // The group keeps talking while it works: feed in what arrived (and new DMs).
                    val arrived = arrivedSinceSeen()
                    val dms = PrismaDms.takeUnread(context, speaker.id)
                    if (arrived.isNotBlank() || dms.isNotEmpty()) {
                        ledger = ledger + ProviderMessage(
                            role = "user",
                            parts = listOf(
                                MessagePart.Text(
                                    buildString {
                                        if (arrived.isNotBlank()) append("[Chegou no grupo enquanto você trabalhava]\n").append(arrived).append('\n')
                                        if (dms.isNotEmpty()) append(dmBlock(dms, names))
                                        append("Leve isso em conta e continue.")
                                    },
                                ),
                            ),
                        )
                    }
                }
            }
            // 3) Anti-chorus: if others wrote meanwhile, check the draft still adds something.
            val draft = finalText.replace(NEEDS_TOOLS, "", ignoreCase = true).trim()
            if (draft.isNotBlank() && !draft.startsWith(SILENCE, ignoreCase = true)) {
                val arrived = arrivedSinceSeen()
                if (arrived.isNotBlank()) {
                    val verdict = pass(
                        listOf(
                            ProviderMessage(role = "system", parts = listOf(MessagePart.Text(speaker.systemPrompt() + "\nVocê está num grupo de trabalho e evita mensagens repetidas."))),
                            ProviderMessage(
                                role = "user",
                                parts = listOf(
                                    MessagePart.Text(
                                        "Você ia mandar no grupo:\n\"\"\"\n${draft.take(2_000)}\n\"\"\"\nEnquanto você escrevia, chegou:\n$arrived\n\n" +
                                            "Se sua mensagem ficou redundante, responda exatamente $SILENCE e o motivo. Se ainda acrescenta algo, " +
                                            "responda exatamente $SEND_AS_IS. Ou reescreva mantendo só o que é novo.",
                                    ),
                                ),
                            ),
                        ),
                        withTools = false,
                        silent = true,
                    ).first.trim()
                    finalText = when {
                        verdict.startsWith(SEND_AS_IS, ignoreCase = true) -> draft
                        verdict.startsWith(SILENCE, ignoreCase = true) -> verdict
                        verdict.isNotBlank() -> verdict
                        else -> draft
                    }
                }
            }
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) {
                // Stopped: keep whatever was already done.
                if (typing && (finalText.isNotBlank() || activity.isNotEmpty())) finish(partial("Interrompido.")) else if (typing) finish(null)
                throw error
            }
            startTyping()
            finish(partial(error.message ?: "Falha ao responder."))
            return true
        }
        val trimmed = finalText.trim()
        val quiet = trimmed.startsWith(SILENCE, ignoreCase = true) || trimmed.isBlank()
        if (quiet && activity.isEmpty()) {
            if (typing) finish(null)
            val why = trimmed.removePrefix(SILENCE).trim(' ', '-', ':', '—', '.').ifBlank {
                reasoning.toString().trim().takeLast(160).ifBlank { "nada novo a acrescentar" }
            }
            GroupStore.update(repository, group.id, persist = false) {
                it.copy(quietLog = (it.quietLog + QuietNote(speaker.id, why.take(200))).takeLast(QUIET_LOG_SIZE))
            }
            return false
        }
        val cleaned = trimmed.replace(SILENCE, "", ignoreCase = true).replace(NEEDS_TOOLS, "", ignoreCase = true).trim()
        startTyping()
        delay((700L + cleaned.length * 14L).coerceAtMost(6_000L))
        finish(partial(null).copy(text = cleaned.ifBlank { "Feito: " + activity.joinToString(", ") }))
        if (Mentions.mentionsUser(cleaned)) {
            PrismaNotifications.groupMention(context, group.id, speaker, cleaned)
        }
        return true
    }

    private suspend fun runTool(context: Context, toolbox: AgentToolbox, group: PrismaGroup, speaker: Bot, invocation: AgentToolInvocation): AgentToolResult {
        val result = when (val preparation = toolbox.prepare(invocation)) {
            is AgentToolPreparation.Rejected -> preparation.result
            is AgentToolPreparation.Ready -> {
                val prepared = preparation.prepared
                val approved = when {
                    !prepared.requiresApproval -> true
                    // More autonomy: routine tools run by themselves; sensitive ones still ask.
                    MissionEngine.autoApproved(GroupStore.get(group.id) ?: group, invocation.toolName, prepared.request.effect) -> true
                    else -> requestApproval(context, group, speaker, prepared.request.summary)
                }
                toolbox.execute(prepared, approved)
            }
        }
        environmentFromTool(context, group, speaker, invocation.toolName, result)
        return result
    }

    /** Asks the user (dialog + notification) to allow a tool; waits up to 10 minutes. */
    suspend fun requestApproval(context: Context, group: PrismaGroup, speaker: Bot, summary: String): Boolean {
        val request = GroupApproval(group.id, group.name, speaker.name, summary)
        pendingApprovals += request
        approvalFlow.value = pendingApprovals.firstOrNull()
        PrismaNotifier.notify(
            context,
            "${speaker.name} pede permissão",
            "${group.name}: $summary",
            groupId = group.id,
            kind = PrismaNotifications.Kind.PERMISSION,
        )
        val allowed = kotlinx.coroutines.withTimeoutOrNull(10 * 60_000L) { request.decision.await() } ?: false
        pendingApprovals.remove(request)
        approvalFlow.value = pendingApprovals.firstOrNull()
        return allowed
    }

    /** Turns tool results that change or break the shared environment into notices for everyone. */
    /** When a tool produced an image (screenshot, look_at_image), show it to vision-capable models. */
    private fun imageMessage(context: Context, tool: String, result: AgentToolResult, settings: ProviderSettings): ProviderMessage? {
        if (result.isError || !settings.preset.capabilities.images) return null
        if (!(tool.contains("screenshot") || tool == "capture_app_screen" || tool == "look_at_image" || tool == "shadow_actions")) return null
        val workspace = java.io.File(context.filesDir, "workspace").canonicalFile
        val file = Regex("[\\w./-]+\\.(png|jpe?g|webp)", RegexOption.IGNORE_CASE).findAll(result.payload).map { it.value }.distinct()
            .mapNotNull { path ->
                listOf(java.io.File(workspace, path.removePrefix("/")), java.io.File(path)).firstOrNull {
                    it.isFile && it.length() in 1..(8L * 1024 * 1024) && it.canonicalPath.startsWith(context.filesDir.canonicalPath)
                }
            }.firstOrNull() ?: return null
        val mime = when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> "image/png"
        }
        return ProviderMessage(
            role = "user",
            parts = listOf(
                MessagePart.Text("Imagem gerada por $tool (${file.name}). Olhe e avalie de verdade:"),
                MessagePart.ImageReference(file.toURI().toString(), mime),
            ),
        )
    }

    private fun environmentFromTool(context: Context, group: PrismaGroup, speaker: Bot, tool: String, result: AgentToolResult) {
        val payload = result.payload
        val notice = when {
            !result.isError && tool == "shadow_display_start" -> "🖥️ ${speaker.name} ligou a tela paralela. Quem for usar, combine antes."
            !result.isError && tool == "shadow_display_stop" -> "🖥️ ${speaker.name} desligou a tela paralela."
            result.isError && listOf("Shizuku", "shizuku", "display", "Display").any { it in payload } ->
                "⚠️ ${speaker.name}: a tela paralela/Shizuku não está disponível (${payload.lineSequence().firstOrNull().orEmpty().take(120)})."
            result.isError && listOf("Connection refused", "ECONNREFUSED", "Errno 111").any { it in payload } ->
                "⚠️ ${speaker.name}: um servidor local recusou conexão. Para servir arquivos de forma estável, use web_serve."
            result.isError && ("limite" in payload.lowercase() || "rate limit" in payload.lowercase()) ->
                "⚠️ Limite de uso atingido em uma ferramenta de ${speaker.name}."
            else -> null
        } ?: return
        environmentNotice(context, notice, group.id)
    }

    private fun moderatorNote(context: Context, groupId: String, decision: Boolean) {
        if (notes[groupId]?.isActive == true) return
        val appContext = context.applicationContext
        notes[groupId] = scope.launch {
            val repository = GroupRepository(appContext)
            val bots = BotRepository(appContext)
            GroupStore.ensureLoaded(repository)
            val group = GroupStore.get(groupId) ?: return@launch
            val admin = bots.ensureAdmin()
            val members = group.memberIds.mapNotNull(bots::byId).filterNot(PrismaAdmin::isAdmin)
            val placeholder = GroupMessage(
                authorId = admin.id,
                kind = if (decision) GroupMessageKind.DECISION else GroupMessageKind.SUMMARY,
                text = "",
                streaming = true,
            )
            GroupStore.update(repository, groupId, persist = false) { it.copy(messages = it.messages + placeholder) }
            setTyping(groupId, admin.id, true)
            val ask = if (decision) {
                "Registre o que o grupo decidiu em 1 a 3 frases, começando direto pela decisão. Sem consenso, registre a opção mais apoiada e o que ficou em aberto."
            } else {
                "Resuma a conversa recente em até 6 linhas: o que cada um trouxe, o que ficou combinado, quem ficou de fazer o quê e o que está pendente."
            }
            fun finish(message: GroupMessage) {
                GroupStore.update(repository, groupId) { current ->
                    current.copy(messages = current.messages.filterNot { it.id == placeholder.id }).withMessage(message)
                }
                setTyping(groupId, admin.id, false)
            }
            try {
                val names = (members + admin).associate { it.id to it.name }
                val text = PrismaModel.complete(
                    appContext,
                    admin.systemPrompt() + "\n\nVocê acompanha o grupo \"${group.name}\" da Sociedade Prisma. Seja neutro e objetivo.",
                    transcript(group, members, admin) + boardBlock(group, names) + "\n\n" + ask,
                    bot = admin,
                )
                finish(placeholder.copy(text = text.ifBlank { "Nada a registrar ainda." }, streaming = false, createdAtMillis = System.currentTimeMillis()))
                if (decision) {
                    GroupStore.update(repository, groupId) { it.copy(decisions = it.decisions + text) }
                    PrismaNotifier.notify(appContext, "Decisão em ${group.name}", text, groupId = groupId, kind = PrismaNotifications.Kind.GROUP)
                }
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) {
                    GroupStore.update(repository, groupId) { current -> current.copy(messages = current.messages.filterNot { it.id == placeholder.id }) }
                    setTyping(groupId, admin.id, false)
                    throw error
                }
                finish(placeholder.copy(streaming = false, error = error.message ?: "Falha."))
            }
        }
    }

    private fun environmentFor(speaker: Bot, members: List<Bot>, admin: Bot, group: PrismaGroup, toolCount: Int): String = buildString {
        append("<environment>\nRefrator no Android. $toolCount ferramentas estão disponíveis como numa conversa normal. ")
        append("Afirme ações somente com resultado de ferramenta; não invente saída, acesso ou identidade de provider.\n</environment>\n\n")
        append(speaker.systemPrompt())
        append("\n\n<grupo nome=\"").append(group.name.replace("\"", "'")).append("\">\n")
        val everyone = listOf(admin) + members
        val handles = Mentions.handles(everyone)
        append("Você está num grupo de WhatsApp da Sociedade Prisma que funciona como um time de trabalho, com o usuário (o dono, ")
        append("que delega as funções), os Prismas do time e o ").append(admin.name).append(" (administrador geral, coordena o time).\n")
        append("Participantes e como marcar cada um (use exatamente estes @): ").append(Mentions.legend(everyone, handles, group.roles)).append('\n')
        append("VOCÊ É ").append(speaker.name).append(" (@").append(handles[speaker.id] ?: "").append("). Fale sempre em primeira pessoa; nunca se marque nem fale de si na terceira pessoa.\n")
        if (PrismaAdmin.isAdmin(speaker)) {
            append("Como administrador, você também conversa: responda quando te marcarem (@maestro) e entre quando o time precisar ")
            append("de coordenação: dividir tarefas no quadro, destravar um impasse, lembrar o objetivo ou chamar alguém. ")
            append("Não peça pausa ao time e não repita o que os outros disseram; deixe o time trabalhar.\n")
            append("Quando o usuário der um objetivo de trabalho: discutam o essencial, então planeje no quadro (task_board add com dono, ")
            append("criteria = critério de pronto) e dispare o trabalho em segundo plano (task_board action=start). Cada dono trabalha ")
            append("sozinho, em paralelo, na própria tela e pasta, por horas se preciso, e avisa no grupo quando terminar ou travar.\n")
        } else {
            group.roles[speaker.id]?.takeIf(String::isNotBlank)?.let { append("Sua função neste time: ").append(it).append('\n') }
        }
        if (group.topic.isNotBlank()) append("Objetivo do grupo: ").append(group.topic.trim()).append('\n')
        group.pinnedNotice?.let { append("Aviso fixado do usuário: ").append(it).append('\n') }
        append("Como o grupo funciona:\n")
        append("- Todos recebem as mensagens na hora; mensagens novas também chegam enquanto você trabalha. Antes de enviar, você confere o que chegou.\n")
        append("- Vocês são livres e se coordenam sozinhos, como uma equipe de verdade: planejem, dividam tarefas no quadro (task_board), ")
        append("debatam, discordem quando fizer sentido e entreguem resultados.\n")
        append("- Sem coro: se outro já disse, não repita nem mande só \"concordo\", \"entendido\" ou \"obrigado\". Mensagem para outra pessoa é dela: não responda por ela.\n")
        append("- Sem paralisia: ninguém espera indefinidamente. Se algo te bloqueia, tente resolver (outra ferramenta, outro caminho), ")
        append("peça ajuda a um colega por mensagem privada (prisma_dm) ou registre o bloqueio no quadro e passe para outra coisa útil. ")
        append("Não peça permissão a colegas para fazer a sua própria tarefa e não peça pausa ao time.\n")
        append("- Ambiente: avisos do sistema (⚠️, 🔄, 🖥️) dizem o que mudou de verdade (app reiniciado, tela paralela, servidor). Confie neles ")
        append("e confira com ferramentas antes de presumir que algo continua aberto. Para servir páginas/jogos de forma estável, use web_serve.\n")
        append("- web_serve é local (127.0.0.1), não expõe nada à rede e continua no ar com a tela desligada: é o jeito certo de abrir ")
        append("páginas e jogos no Chrome. Restrições antigas do tipo \"sem servidor\" não se aplicam a ele.\n")
        append("- Você tem um COMPUTADOR PRÓPRIO: computer_shell (Ubuntu só seu, com estado; instale o que precisar) e um navegador ")
        append("só seu com logins salvos (web_open, web_read, web_act com vários passos numa chamada, web_login para o usuário entrar ")
        append("numa conta sem você ver a senha). Para sites, prefira web_* (texto numerado, rápido) a screenshots.\n")
        append("- Seja rápido como uma pessoa usando o celular: se precisar VER uma página, use o navegador interno (browser_open, browser_snapshot, ")
        append("browser_click, browser_type, browser_screenshot), que é rápido e separado do Chrome do usuário. Apps vão na SUA tela paralela ")
        append("com shadow_actions (vários toques/textos numa chamada, com captura no fim); para abrir endereço use shadow_open_url, nunca ")
        append("digite na barra. Leia a tela com shadow_screen_text. Não fique conferindo status a cada passo.\n")
        append("- Se a tela paralela estiver na tela de bloqueio, não espere: use o navegador interno (browser_open + browser_screenshot), ")
        append("que não depende do desbloqueio, ou avise @você uma única vez.\n")
        append("- Você pode VER imagens: depois de um screenshot (shadow_screenshot, browser_screenshot) ou com look_at_image, a imagem ")
        append("chega para você. Avalie o visual de verdade em vez de dizer que \"não dá para confirmar\".\n")
        append("- Se um colega está travado, não diga \"o próximo passo é dele\": ofereça ajuda concreta ou faça você mesmo por outro caminho.\n")
        append("- Decisões e restrições antigas valem até surgir motivo melhor; se uma delas está impedindo o trabalho, diga e proponha mudar.\n")
        append("- Trabalho longo não se faz no chat: assuma a tarefa no quadro e use task_board action=start para trabalhar em ")
        append("segundo plano (com sua própria tela paralela e pasta). O grupo é para combinar, decidir e reportar.\n")
        append("- Para combinar algo só com um colega, use prisma_dm (mensagem privada); o grupo fica limpo.\n")
        append("- Caderno do time (team_notebook_write) guarda decisões, padrões e links que todos devem lembrar.\n")
        append("- Marcar é simples: escreva o @ da lista. Quem for marcado recebe e responde. Marque @você quando precisar de uma decisão, ")
        append("aprovação ou informação do usuário (ele recebe uma notificação); não marque à toa.\n")
        append("- Pense bem antes de responder. Pode escrever mensagens longas quando o assunto pedir; no dia a dia, seja natural.\n")
        append("- Você recebe a situação do grupo e o uso da conta (tempo sem o usuário, tokens, limites, saldo). Use isso para decidir ")
        append("sozinho quando parar: conclua o essencial e pare se o gasto estiver alto ou o usuário ausente há muito tempo.\n")
        append("- Sugestões e avisos do usuário têm prioridade.\n")
        append("- Se não tiver nada útil a acrescentar agora, responda exatamente $SILENCE.\n</grupo>")
        append("\nAgora: ").append(SimpleDateFormat("EEEE, dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date())).append('.')
    }

    private fun nameOf(id: String, names: Map<String, String>) = when (id) {
        GroupAuthors.USER -> "Usuário"
        GroupAuthors.SYSTEM -> "Sistema"
        else -> names[id] ?: "Prisma"
    }

    /** The messages as plain lines, oldest first ([window] most recent, each cut to [perMessage]). */
    private fun transcript(group: PrismaGroup, members: List<Bot>, admin: Bot, window: Int = 40, perMessage: Int = 1_500): String {
        val names = (members + admin).associate { it.id to it.name }
        val byId = group.messages.associateBy { it.id }
        val typingNames = group.messages.filter { it.streaming }.map { nameOf(it.authorId, names) }.distinct()
        return buildString {
            if (group.memory.isNotBlank()) {
                val ata = if (window < 40) group.memory.trim().take(1_200) else group.memory.trim()
                append("Ata do grupo (resumo do que aconteceu antes):\n").append(ata).append("\n\n")
            }
            append("Conversa do grupo \"").append(group.name).append("\" até agora:\n")
            group.messages.filter {
                !it.streaming && it.text.isNotBlank() &&
                    (it.kind != GroupMessageKind.SYSTEM || NOTICE_PREFIXES.any { prefix -> it.text.startsWith(prefix) })
            }.drop(group.summarizedCount).takeLast(window).forEach { message ->
                val who = nameOf(message.authorId, names)
                val tag = when (message.kind) {
                    GroupMessageKind.SUGGESTION -> "$who · sugestão"
                    GroupMessageKind.ANNOUNCEMENT -> "Aviso de $who"
                    GroupMessageKind.SUMMARY -> "$who · resumo"
                    GroupMessageKind.DECISION -> "Decisão registrada"
                    GroupMessageKind.SYSTEM -> "Sistema"
                    else -> who
                }
                append('[').append(SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(message.createdAtMillis))).append("] [").append(tag).append(']')
                message.replyToId?.let(byId::get)?.let { append(" (respondendo a ").append(nameOf(it.authorId, names)).append(')') }
                if (message.activity.isNotEmpty()) append(" {usou: ").append(message.activity.takeLast(8).joinToString(", ")).append('}')
                if (message.error != null) append(" {erro: ").append(message.error.take(120)).append('}')
                append(": ").append(message.text.trim().take(perMessage)).append('\n')
            }
            if (typingNames.isNotEmpty()) append("(Digitando agora: ").append(typingNames.joinToString(", ")).append(")\n")
            if (group.decisions.isNotEmpty()) {
                append("Decisões já registradas: ").append(group.decisions.takeLast(3).joinToString(" | ") { it.take(300) }).append('\n')
            }
        }
    }

    private fun boardBlock(group: PrismaGroup, names: Map<String, String>): String {
        if (group.tasks.isEmpty()) return "\n<quadro_de_tarefas>(vazio — use task_board para organizar o trabalho)</quadro_de_tarefas>"
        val now = System.currentTimeMillis()
        return buildString {
            append("\n<quadro_de_tarefas>\n")
            group.tasks.filter { it.status != TaskStatus.DONE }.forEach { task ->
                append("- [").append(task.id).append("] ").append(task.title).append(" — ").append(names[task.ownerId] ?: if (task.ownerId == GroupAuthors.USER) "usuário" else "sem dono")
                append(" (").append(task.status.label)
                task.progress?.let { append(", ").append(it).append('%') }
                append(", atualizada há ").append(groupAgo(now - task.updatedAtMillis)).append(')')
                if (task.note.isNotBlank()) append(": ").append(task.note.take(200))
                append('\n')
            }
            val done = group.tasks.count { it.status == TaskStatus.DONE }
            if (done > 0) append("(").append(done).append(" tarefa(s) feita(s))\n")
            append("</quadro_de_tarefas>")
        }
    }

    private fun teamNotebookBlock(context: Context, groupId: String): String {
        val state = PrismaNotebook(context).state(teamNotebookId(groupId))
        val notes = NotebookLogic.forContext(state, setOf(NotebookLogic.PLACE_GENERAL), System.currentTimeMillis(), budget = 1_500)
        if (notes.isEmpty()) return ""
        return "\n<caderno_do_time>\n" + NotebookLogic.render(notes) + "\n</caderno_do_time>"
    }

    private fun dmBlock(dms: List<PrismaDm>, names: Map<String, String>): String {
        if (dms.isEmpty()) return ""
        return "\n<mensagens_privadas_para_voce>\n" + dms.joinToString("\n") { "${names[it.fromId] ?: "Prisma"}: ${it.text.take(800)}" } +
            "\n(Responda em privado com prisma_dm, se fizer sentido.)\n</mensagens_privadas_para_voce>"
    }

    private val compacting = mutableSetOf<String>()

    private fun compactIfNeeded(context: Context, repository: GroupRepository, groupId: String) {
        val group = GroupStore.get(groupId) ?: return
        val finished = group.messages.filter { !it.streaming && it.kind != GroupMessageKind.SYSTEM && it.text.isNotBlank() }
        if (finished.size - group.summarizedCount <= 70 || !compacting.add(groupId)) return
        scope.launch {
            try {
                val bots = BotRepository(context)
                val names = bots.list().associate { it.id to it.name } + (GroupAuthors.USER to "Usuário")
                val upTo = finished.size - 40
                val chunk = finished.subList(group.summarizedCount.coerceAtMost(upTo), upTo)
                val lines = chunk.joinToString("\n") { "${names[it.authorId] ?: "Prisma"}: ${it.text.take(600)}" }
                val minutes = PrismaModel.complete(
                    context,
                    bots.ensureAdmin().systemPrompt() + "\n\nVocê mantém a ata do grupo \"${group.name}\". Seja fiel e objetivo; não invente nada.",
                    "Ata atual:\n${group.memory.ifBlank { "(vazia)" }}\n\nNovas mensagens:\n$lines\n\n" +
                        "Atualize a ata em até 15 linhas: objetivos, o que cada um fez e defendeu, decisões, quem ficou de fazer o quê e pendências.",
                    bot = bots.ensureAdmin(),
                )
                if (minutes.isNotBlank()) {
                    GroupStore.update(repository, groupId) { it.copy(memory = minutes.take(4_000), summarizedCount = upTo) }
                }
            } catch (_: Throwable) {
            } finally {
                compacting -= groupId
            }
        }
    }

    private fun addUsage(repository: GroupRepository, groupId: String, tokens: Long) {
        if (tokens <= 0) return
        GroupStore.update(repository, groupId, persist = false) {
            it.copy(tokensTotal = it.tokensTotal + tokens, tokensSinceUser = it.tokensSinceUser + tokens)
        }
    }

    private fun situation(group: PrismaGroup): String = buildString {
        val now = System.currentTimeMillis()
        val lastUser = group.lastUserAtMillis.takeIf { it > 0 }
            ?: group.messages.lastOrNull { it.authorId == GroupAuthors.USER }?.createdAtMillis?.takeIf { it > 0 }
        append("<situacao_do_grupo>\n")
        append("O usuário escreveu pela última vez ").append(lastUser?.let { "há " + groupAgo(now - it) } ?: "nunca (o grupo foi iniciado por uma rotina ou por um Prisma)").append(".\n")
        append("Desde então, os Prismas mandaram ").append(prismaRun(group)).append(" mensagens e gastaram cerca de ")
            .append(groupTokens(group.tokensSinceUser)).append(" tokens (total do grupo: ").append(groupTokens(group.tokensTotal)).append(").\n")
        append("</situacao_do_grupo>")
    }

    private fun groupAgo(millis: Long): String {
        val minutes = millis / 60_000
        return when {
            minutes < 1 -> "menos de 1 minuto"
            minutes < 60 -> "$minutes min"
            minutes < 48 * 60 -> "${minutes / 60} h ${minutes % 60} min"
            else -> "${minutes / (24 * 60)} dias"
        }
    }

    private fun groupTokens(value: Long): String = when {
        value >= 1_000_000 -> "%.1f mi".format(Locale("pt", "BR"), value / 1_000_000.0)
        value >= 1_000 -> "${value / 1_000} mil"
        else -> value.toString()
    }

    /** Updates a message in place; persisted, so tools already run survive an app restart. */
    private fun edit(repository: GroupRepository, groupId: String, messageId: String, change: (GroupMessage) -> GroupMessage) {
        GroupStore.update(repository, groupId) { group ->
            group.copy(messages = group.messages.map { if (it.id == messageId) change(it) else it })
        }
    }
}

/**
 * Adds a finished message the WhatsApp way: after every finished message and before the
 * "digitando…" bubbles of whoever is still typing.
 */
internal fun PrismaGroup.withMessage(message: GroupMessage): PrismaGroup {
    val (typing, done) = messages.partition { it.streaming }
    return copy(messages = done + message + typing, updatedAtMillis = System.currentTimeMillis())
}

/** A prisma in a group asks to run a tool that needs the user's OK. */
internal class GroupApproval(val groupId: String, val groupName: String, val prismaName: String, val summary: String) {
    val decision = kotlinx.coroutines.CompletableDeferred<Boolean>()
    fun answer(allow: Boolean) {
        decision.complete(allow)
    }
}

/** Enabled plugins, as a prompt block for prismas (empty when none). */
internal fun pluginsPrompt(context: Context): String =
    runCatching { PluginRegistry.promptFor(context) }.getOrDefault("").let { if (it.isBlank()) "" else "\n\n$it" }

// ---------------------------------------------------------------------------------------------
// Delivering a message from a prisma to the user: its own chat + a notification with its name.
// ---------------------------------------------------------------------------------------------

internal object PrismaDispatcher {
    /** Makes sure [bot] has its own conversation, creating it the first time. */
    suspend fun sessionFor(context: Context, bot: Bot): String {
        val sessions = ChatSessionRepository(context)
        bot.sessionId?.let { id -> if (sessions.load(id) != null) return id }
        val settings = ProviderSettingsRepository(context).load()
        val created = sessions.create(settings)
        BotRepository(context).save(bot.copy(sessionId = created.summary.id))
        return created.summary.id
    }

    /**
     * Runs a routine as a full agent turn (with tools) in the prisma's chat and waits for the
     * answer (up to [timeoutMillis]). Returns the reply text, or null when it did not finish.
     */
    suspend fun runTurn(context: Context, bot: Bot, text: String, timeoutMillis: Long = 8 * 60_000L): String? {
        val sessionId = sessionFor(context, bot)
        val sessions = ChatSessionRepository(context)
        val settings = ProviderSettingsRepository(context).let { it.settingsForPrisma(bot, it.load()) }
        val snapshot = sessions.load(sessionId) ?: return null
        val turnId = UUID.randomUUID().toString()
        val state = ChatReducer.submit(
            state = ChatState(messages = snapshot.messages),
            turnId = turnId,
            userMessageId = UUID.randomUUID().toString(),
            assistantMessageId = UUID.randomUUID().toString(),
            text = text,
            providerId = settings.preset.name.lowercase(Locale.ROOT),
            providerDisplayName = settings.preset.displayName,
            requestedModelId = settings.modelId,
        )
        sessions.save(
            id = sessionId,
            settings = settings,
            messages = state.messages,
            providerLedger = snapshot.providerLedger + ProviderMessage(role = "user", parts = listOf(MessagePart.Text(text))),
            toolActivities = snapshot.toolActivities,
        )
        val runId = DistributionBindings.enqueuePersistentTurn(context, sessionId, turnId, text.take(500), settings)
        val executions = ExecutionRepository(context)
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            delay(3_000)
            val run = executions.loadRun(runId) ?: break
            val state2 = runCatching { AgentRunState.valueOf(run.state) }.getOrNull()
            if (state2 == AgentRunState.SUCCEEDED || state2 == AgentRunState.FAILED || state2 == AgentRunState.WAITING_INPUT) {
                break
            }
        }
        return sessions.load(sessionId)?.messages?.lastOrNull { it.role == ChatRole.ASSISTANT }
            ?.takeIf { it.status != ChatMessageStatus.STREAMING }
            ?.text?.takeIf(String::isNotBlank)
    }

    /** Appends a finished assistant message (the prisma "speaking first") to its chat. */
    suspend fun appendMessage(context: Context, bot: Bot, text: String): String {
        val sessionId = sessionFor(context, bot)
        val sessions = ChatSessionRepository(context)
        val settings = ProviderSettingsRepository(context).load()
        val snapshot = sessions.load(sessionId) ?: return sessionId
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = ChatRole.ASSISTANT,
            text = text,
            status = ChatMessageStatus.COMPLETE,
            providerDisplayName = bot.name,
            createdAtMillis = System.currentTimeMillis(),
        )
        sessions.save(
            id = sessionId,
            settings = settings,
            messages = snapshot.messages + message,
            providerLedger = snapshot.providerLedger + ProviderMessage(role = "assistant", parts = listOf(MessagePart.Text(text))),
            toolActivities = snapshot.toolActivities,
        )
        return sessionId
    }
}

/**
 * Plain notices. Messages from a prisma (private chat) or a group go through
 * [PrismaNotifications.privateMessage] / [PrismaNotifications.groupMention] instead, so they stack
 * like a chat. [kind] picks the channel; by default a group notice is "Grupos" and anything else
 * is "Avisos e rotinas".
 */
internal object PrismaNotifier {
    fun notify(
        context: Context,
        title: String,
        text: String,
        sessionId: String? = null,
        groupId: String? = null,
        kind: PrismaNotifications.Kind? = null,
    ) {
        PrismaNotifications.alert(
            context,
            kind ?: if (groupId != null) PrismaNotifications.Kind.GROUP else PrismaNotifications.Kind.ROUTINE,
            title,
            text,
            sessionId = sessionId,
            groupId = groupId,
        )
    }
}

/** Where a tapped notification wants to go; the shell reads and clears it. */
internal object PendingNavigation {
    const val EXTRA_SESSION = "refrator.open_session"
    const val EXTRA_GROUP = "refrator.open_group"
    var sessionId by mutableStateOf<String?>(null)
    var groupId by mutableStateOf<String?>(null)

    fun consume(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_SESSION)?.let { sessionId = it }
        intent.getStringExtra(EXTRA_GROUP)?.let { groupId = it }
        intent.removeExtra(EXTRA_SESSION)
        intent.removeExtra(EXTRA_GROUP)
    }
}

// ---------------------------------------------------------------------------------------------
// Routines (the user's agenda for each prisma) and free check-ins (prismas starting a chat).
// ---------------------------------------------------------------------------------------------

internal object RoutineScheduler {
    private const val CHECK_IN_WORK = "prisma-check-in"

    fun schedule(context: Context, routine: PrismaRoutine) {
        val work = WorkManager.getInstance(context)
        val next = routine.nextRunAfter(System.currentTimeMillis())
        if (next == null) {
            work.cancelUniqueWork(name(routine.id))
            return
        }
        val request = OneTimeWorkRequestBuilder<RoutineWorker>()
            .setInitialDelay((next - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(RoutineWorker.KEY_ROUTINE to routine.id))
            .addTag("prisma-routine")
            .build()
        work.enqueueUniqueWork(name(routine.id), ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context, routineId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(name(routineId))
    }

    /** Re-arms every routine and the free check-in loop (cheap; called at app start and after edits). */
    fun scheduleAll(context: Context) {
        RoutineRepository(context).list().forEach { schedule(context, it) }
        val anyFree = BotRepository(context).list().any { it.autonomy == PrismaAutonomy.FREE }
        val work = WorkManager.getInstance(context)
        if (anyFree) {
            work.enqueueUniquePeriodicWork(
                CHECK_IN_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<PrismaCheckInWorker>(3, TimeUnit.HOURS).build(),
            )
        } else {
            work.cancelUniqueWork(CHECK_IN_WORK)
        }
    }

    /** Runs a routine right now, outside its schedule ("▶" in the agenda). */
    fun runNow(context: Context, routine: PrismaRoutine) {
        val request = OneTimeWorkRequestBuilder<RoutineWorker>()
            .setInputData(workDataOf(RoutineWorker.KEY_ROUTINE to routine.id, RoutineWorker.KEY_MANUAL to true))
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    private fun name(id: String) = "prisma-routine-$id"
}

class RoutineWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val routines = RoutineRepository(applicationContext)
        val routine = routines.byId(inputData.getString(KEY_ROUTINE) ?: return Result.success()) ?: return Result.success()
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        if (!routine.enabled && !manual) return Result.success()
        val bots = BotRepository(applicationContext)
        val bot = bots.byId(routine.botId) ?: bots.ensureAdmin()
        val result = runCatching {
            val groupId = routine.groupId
            if (groupId != null) {
                withContext(Dispatchers.Main) { GroupEngine.roundNow(applicationContext, groupId, routine.instruction) }
                val group = GroupStore.get(groupId)
                val last = group?.lastMessage?.text.orEmpty()
                PrismaNotifier.notify(
                    applicationContext,
                    "${group?.name ?: "Grupo"} · ${routine.title}",
                    last.ifBlank { "Rodada concluída." },
                    groupId = groupId,
                    kind = PrismaNotifications.Kind.ROUTINE,
                )
                last.ifBlank { "Rodada concluída." }
            } else {
                val prompt = "⏰ Rotina \"${routine.title}\" (${routine.scheduleLabel()}): ${routine.instruction}\n\n" +
                    "Agora são ${SimpleDateFormat("EEEE, dd/MM HH:mm", Locale("pt", "BR")).format(Date())}. " +
                    "Você está mandando esta mensagem por conta própria: seja direto e útil."
                val reply = PrismaDispatcher.runTurn(applicationContext, bot, prompt)
                val sessionId = bots.byId(bot.id)?.sessionId
                PrismaNotifications.privateMessage(applicationContext, bot, reply ?: "Terminei a rotina \"${routine.title}\". Toque para ver.", sessionId)
                reply ?: "Executada (sem resposta final ainda)."
            }
        }
        routines.save(
            routine.copy(
                lastRunAtMillis = System.currentTimeMillis(),
                lastResult = result.getOrElse { "Falhou: ${it.message ?: "erro"}" }.replace(Regex("\\s+"), " ").take(160),
            ),
        )
        routines.byId(routine.id)?.let { RoutineScheduler.schedule(applicationContext, it) }
        return Result.success()
    }

    companion object {
        const val KEY_ROUTINE = "routine_id"
        const val KEY_MANUAL = "manual"
    }
}

/** Every few hours, prismas with "Livre" autonomy may start a conversation if they have something useful. */
class PrismaCheckInWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val bots = BotRepository(applicationContext)
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val today = SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date())
        val counters = applicationContext.getSharedPreferences("prisma-autonomy", Context.MODE_PRIVATE)
        val sessions = ChatSessionRepository(applicationContext)
        for (bot in bots.list().filter { it.autonomy == PrismaAutonomy.FREE }) {
            if (bot.isQuietAt(hour)) continue
            val key = "count-${bot.id}-$today"
            if (counters.getInt(key, 0) >= bot.dailyLimit) continue
            val recent = bot.sessionId?.let { sessions.load(it) }?.messages.orEmpty().takeLast(10).joinToString("\n") { message ->
                (if (message.role == ChatRole.USER) "Usuário: " else "${bot.name}: ") + message.text.take(400)
            }
            val system = bot.systemPrompt() + pluginsPrompt(applicationContext) +
                "\n\nVocê pode puxar assunto com o usuário por conta própria, como um amigo útil, sem ser invasivo."
            val user = "Agora são ${SimpleDateFormat("EEEE, dd/MM HH:mm", Locale("pt", "BR")).format(Date())}.\n" +
                (if (recent.isBlank()) "Vocês ainda não conversaram.\n" else "Últimas mensagens:\n$recent\n") +
                "Se tiver algo realmente útil e oportuno para dizer agora (um lembrete, acompanhamento de algo pendente, " +
                "uma ideia ligada ao seu trabalho), escreva a mensagem em até 60 palavras. Se não tiver, responda exatamente: NADA"
            val reply = runCatching { PrismaModel.complete(applicationContext, system, user, bot = bot) }.getOrNull()?.trim() ?: continue
            if (reply.isBlank() || reply.trim('.', ' ').equals("NADA", ignoreCase = true)) continue
            val sessionId = PrismaDispatcher.appendMessage(applicationContext, bot, reply)
            counters.edit().putInt(key, counters.getInt(key, 0) + 1).apply()
            PrismaNotifications.privateMessage(applicationContext, bot, reply, sessionId)
        }
        return Result.success()
    }
}
