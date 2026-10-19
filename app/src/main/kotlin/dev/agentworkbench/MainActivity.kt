package dev.agentworkbench

import android.os.Bundle
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.compose.setContent
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import kotlin.math.roundToInt
import dev.agentworkbench.core.Capability
import dev.agentworkbench.core.ApprovalAuthority
import dev.agentworkbench.core.ApprovalChallenge
import dev.agentworkbench.core.CommandSpec
import dev.agentworkbench.core.ConfirmationMethod
import dev.agentworkbench.core.DistributionProfile
import dev.agentworkbench.core.EnvironmentTrust
import dev.agentworkbench.core.ExecutionRunner
import dev.agentworkbench.core.ExecutionMode
import dev.agentworkbench.core.PolicyContext
import dev.agentworkbench.core.PolicyDecision
import dev.agentworkbench.core.PolicyEngine
import dev.agentworkbench.core.PermitResult
import dev.agentworkbench.core.RunnerEvent
import dev.agentworkbench.core.ToolEffect
import dev.agentworkbench.core.ToolRequest
import dev.agentworkbench.core.fingerprint
import dev.agentworkbench.runner.safe.AndroidSafeRunner
import java.time.Instant
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SHIZUKU_PERMISSION_REQUEST = 7_201

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReporter.install(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        DistributionBindings.scheduleExecutionRecovery(applicationContext)
        PendingNavigation.consume(intent)
        BotRepository(applicationContext).ensureAdmin()
        runCatching { RoutineScheduler.scheduleAll(applicationContext) }
        // Background missions that were working before the app closed pick up where they left.
        runCatching { MissionEngine.resumeAll(applicationContext) }
        // Groups cut by a dead process (low memory, a crashed app it depended on) go on by themselves.
        runCatching { GroupEngine.resumeAfterDeath(applicationContext) }
        askNotificationPermissionOnce()
        setContent {
            WorkbenchTheme {
                // One clock for every mascot: the same friend moves in sync everywhere.
                ProvideMascotClock {
                    AgentWorkbenchShell(
                        profile = DistributionBindings.profile(),
                        workspacePath = applicationContext.filesDir.absolutePath,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        PendingNavigation.consume(intent)
    }

    override fun onResume() {
        super.onResume()
        PrismaNotifications.setForeground(true)
    }

    override fun onPause() {
        PrismaNotifications.setForeground(false)
        super.onPause()
    }

    /** Prismas message first (routines, notices, decisions): ask once for notification access. */
    private fun askNotificationPermissionOnce() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("prisma-autonomy", MODE_PRIVATE)
        if (prefs.getBoolean("asked-notifications", false)) return
        prefs.edit().putBoolean("asked-notifications", true).apply()
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7_302)
    }

    override fun onDestroy() {
        AgentBrowserSession.release(this)
        super.onDestroy()
    }
}

private enum class WorkbenchDestination {
    CHATS,
    CHAT,
    EXPLORE,
    BOTS,
    PROFILE,
    GROUP,
    AGENDA,
}

private enum class ChatLibraryView { ACTIVE, ARCHIVED, TRASH }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentWorkbenchShell(
    profile: DistributionProfile,
    workspacePath: String,
) {
    val context = LocalContext.current
    val rootView = LocalView.current
    val repository = remember(context) {
        ProviderSettingsRepository(context.applicationContext)
    }
    val sessionRepository = remember(context) {
        ChatSessionRepository(context.applicationContext)
    }
    val contextMemoryRepository = remember(context) {
        ContextMemoryRepository(context.applicationContext)
    }
    val agentPlatformRepository = remember(context) {
        AgentPlatformRepository(context.applicationContext)
    }
    val workspaceId = remember(workspacePath) {
        ContextMemoryRepository.workspaceId(workspacePath)
    }
    val skillRepository = remember(context) {
        AgentSkillRepository(context.applicationContext)
    }
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(repository.load()) }
    var destination by remember { mutableStateOf(WorkbenchDestination.CHATS) }
    var settingsReturn by remember { mutableStateOf(WorkbenchDestination.CHAT) }
    var sessions by remember { mutableStateOf<List<ChatSessionSummary>>(emptyList()) }
    var activeSessionId by remember { mutableStateOf<String?>(null) }
    var historyOpen by remember { mutableStateOf(false) }
    var accountsOpen by remember { mutableStateOf(false) }
    val historyPanelWidthPx = LocalWindowInfo.current.containerSize.width * 0.88f
    var historyOffset by remember(historyPanelWidthPx) {
        mutableFloatStateOf(-historyPanelWidthPx)
    }
    var libraryView by remember { mutableStateOf(ChatLibraryView.ACTIVE) }
    var chatBusy by remember { mutableStateOf(false) }
    var imeVisible by remember { mutableStateOf(false) }

    // No Motorola/Android 15, a leitura Compose de WindowInsets dentro do slot do Scaffold
    // pode ficar com o valor consumido durante a animação. Observar os insets da View raiz não
    // substitui listeners do Compose e mantém a presença do bottomBar sincronizada com o IME.
    DisposableEffect(rootView) {
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val visible = ViewCompat.getRootWindowInsets(rootView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            if (imeVisible != visible) imeVisible = visible
        }
        rootView.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose {
            if (rootView.viewTreeObserver.isAlive) {
                rootView.viewTreeObserver.removeOnGlobalLayoutListener(listener)
            }
        }
    }
    var enabledSkillIds by remember {
        mutableStateOf(skillRepository.enabledIds())
    }
    val transportSecurity = remember(context) { TransportSecurityPreferences(context) }
    var allowLocalCleartext by remember {
        mutableStateOf(transportSecurity.allowLocalCleartext())
    }

    androidx.compose.runtime.LaunchedEffect(sessionRepository) {
        val existing = sessionRepository.list()
        if (existing.isEmpty()) {
            val created = sessionRepository.create(settings)
            sessions = listOf(created.summary)
            activeSessionId = created.summary.id
        } else {
            sessions = existing
            activeSessionId = existing.first().id
        }
    }

    fun mergeSummary(summary: ChatSessionSummary) {
        sessions = (sessions.filterNot { it.id == summary.id } + summary)
            .sortedWith(
                compareByDescending<ChatSessionSummary> { it.pinned }
                    .thenByDescending { it.updatedAtMillis },
            )
    }

    fun openSettings() {
        if (destination != WorkbenchDestination.PROFILE) {
            settingsReturn = destination
        }
        destination = WorkbenchDestination.PROFILE
    }

    fun createNewChat() {
        if (chatBusy) return
        scope.launch {
            val created = sessionRepository.create(settings)
            libraryView = ChatLibraryView.ACTIVE
            mergeSummary(created.summary)
            activeSessionId = created.summary.id
            destination = WorkbenchDestination.CHAT
        }
    }

    fun openHistory() {
        historyOpen = true
        WorkbenchFeedback.onDrawerToggle(context)
        historyOffset = 0f
    }

    fun closeHistory() {
        historyOpen = false
        WorkbenchFeedback.onDrawerToggle(context)
        historyOffset = -historyPanelWidthPx
    }

    fun settleHistoryDrag() {
        // Um gesto curto já demonstra intenção; não obriga atravessar metade da tela.
        if (historyOffset > -historyPanelWidthPx * 0.8f) openHistory() else closeHistory()
    }

    val botRepository = remember(context) { BotRepository(context.applicationContext) }
    var bots by remember { mutableStateOf(botRepository.list()) }
    // (bot being edited, is it new?)
    var botDraft by remember { mutableStateOf<Pair<Bot, Boolean>?>(null) }
    var renameDraft by remember { mutableStateOf<String?>(null) }
    var exploreTab by remember { mutableStateOf(0) }
    var inspectorRequest by remember { mutableStateOf(0) }
    // Sociedade Prisma: groups, agenda and the plugin store.
    val groupRepository = remember(context) { GroupRepository(context.applicationContext).also(GroupStore::ensureLoaded) }
    val routineRepository = remember(context) { RoutineRepository(context.applicationContext) }
    val groups by GroupStore.groups.collectAsState()
    val speaking by GroupEngine.speaking.collectAsState()
    var activeGroupId by remember { mutableStateOf<String?>(null) }
    // (group being edited or null for a new one) — wrapped so "new" and "closed" differ.
    var groupDraft by remember { mutableStateOf<Pair<PrismaGroup?, Unit>?>(null) }
    var routines by remember { mutableStateOf(routineRepository.list()) }
    var routineDraft by remember { mutableStateOf<Pair<PrismaRoutine?, Unit>?>(null) }
    var chatPrefill by remember { mutableStateOf<String?>(null) }
    // The prisma whose memory (notebook + private summary) is open.
    var memoryFor by remember { mutableStateOf<Bot?>(null) }
    var boardOpen by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(PrismaChanges.version) {
        if (PrismaChanges.version > 0) {
            bots = botRepository.list()
            routines = routineRepository.list()
        }
    }
    var chatView by remember { mutableStateOf(0) }
    var chatQuery by remember { mutableStateOf("") }
    var archivedSessions by remember { mutableStateOf<List<ChatSessionSummary>>(emptyList()) }
    var trashedSessions by remember { mutableStateOf<List<ChatSessionSummary>>(emptyList()) }
    // Full-text search over conversation content (null = not searching).
    var searchResults by remember { mutableStateOf<List<ChatSessionSummary>?>(null) }

    fun refreshLists() {
        scope.launch {
            sessions = sessionRepository.list()
            archivedSessions = sessionRepository.archived()
            trashedSessions = sessionRepository.trashed()
        }
    }

    androidx.compose.runtime.LaunchedEffect(chatView) {
        when (chatView) {
            1 -> archivedSessions = sessionRepository.archived()
            2 -> trashedSessions = sessionRepository.trashed()
        }
    }
    androidx.compose.runtime.LaunchedEffect(chatQuery) {
        if (chatQuery.trim().length < 2) {
            searchResults = null
        } else {
            kotlinx.coroutines.delay(250)
            searchResults = sessionRepository.search(chatQuery.trim())
        }
    }
    val activeBot = bots.firstOrNull { it.sessionId != null && it.sessionId == activeSessionId }
    val activeSummary = sessions.firstOrNull { it.id == activeSessionId }

    fun openSession(id: String) {
        // Switching conversations mid-answer would drop the running turn; finish it first.
        if (chatBusy && id != activeSessionId) {
            android.widget.Toast.makeText(context, "Aguarde a resposta atual terminar.", android.widget.Toast.LENGTH_SHORT).show()
            destination = WorkbenchDestination.CHAT
            return
        }
        activeSessionId = id
        destination = WorkbenchDestination.CHAT
        PrismaNotifications.markSeen(context, sessionId = id)
    }

    fun openBot(bot: Bot) {
        val existing = bot.sessionId?.takeIf { id -> sessions.any { it.id == id } }
        if (existing != null) {
            openSession(existing)
            return
        }
        if (chatBusy) {
            openSession(activeSessionId.orEmpty())
            return
        }
        scope.launch {
            val created = sessionRepository.create(settings)
            mergeSummary(created.summary)
            botRepository.save(bot.copy(sessionId = created.summary.id))
            bots = botRepository.list()
            openSession(created.summary.id)
        }
    }

    fun openGroup(id: String) {
        activeGroupId = id
        destination = WorkbenchDestination.GROUP
        PrismaNotifications.markSeen(context, groupId = id)
    }

    // The chat or group on screen does not raise alerts while the app is open.
    androidx.compose.runtime.LaunchedEffect(destination, activeSessionId, activeGroupId) {
        PrismaNotifications.setVisible(
            context,
            sessionId = activeSessionId.takeIf { destination == WorkbenchDestination.CHAT },
            groupId = activeGroupId.takeIf { destination == WorkbenchDestination.GROUP },
        )
    }

    fun openAdmin(prefill: String? = null) {
        val admin = botRepository.ensureAdmin()
        bots = botRepository.list()
        chatPrefill = prefill
        openBot(bots.firstOrNull { it.id == admin.id } ?: admin)
    }

    // A tapped notification (routine, notice, decision) opens its chat or group.
    androidx.compose.runtime.LaunchedEffect(PendingNavigation.sessionId, sessions) {
        val id = PendingNavigation.sessionId ?: return@LaunchedEffect
        if (sessions.any { it.id == id }) {
            PendingNavigation.sessionId = null
            bots = botRepository.list()
            openSession(id)
        } else if (sessions.isNotEmpty()) {
            sessions = sessionRepository.list()
        }
    }
    androidx.compose.runtime.LaunchedEffect(PendingNavigation.groupId) {
        val id = PendingNavigation.groupId ?: return@LaunchedEffect
        PendingNavigation.groupId = null
        openGroup(id)
    }

    androidx.activity.compose.BackHandler(enabled = destination != WorkbenchDestination.CHATS) {
        destination = when (destination) {
            WorkbenchDestination.AGENDA -> WorkbenchDestination.BOTS
            else -> WorkbenchDestination.CHATS
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WorkbenchTokens.Canvas),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding(),
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                DestinationSlot(destination == WorkbenchDestination.CHATS) {
                    val listSource = searchResults ?: when (chatView) {
                        1 -> archivedSessions
                        2 -> trashedSessions
                        else -> sessions
                    }
                    ChatsHome(
                        subtitle = "${bots.size} ${if (bots.size == 1) "prisma" else "prismas"} · ${sessions.size} ${if (sessions.size == 1) "conversa" else "conversas"}",
                        entries = buildChatList(
                            sessions = listSource,
                            bots = bots,
                            includeUnopenedBots = chatView == 0 && searchResults == null,
                        ) { id -> sessionRepository.preview(id) },
                        busySessionId = activeSessionId.takeIf { chatBusy },
                        view = chatView,
                        query = chatQuery,
                        onViewChange = { chatView = it },
                        onQueryChange = { chatQuery = it },
                        actionsFor = { entry ->
                            val id = entry.sessionId
                            val running = chatBusy && id == activeSessionId
                            buildList {
                                entry.bot?.let { bot ->
                                    add("Editar prisma" to { botDraft = bot to false })
                                    add("Memória do prisma" to { memoryFor = bot })
                                }
                                if (id != null && !running) {
                                    when (chatView) {
                                        0 -> {
                                            add(
                                                (if (entry.pinned) "Desafixar" else "Fixar") to {
                                                    scope.launch { sessionRepository.pin(id, !entry.pinned)?.let(::mergeSummary) }
                                                },
                                            )
                                            add("Arquivar" to { scope.launch { sessionRepository.archive(id, true); refreshLists() } })
                                            add("Apagar" to { scope.launch { sessionRepository.delete(id); refreshLists() } })
                                        }
                                        1 -> {
                                            add("Desarquivar" to { scope.launch { sessionRepository.archive(id, false); refreshLists() } })
                                            add("Apagar" to { scope.launch { sessionRepository.delete(id); refreshLists() } })
                                        }
                                        else -> add("Restaurar" to { scope.launch { sessionRepository.restore(id); refreshLists() } })
                                    }
                                }
                            }
                        },
                        onOpen = { entry ->
                            val sessionId = entry.sessionId
                            val bot = entry.bot
                            when {
                                chatView == 2 && searchResults == null ->
                                    android.widget.Toast.makeText(context, "Restaure a conversa para abrir.", android.widget.Toast.LENGTH_SHORT).show()
                                sessionId != null -> openSession(sessionId)
                                bot != null -> openBot(bot)
                            }
                        },
                        onNewChat = ::createNewChat,
                        groups = groups.filterNot { it.archived }.filter { chatQuery.isBlank() || it.name.contains(chatQuery, ignoreCase = true) },
                        groupRow = { group ->
                            GroupRow(
                                group = group,
                                bots = bots,
                                busy = speaking.containsKey(group.id),
                                onClick = { openGroup(group.id) },
                                onLongClick = { groupDraft = group to Unit },
                            )
                        },
                        onNewGroup = { groupDraft = null to Unit },
                        cliShortcuts = listOf("Claude Code" to "claude", "Codex" to "codex", "OpenCode" to "opencode")
                            .map { (label, command) ->
                                "$label no terminal" to {
                                    // Opens the official CLI for the user to drive; native first, else inside Ubuntu.
                                    val opened = EmbeddedTerminalSessions.open(
                                        context,
                                        java.io.File(workspacePath, "workspace"),
                                        initialCommand = "if command -v $command >/dev/null 2>&1; then $command; " +
                                            "else refrator-linux run $command; fi",
                                    )
                                    if (opened) {
                                        exploreTab = 1
                                        destination = WorkbenchDestination.EXPLORE
                                    } else {
                                        android.widget.Toast.makeText(context, "Instale o Linux base na aba Terminal primeiro.", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                    )
                }
                DestinationSlot(destination == WorkbenchDestination.CHAT, horizontal = true) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        ChatTopBar(
                            title = activeBot?.name ?: activeSummary?.title ?: "Refrator",
                            bot = activeBot,
                            mascot = activeBot?.mascot ?: Mascots.forKey(activeSessionId.orEmpty()),
                            busy = chatBusy,
                            modelLabel = repository.settingsForPrisma(activeBot, settings).modelId.substringAfterLast('/'),
                            menuItems = buildList {
                                add("Painel da conversa" to { inspectorRequest++ })
                                activeBot?.let { bot -> add("Memória de ${bot.name}" to { memoryFor = bot }) }
                                // In a prisma's chat its model lives in its editor; the app-wide model
                                // ("Modelo e contas") stays in plain chats and in Perfil, never both here.
                                if (activeBot == null) add("Modelo e contas" to { accountsOpen = true })
                                activeBot?.let { bot -> add("Modelo e edição de ${bot.name}" to { botDraft = bot to false }) }
                                add("Renomear" to { renameDraft = activeSummary?.title.orEmpty() })
                                activeSummary?.let { summary ->
                                    add(
                                        (if (summary.pinned) "Desafixar" else "Fixar") to {
                                            scope.launch { sessionRepository.pin(summary.id, !summary.pinned)?.let(::mergeSummary) }
                                        },
                                    )
                                }
                                add("Nova conversa" to { createNewChat() })
                                activeSummary?.let { summary ->
                                    add(
                                        "Apagar conversa" to {
                                            if (!chatBusy) {
                                                scope.launch {
                                                    if (sessionRepository.delete(summary.id)) {
                                                        sessions = sessionRepository.list()
                                                        destination = WorkbenchDestination.CHATS
                                                    }
                                                }
                                            }
                                        },
                                    )
                                }
                            },
                            onBack = { destination = WorkbenchDestination.CHATS },
                            tune = {
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(CircleShape)
                                        .clickable { inspectorRequest++ }
                                        .semantics { contentDescription = "Painel da conversa" },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(painterResource(R.drawable.ic_activity), contentDescription = null, modifier = Modifier.size(21.dp), tint = WorkbenchTokens.Text)
                                }
                                ComposerTuneButton(
                                    enabled = !chatBusy,
                                    executionMode = settings.executionMode,
                                    reasoningEffort = settings.reasoningEffort,
                                    reasoningSupported = settings.preset.capabilities.reasoningEffort,
                                    onExecutionModeChange = { mode ->
                                        repository.saveExecutionMode(mode)
                                        settings = settings.copy(executionMode = mode)
                                    },
                                    onReasoningEffortChange = { effort ->
                                        repository.saveReasoningEffort(effort)
                                        settings = settings.copy(reasoningEffort = effort)
                                    },
                                )
                            },
                        )
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            val sessionId = activeSessionId
                            if (sessionId == null) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("Escolha uma conversa", color = WorkbenchTokens.TextMuted)
                                }
                            } else {
                                ChatPanel(
                                    repository = repository,
                                    sessionRepository = sessionRepository,
                                    contextMemoryRepository = contextMemoryRepository,
                                    agentPlatformRepository = agentPlatformRepository,
                                    workspaceId = workspaceId,
                                    sessionId = sessionId,
                                    settings = settings,
                                    activeSkills = skillRepository.resolve(enabledSkillIds),
                                    allowLocalCleartext = allowLocalCleartext,
                                    onOpenSettings = { accountsOpen = true },
                                    onSessionSaved = ::mergeSummary,
                                    onBusyChanged = { chatBusy = it },
                                    onSettingsChange = { settings = it },
                                    bot = activeBot,
                                    inspectorRequest = inspectorRequest,
                                    prefill = chatPrefill?.takeIf { PrismaAdmin.isAdmin(activeBot) },
                                )
                            }
                        }
                        if (!imeVisible) Spacer(modifier = Modifier.navigationBarsPadding())
                    }
                }
                DestinationSlot(destination == WorkbenchDestination.EXPLORE) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        ScreenHeader(title = "Explorar", subtitle = "Loja de plugins, terminal e ferramentas")
                        Segmented(listOf("Loja", "Terminal", "Ferramentas"), exploreTab) { exploreTab = it }
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            when (exploreTab) {
                                0 -> PluginStoreScreen(onAskMaestro = { text -> openAdmin(text) })
                                1 -> TerminalPanel(workspacePath = workspacePath)
                                else -> ToolsPanel(profile = profile, workspacePath = workspacePath)
                            }
                        }
                    }
                }
                DestinationSlot(destination == WorkbenchDestination.BOTS) {
                    BotsScreen(
                        bots = bots,
                        onOpen = ::openBot,
                        onEdit = { bot -> botDraft = bot to false },
                        onCreateFrom = { template ->
                            botDraft = (template?.let(BotTemplates::instantiate) ?: Bot(name = "", job = "")) to true
                        },
                        header = {
                            val admin = bots.firstOrNull(PrismaAdmin::isAdmin) ?: PrismaAdmin.default()
                            val busyIds = buildSet {
                                speaking.values.forEach { addAll(it) }
                                if (chatBusy) activeBot?.id?.let(::add)
                            }
                            Column(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                PrismaPlaza(bots = bots, busyIds = busyIds, onOpen = { openBot(it) })
                                val now = System.currentTimeMillis()
                                CentralCard(
                                    admin = admin,
                                    routinesCount = routines.count { it.enabled },
                                    groupsCount = groups.size,
                                    nextRoutine = routines.filter { it.enabled }.minByOrNull { it.nextRunAfter(now) ?: Long.MAX_VALUE },
                                    onOpenAdmin = { openAdmin() },
                                    onOpenAgenda = { destination = WorkbenchDestination.AGENDA },
                                    onNewGroup = { groupDraft = null to Unit },
                                )
                            }
                        },
                    )
                }
                DestinationSlot(destination == WorkbenchDestination.GROUP, horizontal = true) {
                    val group = groups.firstOrNull { it.id == activeGroupId }
                    if (group == null) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Grupo não encontrado", color = WorkbenchTokens.TextMuted)
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            GroupChatScreen(
                                group = group,
                                bots = bots,
                                busy = speaking.containsKey(group.id),
                                speakingId = speaking[group.id]?.firstOrNull(),
                                typingIds = speaking[group.id].orEmpty(),
                                onBack = { destination = WorkbenchDestination.CHATS },
                                onSend = { text, kind, replyTo -> GroupEngine.send(context, group.id, text, kind, replyTo) },
                                onNextRound = { GroupEngine.summarize(context, group.id) },
                                onDecide = { GroupEngine.decide(context, group.id) },
                                onStop = { GroupEngine.stop(group.id) },
                                onEdit = { groupDraft = group to Unit },
                                onToggleAutoReply = { enabled ->
                                    GroupStore.update(groupRepository, group.id) { it.copy(autoReply = enabled) }
                                },
                                modifier = Modifier.weight(1f),
                                onOpenBoard = { boardOpen = true },
                            )
                            if (boardOpen) {
                                GroupBoardSheet(group = group, bots = bots, typingIds = speaking[group.id].orEmpty(), onDismiss = { boardOpen = false })
                            }
                            if (!imeVisible) Spacer(modifier = Modifier.navigationBarsPadding())
                        }
                    }
                }
                DestinationSlot(destination == WorkbenchDestination.AGENDA) {
                    AgendaScreen(
                        routines = routines,
                        bots = bots,
                        groups = groups,
                        onBack = { destination = WorkbenchDestination.BOTS },
                        onEdit = { routine -> routineDraft = routine to Unit },
                        onToggle = { routine, enabled ->
                            val updated = routine.copy(enabled = enabled)
                            routineRepository.save(updated)
                            RoutineScheduler.schedule(context, updated)
                            routines = routineRepository.list()
                        },
                        onRunNow = { routine ->
                            RoutineScheduler.runNow(context, routine)
                            android.widget.Toast.makeText(context, "Rodando \"${routine.title}\" agora…", android.widget.Toast.LENGTH_SHORT).show()
                        },
                    )
                }
                DestinationSlot(destination == WorkbenchDestination.PROFILE) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        ScreenHeader(title = "Perfil", subtitle = "Modelo, contas e ajustes")
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(WorkbenchTokens.Surface)
                                .border(1.dp, WorkbenchTokens.BorderSoft, RoundedCornerShape(20.dp))
                                .clickable { accountsOpen = true }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Modelo e contas", style = MaterialTheme.typography.titleMedium, color = WorkbenchTokens.Text)
                                Text(
                                    text = "${settings.preset.displayName} · ${settings.modelId}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = WorkbenchTokens.TextMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = WorkbenchTokens.TextMuted)
                        }
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            ProviderSettingsPanel(
                                repository = repository,
                                contextMemoryRepository = contextMemoryRepository,
                                agentPlatformRepository = agentPlatformRepository,
                                workspaceId = workspaceId,
                                conversationId = activeSessionId,
                                settings = settings,
                                availableSkills = skillRepository.available,
                                enabledSkillIds = enabledSkillIds,
                                onSkillToggled = { id, enabled ->
                                    enabledSkillIds = skillRepository.setEnabled(id, enabled)
                                },
                                allowLocalCleartext = allowLocalCleartext,
                                onAllowLocalCleartextChanged = { enabled ->
                                    transportSecurity.setAllowLocalCleartext(enabled)
                                    allowLocalCleartext = enabled
                                },
                                onSaved = { saved -> settings = saved },
                            )
                        }
                    }
                }
            }
            if (destination != WorkbenchDestination.CHAT && destination != WorkbenchDestination.GROUP && !imeVisible) {
                HomeBottomBar(
                    selected = when (destination) {
                        WorkbenchDestination.EXPLORE -> 1
                        WorkbenchDestination.BOTS, WorkbenchDestination.AGENDA -> 2
                        WorkbenchDestination.PROFILE -> 3
                        else -> 0
                    },
                    onSelect = { index ->
                        destination = listOf(
                            WorkbenchDestination.CHATS,
                            WorkbenchDestination.EXPLORE,
                            WorkbenchDestination.BOTS,
                            WorkbenchDestination.PROFILE,
                        )[index]
                    },
                )
            }
        }

        groupDraft?.let { (draft, _) ->
            GroupEditorSheet(
                initial = draft,
                bots = bots,
                onSave = { saved ->
                    GroupStore.put(groupRepository, saved)
                    groupDraft = null
                    if (draft == null) openGroup(saved.id)
                },
                onDelete = draft?.let {
                    { removed: PrismaGroup ->
                        GroupEngine.stop(removed.id)
                        GroupStore.remove(groupRepository, removed.id)
                        groupDraft = null
                        if (activeGroupId == removed.id) destination = WorkbenchDestination.CHATS
                    }
                },
                onDismiss = { groupDraft = null },
            )
        }

        routineDraft?.let { (draft, _) ->
            RoutineEditorSheet(
                initial = draft,
                bots = bots,
                groups = groups,
                defaultBotId = draft?.botId ?: PrismaAdmin.ID,
                onSave = { saved ->
                    routineRepository.save(saved)
                    RoutineScheduler.schedule(context, saved)
                    routines = routineRepository.list()
                    routineDraft = null
                },
                onDelete = draft?.takeIf { existing -> routines.any { it.id == existing.id } }?.let {
                    { removed: PrismaRoutine ->
                        routineRepository.delete(removed.id)
                        RoutineScheduler.cancel(context, removed.id)
                        routines = routineRepository.list()
                        routineDraft = null
                    }
                },
                onDismiss = { routineDraft = null },
            )
        }

        botDraft?.let { (draft, isNew) ->
            BotEditorSheet(
                initial = draft,
                isNew = isNew,
                onSave = { saved ->
                    botRepository.save(saved)
                    bots = botRepository.list()
                    RoutineScheduler.scheduleAll(context)
                    botDraft = null
                    if (isNew) openBot(saved)
                },
                onDelete = { removed ->
                    botRepository.delete(removed.id)
                    bots = botRepository.list()
                    botDraft = null
                    if (removed.sessionId == activeSessionId) destination = WorkbenchDestination.CHATS
                },
                onDismiss = { botDraft = null },
            )
        }

        renameDraft?.let { draft ->
            var value by remember(draft) { mutableStateOf(draft) }
            AlertDialog(
                onDismissRequest = { renameDraft = null },
                containerColor = WorkbenchTokens.SurfaceRaised,
                title = { Text("Renomear conversa") },
                text = { OutlinedTextField(value = value, onValueChange = { value = it.take(56) }, singleLine = true) },
                confirmButton = {
                    TextButton(onClick = {
                        val id = activeSessionId
                        renameDraft = null
                        if (id != null && value.isNotBlank()) {
                            scope.launch { sessionRepository.rename(id, value.trim())?.let(::mergeSummary) }
                        }
                    }) { Text("Salvar") }
                },
                dismissButton = { TextButton(onClick = { renameDraft = null }) { Text("Cancelar") } },
            )
        }

        memoryFor?.let { bot ->
            PrismaMemorySheet(bot = bot, groups = groups, onDismiss = { memoryFor = null })
        }

        // A prisma asked the user to log in on its own browser (web_login): the live page, on top.
        PrismaLoginHost()

        // A prisma working in a group wants a tool that needs the user's OK (wherever the user is).
        val groupApproval by GroupEngine.approval.collectAsState()
        groupApproval?.let { request ->
            AlertDialog(
                onDismissRequest = {},
                title = { Text("${request.prismaName} quer usar uma ferramenta") },
                text = { Text("No grupo ${request.groupName}:\n${request.summary}") },
                confirmButton = { TextButton(onClick = { GroupEngine.answerApproval(true) }) { Text("Permitir") } },
                dismissButton = { TextButton(onClick = { GroupEngine.answerApproval(false) }) { Text("Negar") } },
            )
        }

        if (accountsOpen) {
            ProviderAccountsSheet(
                settings = settings,
                repository = repository,
                allowLocalCleartext = allowLocalCleartext,
                onSettingsChange = { settings = it },
                onOpenSettings = {
                    accountsOpen = false
                    openSettings()
                },
                onDismiss = { accountsOpen = false },
            )
        }

        val crashContext = LocalContext.current
        var crashReport by remember { mutableStateOf(CrashReporter.pending(crashContext)) }
        crashReport?.let { report ->
            AlertDialog(
                onDismissRequest = {
                    CrashReporter.clear(crashContext)
                    crashReport = null
                },
                containerColor = WorkbenchTokens.SurfaceRaised,
                title = { Text("O Refrator fechou inesperadamente") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Copie o relatório abaixo e envie para quem está desenvolvendo o app.",
                            color = WorkbenchTokens.TextMuted,
                        )
                        Box(
                            modifier = Modifier
                                .heightIn(max = 280.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            Text(
                                text = report,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelSmall,
                                color = WorkbenchTokens.Text,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            crashContext.getSystemService(android.content.ClipboardManager::class.java)
                                ?.setPrimaryClip(android.content.ClipData.newPlainText("Crash do Refrator", report))
                        },
                    ) { Text("Copiar") }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            CrashReporter.clear(crashContext)
                            crashReport = null
                        },
                    ) { Text("Fechar") }
                },
            )
        }

        CliApprovalCenter.pending.firstOrNull()?.let { entry ->
            AlertDialog(
                onDismissRequest = {},
                containerColor = WorkbenchTokens.SurfaceRaised,
                title = { Text("Agente do terminal pede permissão") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = entry.prepared.invocation.toolName,
                            fontFamily = FontFamily.Monospace,
                            color = WorkbenchTokens.Gold,
                        )
                        Text(entry.prepared.request.summary, color = WorkbenchTokens.Text)
                        Text(
                            text = entry.prepared.decision.reason,
                            style = MaterialTheme.typography.labelSmall,
                            color = WorkbenchTokens.TextMuted,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { CliApprovalCenter.resolve(entry, true) }) { Text("Permitir") }
                },
                dismissButton = {
                    TextButton(onClick = { CliApprovalCenter.resolve(entry, false) }) { Text("Negar") }
                },
            )
        }

    }
}

@Composable
private fun DestinationSlot(
    active: Boolean,
    horizontal: Boolean = false,
    content: @Composable () -> Unit,
) {
    // Fade + slight rise when a destination becomes visible; inactive slots stay alive at size 0.
    val reveal by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
        label = "destinationReveal",
    )
    Box(
        modifier = if (active) {
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = reveal
                    if (horizontal) {
                        translationX = (1f - reveal) * size.width * 0.12f
                    } else {
                        translationY = (1f - reveal) * 12.dp.toPx()
                    }
                }
        } else {
            Modifier
                .size(0.dp)
                .clipToBounds()
        },
    ) {
        content()
    }
}

@Composable
private fun ToolsPanel(
    profile: DistributionProfile,
    workspacePath: String,
) {
    val activity = requireNotNull(LocalActivity.current) {
        "ToolsPanel requires an Activity host."
    }
    val scope = rememberCoroutineScope()
    val runner = remember(workspacePath) {
        AndroidSafeRunner(java.io.File(workspacePath))
    }
    val powerProbe = remember(workspacePath) {
        DistributionBindings.powerProbe(java.io.File(workspacePath))
    }
    val privilegedShell = remember(activity) {
        DistributionBindings.privilegedShellBridge(activity)
    }
    val termuxBridge = remember(activity) { DistributionBindings.termuxBridge(activity) }
    val termuxRepository = remember(activity) { TermuxBridgeConfigRepository(activity.applicationContext) }
    var termuxConfig by remember { mutableStateOf(termuxRepository.load()) }
    var termuxUsername by remember { mutableStateOf(termuxConfig.username) }
    var termuxPort by remember { mutableStateOf(termuxConfig.port.toString()) }
    var termuxWorkspace by remember { mutableStateOf(termuxConfig.workspace) }
    var termuxProbe by remember { mutableStateOf<TermuxHostKeyProbe?>(null) }
    var termuxBusy by remember { mutableStateOf(false) }
    val internalRuntime = remember(activity, workspacePath, powerProbe) {
        InternalRuntime(
            context = activity.applicationContext,
            workspaceRoot = java.io.File(workspacePath),
            externalPackagesAllowed = powerProbe != null,
        )
    }
    var runtimeBusy by remember { mutableStateOf(false) }
    val documentTree = remember(activity) {
        DocumentTreeAccess(activity.applicationContext)
    }
    var documentTreeStatus by remember { mutableStateOf(documentTree.status()) }
    var terminalOutput by remember {
        mutableStateOf("Ready. No arbitrary shell access is enabled.")
    }
    val documentTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            documentTreeStatus = runCatching { documentTree.grant(uri) }
                .getOrElse {
                    terminalOutput = "Falha ao conceder pasta: ${it.message}"
                    documentTree.status()
                }
        }
    }
    var privilegedStatus by remember(privilegedShell) {
        mutableStateOf(privilegedShell.snapshot())
    }
    val approvalAuthority = remember { ApprovalAuthority() }
    var running by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var activeCommandId by remember { mutableStateOf<String?>(null) }
    var commandDraft by remember {
        mutableStateOf("echo \"hello from Refrator\" && id && pwd")
    }
    var pendingApproval by remember {
        mutableStateOf<PendingShellApproval?>(null)
    }
    androidx.compose.runtime.LaunchedEffect(privilegedShell) {
        while (true) {
            privilegedStatus = privilegedShell.snapshot()
            delay(1_000)
        }
    }

    Scaffold { padding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "Terminal e ações locais",
                    style = MaterialTheme.typography.titleLarge,
                )
                DistributionShadowDisplayPanel(workspacePath)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Refrator ${BuildConfig.RELEASE_CHANNEL.uppercase()} " +
                                "${BuildConfig.VERSION_NAME} · ${profile.compiledCapabilities.size} capacidades",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "O shell local é isolado pelo Android e cada comando passa pela política de autorização.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "Arquivos externos · SAF",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = if (documentTreeStatus.granted) {
                                "${documentTreeStatus.displayName ?: "Pasta selecionada"} · " +
                                    "leitura=${documentTreeStatus.canRead} · " +
                                    "escrita=${documentTreeStatus.canWrite}"
                            } else {
                                "Nenhuma pasta externa foi concedida. O seletor do Android " +
                                    "permite escolher Downloads ou uma pasta de projetos."
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { documentTreeLauncher.launch(null) },
                        ) {
                            Text(
                                if (documentTreeStatus.granted) {
                                    "Trocar pasta externa"
                                } else {
                                    "Escolher pasta externa"
                                },
                            )
                        }
                        if (documentTreeStatus.granted) {
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    documentTree.revoke()
                                    documentTreeStatus = documentTree.status()
                                    terminalOutput = "Concessão da pasta externa removida."
                                },
                            ) {
                                Text("Desconectar pasta")
                            }
                        }
                    }
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val runtimeStatus = internalRuntime.status()
                        Text("Runtime interno", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Executa no sandbox do Refrator, sem Termux, SSH, porta local ou root. " +
                                "Packs usam HTTPS, SHA-256 e instalação atômica; ferramentas nativas entram somente como componentes assinados.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "ABI ${runtimeStatus.optString("abi")} · " +
                                "${runtimeStatus.optJSONArray("installed_packages")?.length() ?: 0} packs · " +
                                "workspace $workspacePath",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !runtimeBusy,
                            onClick = {
                                runtimeBusy = true
                                scope.launch {
                                    terminalOutput = runCatching {
                                        val result = internalRuntime.execute(
                                            RuntimeCommandRequest(
                                                id = UUID.randomUUID().toString(),
                                                command = "printf 'runtime=internal\\n'; id; uname -a; pwd; command -v sh toybox grep sed awk find tar sha256sum",
                                                workingDirectory = ".",
                                                timeoutMillis = 15_000,
                                                outputLimitBytes = 65_536,
                                            ),
                                        )
                                        "Runtime exit=${result.exitCode} timeout=${result.timedOut}\n${result.output}"
                                    }.getOrElse { "Teste do runtime falhou: ${it.message}" }
                                    runtimeBusy = false
                                }
                            },
                        ) { Text(if (runtimeBusy) "Testando…" else "Testar runtime interno") }
                    }
                }
                termuxBridge?.let { bridge ->
                    val snapshot = bridge.snapshot(termuxConfig)
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("Linux Termux · SSH local", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${snapshot.detail} Transporte limitado a 127.0.0.1; a chave privada fica no Android Keystore.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedTextField(
                                value = termuxUsername,
                                onValueChange = { termuxUsername = it; termuxProbe = null },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Usuário (resultado de whoami)") },
                                singleLine = true,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = termuxPort,
                                    onValueChange = { termuxPort = it.filter(Char::isDigit); termuxProbe = null },
                                    modifier = Modifier.weight(1f),
                                    label = { Text("Porta") },
                                    singleLine = true,
                                )
                                OutlinedTextField(
                                    value = termuxWorkspace,
                                    onValueChange = { termuxWorkspace = it; termuxProbe = null },
                                    modifier = Modifier.weight(2f),
                                    label = { Text("Workspace Linux") },
                                    singleLine = true,
                                )
                            }
                            Text("1. No Termux, execute:", style = MaterialTheme.typography.labelMedium)
                            OutlinedTextField(
                                value = buildString {
                                    appendLine("pkg install openssh")
                                    appendLine("mkdir -p ~/.ssh ~/agent-workbench")
                                    appendLine("printf '%s\\n' '${bridge.publicKey()}' >> ~/.ssh/authorized_keys")
                                    appendLine("chmod 700 ~/.ssh && chmod 600 ~/.ssh/authorized_keys")
                                    appendLine("termux-wake-lock")
                                    appendLine("sshd -o ListenAddress=127.0.0.1 -o ListenAddress=::1")
                                    append("whoami")
                                },
                                onValueChange = {},
                                readOnly = true,
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Configuração legítima do OpenSSH") },
                                minLines = 6,
                                maxLines = 9,
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !termuxBusy && termuxUsername.isNotBlank() && termuxPort.toIntOrNull() != null,
                                onClick = {
                                    termuxBusy = true
                                    termuxProbe = null
                                    scope.launch {
                                        val candidate = TermuxBridgeConfig(
                                            port = termuxPort.toIntOrNull() ?: 8_022,
                                            username = termuxUsername.trim(),
                                            workspace = termuxWorkspace.trim(),
                                        )
                                        terminalOutput = runCatching {
                                            termuxRepository.saveCandidate(candidate)
                                            val observed = bridge.probeHostKey(candidate)
                                            termuxProbe = observed
                                            "Host key observada. Confira e confirme manualmente: ${observed.fingerprint}"
                                        }.getOrElse {
                                            android.util.Log.e(
                                                "TermuxBridge",
                                                "Falha ao observar host key SSH: ${it::class.java.simpleName}",
                                            )
                                            "Falha ao observar SSH local: ${it::class.java.simpleName}: " +
                                                (it.message ?: "sem mensagem")
                                        }
                                        termuxBusy = false
                                    }
                                },
                            ) { Text(if (termuxBusy) "Conectando…" else "1 · LER HOST KEY") }
                            termuxProbe?.let { observed ->
                                Text(
                                    "${observed.algorithm}\n${observed.fingerprint}",
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Button(
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !termuxBusy,
                                    onClick = {
                                        termuxConfig = TermuxBridgeConfig(
                                            port = termuxPort.toIntOrNull() ?: 8_022,
                                            username = termuxUsername.trim(),
                                            workspace = termuxWorkspace.trim(),
                                            hostKeyFingerprint = observed.fingerprint,
                                        )
                                        terminalOutput = runCatching {
                                            termuxRepository.save(termuxConfig)
                                            "Host key fixada. Alterações futuras serão recusadas até novo pareamento."
                                        }.getOrElse { "Configuração recusada: ${it.message}" }
                                    },
                                ) { Text("2 · CONFIAR NESTA HOST KEY") }
                            }
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !termuxBusy && termuxConfig.configured,
                                onClick = {
                                    termuxBusy = true
                                    scope.launch {
                                        terminalOutput = runCatching {
                                            val result = bridge.execute(
                                                termuxConfig,
                                                TermuxCommandRequest(
                                                    UUID.randomUUID().toString(),
                                                    "printf 'bridge=ok\\n'; id; printf 'shell=%s\\n' \"${'$'}SHELL\"; pwd",
                                                    termuxConfig.workspace,
                                                    15_000,
                                                    65_536,
                                                ),
                                            )
                                            "SSH exit=${result.exitCode} timeout=${result.timedOut}\n${result.output}"
                                        }.getOrElse { "Teste SSH falhou: ${it.message}" }
                                        termuxBusy = false
                                    }
                                },
                            ) { Text("3 · TESTAR LINUX, RUNTIMES E WORKSPACE") }
                            if (termuxConfig.hostKeyFingerprint.isNotBlank()) {
                                TextButton(
                                    onClick = {
                                        termuxRepository.clearPinnedHostKey()
                                        termuxConfig = termuxRepository.load()
                                        termuxProbe = null
                                        terminalOutput = "Confiança SSH removida; nenhum comando remoto será aceito até novo pareamento."
                                    },
                                ) { Text("Remover confiança SSH") }
                            }
                        }
                    }
                }
                privilegedShell.let { bridge ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = "Shizuku · bridge ADB",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = privilegedStatus.displayText(),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                enabled = privilegedStatus.serverRunning &&
                                    !privilegedStatus.permissionGranted,
                                onClick = {
                                    val requested = bridge.requestPermission(
                                        activity = activity,
                                        requestCode = SHIZUKU_PERMISSION_REQUEST,
                                    )
                                    privilegedStatus = bridge.snapshot()
                                    terminalOutput = if (requested) {
                                        "Pedido de autorização enviado ao Shizuku. " +
                                            "O acesso é revogável e não equivale a root."
                                    } else {
                                        "Não foi possível pedir autorização. " +
                                            "Confira se o serviço Shizuku está rodando."
                                    }
                                },
                            ) {
                                Text(
                                    if (privilegedStatus.permissionGranted) {
                                        "Shizuku autorizado"
                                    } else {
                                        "Autorizar Shizuku"
                                    },
                                )
                            }
                            if (showDiagnostics) {
                                OutlinedButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !running && privilegedStatus.permissionGranted,
                                    onClick = {
                                        running = true
                                        terminalOutput = "Conectando UserService Shizuku…"
                                        scope.launch {
                                            terminalOutput = runCatching {
                                                bridge.execute(
                                                    script = "id; getprop ro.product.model; " +
                                                        "getprop ro.build.version.release",
                                                    timeoutMs = 5_000,
                                                    maxOutputBytes = 16_384,
                                                )
                                            }.getOrElse { error ->
                                                "Falha no bridge: " +
                                                    (error.message ?: error::class.java.simpleName)
                                            }
                                            privilegedStatus = bridge.snapshot()
                                            running = false
                                        }
                                    },
                                ) {
                                    Text("Testar bridge (id)")
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = { showDiagnostics = !showDiagnostics }) {
                    Text(if (showDiagnostics) "Ocultar diagnóstico avançado" else "Mostrar diagnóstico avançado")
                }
                if (showDiagnostics) {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !running,
                    onClick = {
                        running = true
                        terminalOutput = "Evaluating policy...\n"
                        scope.launch {
                            terminalOutput = runSafeDiagnostic(
                                profile = profile,
                                runner = runner,
                                workspacePath = workspacePath,
                                onUpdate = { terminalOutput = it },
                            )
                            running = false
                        }
                    },
                ) {
                    Text(if (running) "Rodando…" else "Rodar diagnóstico seguro")
                }
                }
                if (showDiagnostics) powerProbe?.let { (powerRunner, probeCommand) ->
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !running,
                        onClick = {
                            running = true
                            terminalOutput = "Avaliando política de execução...\n"
                            scope.launch {
                                terminalOutput = runPowerShellProbe(
                                    profile = profile,
                                    runner = powerRunner,
                                    command = probeCommand,
                                    onUpdate = { terminalOutput = it },
                                )
                                running = false
                            }
                        },
                    ) {
                        Text(if (running) "Rodando…" else "Testar shell local")
                    }
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = "Comando local",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = "User-entered commands are treated as destructive. " +
                                    "Review and approve the exact payload before every run.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedTextField(
                                value = commandDraft,
                                onValueChange = { value ->
                                    commandDraft = value
                                    pendingApproval = null
                                },
                                enabled = !running,
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Android shell command") },
                                minLines = 2,
                                maxLines = 4,
                            )

                            val pending = pendingApproval
                            if (pending == null) {
                                Button(
                                    enabled = !running && commandDraft.isNotBlank(),
                                    onClick = {
                                        when (
                                            val result = preparePowerShellReview(
                                                profile = profile,
                                                workspacePath = workspacePath,
                                                script = commandDraft,
                                                authority = approvalAuthority,
                                            )
                                        ) {
                                            is PowerReviewResult.Ready -> {
                                                pendingApproval = result.pending
                                                terminalOutput = buildString {
                                                    appendLine("Strong approval required.")
                                                    appendLine("Review the exact command shown above.")
                                                    append(
                                                        "Payload: " +
                                                            result.pending.command
                                                                .fingerprint()
                                                                .take(16),
                                                    )
                                                }
                                            }

                                            is PowerReviewResult.Rejected -> {
                                                terminalOutput = result.reason
                                            }
                                        }
                                    },
                                ) {
                                    Text("Revisar comando")
                                }
                            } else {
                                Text(
                                    text = "Exact command awaiting approval:\n${pending.script}",
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Button(
                                    enabled = !running,
                                    onClick = {
                                        when (
                                            val permitResult = approvalAuthority.issue(
                                                challengeId = pending.challenge.id,
                                                request = pending.request,
                                                method = ConfirmationMethod.STRONG,
                                                now = Instant.now(),
                                            )
                                        ) {
                                            is PermitResult.Rejected -> {
                                                terminalOutput =
                                                    "Approval rejected: ${permitResult.reason}"
                                                pendingApproval = null
                                            }

                                            is PermitResult.Issued -> {
                                                val authorization = approvalAuthority.bind(
                                                    permit = permitResult.permit,
                                                    request = pending.request,
                                                    command = pending.command,
                                                )
                                                if (authorization == null) {
                                                    terminalOutput =
                                                        "Approval binding failed; command not run."
                                                    pendingApproval = null
                                                } else {
                                                    val authorizedCommand =
                                                        pending.command.copy(
                                                            authorization = authorization,
                                                        )
                                                    pendingApproval = null
                                                    running = true
                                                    activeCommandId = authorizedCommand.id
                                                    terminalOutput =
                                                        "Executing approved payload...\n"
                                                    scope.launch {
                                                        terminalOutput = executePowerCommand(
                                                            runner = powerRunner,
                                                            command = authorizedCommand,
                                                            script = pending.script,
                                                            onUpdate = {
                                                                terminalOutput = it
                                                            },
                                                        )
                                                        activeCommandId = null
                                                        running = false
                                                    }
                                                }
                                            }
                                        }
                                    },
                                ) {
                                    Text("Aprovar comando exato")
                                }
                                OutlinedButton(
                                    enabled = !running,
                                    onClick = {
                                        pendingApproval = null
                                        terminalOutput = "Approval cancelled; nothing executed."
                                    },
                                ) {
                                    Text("Cancelar revisão")
                                }
                            }

                            val commandId = activeCommandId
                            if (running && commandId != null) {
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            powerRunner.cancel(commandId)
                                        }
                                    },
                                ) {
                                    Text("Cancelar comando em execução")
                                }
                            }
                        }
                    }
                }
                // Sem altura própria a saída esticava a tela inteira; sem SelectionContainer,
                // uma tela chamada "Terminal e ações locais" não deixava copiar o que ela mesma
                // imprimia — diferente do CodeBlock do chat, que já tem botão de copiar.
                SelectionContainer {
                    Text(
                        text = terminalOutput,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState())
                            .background(Color(0xFF111318))
                            .padding(16.dp),
                        color = Color(0xFFE2E8F0),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private data class PendingShellApproval(
    val script: String,
    val command: CommandSpec,
    val request: ToolRequest,
    val challenge: ApprovalChallenge,
)

private sealed interface PowerReviewResult {
    data class Ready(
        val pending: PendingShellApproval,
    ) : PowerReviewResult

    data class Rejected(
        val reason: String,
    ) : PowerReviewResult
}

private fun preparePowerShellReview(
    profile: DistributionProfile,
    workspacePath: String,
    script: String,
    authority: ApprovalAuthority,
): PowerReviewResult {
    val command = DistributionBindings.powerCommand(
        workspaceRoot = java.io.File(workspacePath),
        commandId = "user-shell-${UUID.randomUUID()}",
        script = script,
    ) ?: return PowerReviewResult.Rejected(
        "A execução do shell Android não está disponível nesta distribuição.",
    )
    val request = ToolRequest(
        id = command.id,
        toolName = "power.user_shell",
        capabilities = setOf(
            Capability.FILE_READ,
            Capability.FILE_WRITE,
            Capability.PROCESS_INSPECT,
            Capability.SHELL_EXECUTE,
            Capability.NETWORK_ACCESS,
        ),
        effect = ToolEffect.DESTRUCTIVE,
        workspaceScoped = false,
        reversible = false,
        summary = "Execute exact user-entered shell command:\n$script",
        payloadFingerprint = command.fingerprint(),
    )
    val decision = PolicyEngine().evaluate(
        context = PolicyContext(
            distribution = profile,
            mode = ExecutionMode.FULL,
            environmentTrust = EnvironmentTrust.POWER_USERSPACE,
        ),
        request = request,
    )
    if (decision !is PolicyDecision.Ask || !decision.strongConfirmation) {
        return PowerReviewResult.Rejected(
            "Fail-closed: arbitrary shell did not receive a strong approval challenge.",
        )
    }
    val challenge = authority.prepare(request, decision, Instant.now())
        ?: return PowerReviewResult.Rejected("Policy refused to create an approval challenge.")
    return PowerReviewResult.Ready(
        PendingShellApproval(
            script = script,
            command = command,
            request = request,
            challenge = challenge,
        ),
    )
}

private suspend fun executePowerCommand(
    runner: ExecutionRunner,
    command: CommandSpec,
    script: String,
    onUpdate: (String) -> Unit,
): String {
    val output = StringBuilder()
    output.appendLine("Approved payload: ${command.fingerprint().take(16)}")
    output.appendLine("$ $script")
    onUpdate(output.toString())

    runner.execute(command).collect { event ->
        when (event) {
            is RunnerEvent.Started -> Unit
            is RunnerEvent.StandardOutput ->
                output.append(event.bytes.toString(Charsets.UTF_8))

            is RunnerEvent.StandardError ->
                output.append("[stderr] ${event.bytes.toString(Charsets.UTF_8)}")

            is RunnerEvent.Completed ->
                output.appendLine("[exit ${event.exitCode}]")

            is RunnerEvent.Rejected ->
                output.appendLine("[rejected] ${event.reason}")

            is RunnerEvent.Interrupted ->
                output.appendLine("[interrupted] ${event.reason}")
        }
        onUpdate(output.toString())
    }
    return output.toString()
}

private suspend fun runSafeDiagnostic(
    profile: DistributionProfile,
    runner: AndroidSafeRunner,
    workspacePath: String,
    onUpdate: (String) -> Unit,
): String {
    val request = ToolRequest(
        id = "local-device-diagnostic",
        toolName = "android.bounded_diagnostic",
        capabilities = setOf(
            Capability.PROCESS_INSPECT,
            Capability.SHELL_EXECUTE,
        ),
        effect = ToolEffect.READ_ONLY,
        workspaceScoped = true,
        reversible = true,
        summary = "Read app identity, kernel, working directory, and Android properties",
    )
    val decision = PolicyEngine().evaluate(
        context = PolicyContext(
            distribution = profile,
            mode = ExecutionMode.OBSERVE,
            environmentTrust = EnvironmentTrust.ANDROID_APP,
        ),
        request = request,
    )

    val output = StringBuilder()
    output.appendLine("Policy: ${decision::class.simpleName}")
    output.appendLine(decision.reason)
    onUpdate(output.toString())

    if (decision !is PolicyDecision.Allow) return output.toString()

    val boundaryProbes = listOf(
        CommandSpec(
            id = "boundary-arbitrary-shell",
            executable = "/system/bin/sh",
            arguments = listOf("-c", "id"),
            workingDirectory = workspacePath,
            timeoutMillis = 2_000,
            outputLimitBytes = 8_192,
            idempotent = true,
        ),
        CommandSpec(
            id = "boundary-directory-escape",
            executable = "/system/bin/id",
            arguments = emptyList(),
            workingDirectory = "/data/local/tmp",
            timeoutMillis = 2_000,
            outputLimitBytes = 8_192,
            idempotent = true,
        ),
        CommandSpec(
            id = "boundary-environment-injection",
            executable = "/system/bin/id",
            arguments = emptyList(),
            workingDirectory = workspacePath,
            environmentHandles = mapOf("PATH" to "/data/local/tmp"),
            timeoutMillis = 2_000,
            outputLimitBytes = 8_192,
            idempotent = true,
        ),
    )
    val blockedProbeCount = boundaryProbes.count { probe ->
        runnerRejects(runner, probe)
    }
    output.appendLine("Boundary checks: $blockedProbeCount/${boundaryProbes.size} blocked")
    onUpdate(output.toString())
    if (blockedProbeCount != boundaryProbes.size) {
        output.appendLine("[aborted] Runner boundary self-test failed")
        onUpdate(output.toString())
        return output.toString()
    }

    val commands = listOf(
        "/system/bin/id" to emptyList(),
        "/system/bin/uname" to listOf("-a"),
        "/system/bin/pwd" to emptyList(),
        "/system/bin/getprop" to listOf("ro.product.model"),
        "/system/bin/getprop" to listOf("ro.build.version.release"),
        "/system/bin/getprop" to listOf("ro.build.version.sdk"),
    )

    commands.forEachIndexed { index, (executable, arguments) ->
        output.append("\n$ ${executable.substringAfterLast('/')} ${arguments.joinToString(" ")}\n")
        onUpdate(output.toString())
        runner.execute(
            CommandSpec(
                id = "device-diagnostic-$index",
                executable = executable,
                arguments = arguments,
                workingDirectory = workspacePath,
                timeoutMillis = 2_000,
                outputLimitBytes = 8_192,
                idempotent = true,
            ),
        ).collect { event ->
            when (event) {
                is RunnerEvent.Started -> Unit
                is RunnerEvent.StandardOutput ->
                    output.append(event.bytes.toString(Charsets.UTF_8))

                is RunnerEvent.StandardError ->
                    output.append("[stderr] ${event.bytes.toString(Charsets.UTF_8)}")

                is RunnerEvent.Completed ->
                    output.appendLine("[exit ${event.exitCode}]")

                is RunnerEvent.Rejected ->
                    output.appendLine("[rejected] ${event.reason}")

                is RunnerEvent.Interrupted ->
                    output.appendLine("[interrupted] ${event.reason}")
            }
            onUpdate(output.toString())
        }
    }
    return output.toString()
}

private suspend fun runnerRejects(
    runner: ExecutionRunner,
    command: CommandSpec,
): Boolean {
    var rejected = false
    var unexpectedEvent = false
    runner.execute(command).collect { event ->
        if (event is RunnerEvent.Rejected) {
            rejected = true
        } else {
            unexpectedEvent = true
        }
    }
    return rejected && !unexpectedEvent
}

private suspend fun runPowerShellProbe(
    profile: DistributionProfile,
    runner: ExecutionRunner,
    command: CommandSpec,
    onUpdate: (String) -> Unit,
): String {
    val request = ToolRequest(
        id = command.id,
        toolName = "power.android_shell_probe",
        capabilities = setOf(
            Capability.FILE_READ,
            Capability.FILE_WRITE,
            Capability.PROCESS_INSPECT,
            Capability.SHELL_EXECUTE,
        ),
        effect = ToolEffect.WORKSPACE_MUTATION,
        workspaceScoped = true,
        reversible = true,
        summary = "Executar a sonda assinada do shell Android e remover o arquivo temporário",
    )
    val decision = PolicyEngine().evaluate(
        context = PolicyContext(
            distribution = profile,
            mode = ExecutionMode.AUTO,
            environmentTrust = EnvironmentTrust.POWER_USERSPACE,
        ),
        request = request,
    )

    val output = StringBuilder()
    output.appendLine("Política do shell: ${decision::class.simpleName}")
    output.appendLine(decision.reason)
    onUpdate(output.toString())
    if (decision !is PolicyDecision.Allow) return output.toString()

    val arbitraryScriptRejected = runnerRejects(
        runner = runner,
        command = command.copy(
            id = "power-boundary-arbitrary-script",
            arguments = listOf("-c", "id"),
        ),
    )
    output.appendLine(
        if (arbitraryScriptRejected) {
            "Limite de segurança: OK (script sem autorização bloqueado)"
        } else {
            "Limite de segurança: FALHA (script sem autorização alcançou o executor)"
        },
    )
    onUpdate(output.toString())
    if (!arbitraryScriptRejected) return output.toString()

    output.appendLine()
    output.appendLine("$ /system/bin/sh -c <signed workspace probe>")
    onUpdate(output.toString())
    runner.execute(command).collect { event ->
        when (event) {
            is RunnerEvent.Started -> Unit
            is RunnerEvent.StandardOutput ->
                output.append(event.bytes.toString(Charsets.UTF_8))

            is RunnerEvent.StandardError ->
                output.append("[stderr] ${event.bytes.toString(Charsets.UTF_8)}")

            is RunnerEvent.Completed ->
                output.appendLine("[exit ${event.exitCode}]")

            is RunnerEvent.Rejected ->
                output.appendLine("[rejected] ${event.reason}")

            is RunnerEvent.Interrupted ->
                output.appendLine("[interrupted] ${event.reason}")
        }
        onUpdate(output.toString())
    }
    return output.toString()
}
