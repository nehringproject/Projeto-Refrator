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
import androidx.compose.runtime.DisposableEffect
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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        DistributionBindings.scheduleExecutionRecovery(applicationContext)
        setContent {
            WorkbenchTheme {
                AgentWorkbenchShell(
                    profile = DistributionBindings.profile(),
                    workspacePath = applicationContext.filesDir.absolutePath,
                )
            }
        }
    }

    override fun onDestroy() {
        AgentBrowserSession.release(this)
        super.onDestroy()
    }
}

private enum class WorkbenchDestination {
    CHAT,
    TOOLS,
    SETTINGS,
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
    var destination by remember { mutableStateOf(WorkbenchDestination.CHAT) }
    var settingsReturn by remember { mutableStateOf(WorkbenchDestination.CHAT) }
    var sessions by remember { mutableStateOf<List<ChatSessionSummary>>(emptyList()) }
    var activeSessionId by remember { mutableStateOf<String?>(null) }
    var historyOpen by remember { mutableStateOf(false) }
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
        if (destination != WorkbenchDestination.SETTINGS) {
            settingsReturn = destination
        }
        destination = WorkbenchDestination.SETTINGS
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

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            // Edge-to-edge no Android 15 entrega o IME como inset, mas não reduz sozinho a
            // árvore Compose. Este é o único consumidor. A navegação inferior é um overlay fora
            // do Scaffold, então sua altura nunca é somada à altura do teclado.
            modifier = Modifier.imePadding(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = WorkbenchTokens.Canvas,
            topBar = {
            GlassSurface(shape = RectangleShape) {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Image(
                            painter = painterResource(R.drawable.refrator_icon_art),
                            contentDescription = null,
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                        Column {
                            Text(
                                text = when (destination) {
                                    WorkbenchDestination.CHAT -> "Refrator"
                                    WorkbenchDestination.TOOLS -> "Ferramentas"
                                    WorkbenchDestination.SETTINGS -> "Configurações"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = if (destination == WorkbenchDestination.TOOLS) {
                                        "Nehring Project · Android"
                                    } else {
                                        "Nehring Project"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (destination == WorkbenchDestination.CHAT) {
                                    Box(
                                        modifier = Modifier
                                            .size(5.dp)
                                            .background(
                                                color = WorkbenchTokens.Green,
                                                shape = RoundedCornerShape(50),
                                            ),
                                    )
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    when (destination) {
                        WorkbenchDestination.SETTINGS -> IconButton(onClick = { destination = settingsReturn }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_back),
                                contentDescription = "Voltar",
                            )
                        }

                        WorkbenchDestination.CHAT -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .padding(start = 2.dp)
                                    .size(width = 3.dp, height = 16.dp)
                                    .background(WorkbenchTokens.TextFaint, RoundedCornerShape(50)),
                            )
                            IconButton(
                                enabled = !chatBusy,
                                onClick = {
                                    libraryView = ChatLibraryView.ACTIVE
                                    scope.launch { sessions = sessionRepository.list() }
                                    openHistory()
                                },
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_history),
                                    contentDescription = "Conversas",
                                )
                            }
                        }

                        WorkbenchDestination.TOOLS -> Unit
                    }
                },
                actions = {
                    if (destination == WorkbenchDestination.CHAT) {
                        IconButton(
                            enabled = !chatBusy,
                            onClick = ::createNewChat,
                        ) {
                            Text(
                                text = "+",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Normal,
                                color = WorkbenchTokens.TextMuted,
                            )
                        }
                    }
                    if (destination != WorkbenchDestination.SETTINGS) {
                        IconButton(onClick = ::openSettings) {
                            Icon(
                                painter = painterResource(R.drawable.ic_settings),
                                contentDescription = "Configurações",
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = WorkbenchTokens.Text,
                    actionIconContentColor = WorkbenchTokens.TextMuted,
                    navigationIconContentColor = WorkbenchTokens.TextMuted,
                ),
                )
            }
            },
        ) { padding ->
            Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(
                    bottom = if (destination != WorkbenchDestination.SETTINGS && !imeVisible) {
                        132.dp
                    } else {
                        0.dp
                    },
                ),
            color = WorkbenchTokens.Canvas,
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    DestinationSlot(destination == WorkbenchDestination.CHAT) {
                        val sessionId = activeSessionId
                        if (sessionId == null) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("Carregando conversas…")
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
                                onOpenSettings = ::openSettings,
                                onSessionSaved = ::mergeSummary,
                                onBusyChanged = { chatBusy = it },
                            )
                        }
                    }
                    DestinationSlot(destination == WorkbenchDestination.TOOLS) {
                        ToolsPanel(
                            profile = profile,
                            workspacePath = workspacePath,
                        )
                    }
                    DestinationSlot(destination == WorkbenchDestination.SETTINGS) {
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

        // A navegação é realmente flutuante: fica sobre o Scaffold e nunca participa da
        // medição dele. Assim não existe altura antiga para permanecer reservada sob o composer.
        if (destination != WorkbenchDestination.SETTINGS && !imeVisible) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 52.dp, vertical = 8.dp),
            ) {
                GlassSurface(
                    shape = RoundedCornerShape(26.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    NavigationBar(
                        modifier = Modifier.height(70.dp),
                        containerColor = Color.Transparent,
                        tonalElevation = 0.dp,
                    ) {
                        NavigationBarItem(
                            selected = destination == WorkbenchDestination.CHAT,
                            onClick = { destination = WorkbenchDestination.CHAT },
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_chat),
                                    contentDescription = null,
                                )
                            },
                            label = { Text("Chat") },
                            colors = workbenchNavigationColors(),
                        )
                        NavigationBarItem(
                            selected = destination == WorkbenchDestination.TOOLS,
                            onClick = { destination = WorkbenchDestination.TOOLS },
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_terminal),
                                    contentDescription = null,
                                )
                            },
                            label = { Text("Ferramentas") },
                            colors = workbenchNavigationColors(),
                        )
                    }
                }
            }
        }

        if (historyOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x66000000))
                    .clickable { closeHistory() },
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .offset { IntOffset(x = historyOffset.roundToInt(), y = 0) }
                .pointerInput(historyPanelWidthPx) {
                    detectHorizontalDragGestures(
                        onDragEnd = { settleHistoryDrag() },
                        onDragCancel = { settleHistoryDrag() },
                    ) { change, dragAmount ->
                        change.consume()
                        historyOffset = (historyOffset + dragAmount)
                            .coerceIn(-historyPanelWidthPx, 0f)
                    }
                },
        ) {
            ChatHistoryPanel(
                sessions = sessions,
                activeSessionId = activeSessionId,
                busy = chatBusy,
                libraryView = libraryView,
                onClose = { closeHistory() },
                onNewChat = {
                    closeHistory()
                    createNewChat()
                },
                onSelect = { id ->
                    if (!chatBusy) {
                        activeSessionId = id
                        destination = WorkbenchDestination.CHAT
                        closeHistory()
                    }
                },
                onDelete = { id ->
                    if (!chatBusy) {
                        scope.launch {
                            if (sessionRepository.delete(id)) {
                                if (libraryView == ChatLibraryView.ACTIVE) {
                                    val remaining = sessionRepository.list()
                                    if (remaining.isEmpty()) {
                                        val created = sessionRepository.create(settings)
                                        sessions = listOf(created.summary)
                                        activeSessionId = created.summary.id
                                    } else {
                                        sessions = remaining
                                        if (activeSessionId == id) activeSessionId = remaining.first().id
                                    }
                                } else {
                                    sessions = when (libraryView) {
                                        ChatLibraryView.ARCHIVED -> sessionRepository.archived()
                                        ChatLibraryView.TRASH -> sessionRepository.trashed()
                                        ChatLibraryView.ACTIVE -> sessionRepository.list()
                                    }
                                }
                            }
                        }
                    }
                },
                onRename = { id, title ->
                    if (!chatBusy) {
                        scope.launch { sessionRepository.rename(id, title)?.let(::mergeSummary) }
                    }
                },
                onPin = { id, pinned ->
                    if (!chatBusy) {
                        scope.launch { sessionRepository.pin(id, pinned)?.let(::mergeSummary) }
                    }
                },
                onArchive = { id ->
                    if (!chatBusy) {
                        scope.launch {
                            if (sessionRepository.archive(id, true)) {
                                sessions = sessionRepository.list()
                                if (activeSessionId == id) activeSessionId = sessions.firstOrNull()?.id
                            }
                        }
                    }
                },
                onRestore = { id ->
                    if (!chatBusy) {
                        scope.launch {
                            when (libraryView) {
                                ChatLibraryView.ARCHIVED -> sessionRepository.archive(id, false)
                                ChatLibraryView.TRASH -> sessionRepository.restore(id)
                                ChatLibraryView.ACTIVE -> false
                            }
                            sessions = when (libraryView) {
                                ChatLibraryView.ACTIVE -> sessionRepository.list()
                                ChatLibraryView.ARCHIVED -> sessionRepository.archived()
                                ChatLibraryView.TRASH -> sessionRepository.trashed()
                            }
                        }
                    }
                },
                onLibraryView = { view ->
                    libraryView = view
                    scope.launch {
                        sessions = when (view) {
                            ChatLibraryView.ACTIVE -> sessionRepository.list()
                            ChatLibraryView.ARCHIVED -> sessionRepository.archived()
                            ChatLibraryView.TRASH -> sessionRepository.trashed()
                        }
                    }
                },
                onSearch = { query ->
                    if (libraryView == ChatLibraryView.ACTIVE) {
                        scope.launch { sessions = sessionRepository.search(query) }
                    }
                },
            )
        }
        if (destination == WorkbenchDestination.CHAT && !historyOpen) {
            // Faixa fina e sempre presente na borda esquerda: puxar da esquerda pro meio
            // abre o histórico com o mesmo gesto que fecha ele arrastando de volta.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(32.dp)
                    .pointerInput(historyPanelWidthPx) {
                        detectHorizontalDragGestures(
                            onDragEnd = { settleHistoryDrag() },
                            onDragCancel = { settleHistoryDrag() },
                        ) { change, dragAmount ->
                            change.consume()
                            historyOffset = (historyOffset + dragAmount)
                                .coerceIn(-historyPanelWidthPx, 0f)
                        }
                    },
            )
        }
    }
}

@Composable
private fun workbenchNavigationColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = WorkbenchTokens.Green,
    selectedTextColor = WorkbenchTokens.Green,
    indicatorColor = WorkbenchTokens.GreenSoft,
    unselectedIconColor = WorkbenchTokens.TextMuted,
    unselectedTextColor = WorkbenchTokens.TextMuted,
)

private fun ExecutionMode.displayName(): String = when (this) {
    ExecutionMode.FULL -> "Livre"
    ExecutionMode.AUTO -> "Auto"
    ExecutionMode.BUILD -> "Confirmar"
    ExecutionMode.PLAN -> "Planejar"
    ExecutionMode.OBSERVE -> "Observar"
}

@Composable
private fun ChatHistoryPanel(
    sessions: List<ChatSessionSummary>,
    activeSessionId: String?,
    busy: Boolean,
    libraryView: ChatLibraryView,
    onClose: () -> Unit,
    onNewChat: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onPin: (String, Boolean) -> Unit,
    onArchive: (String) -> Unit,
    onRestore: (String) -> Unit,
    onLibraryView: (ChatLibraryView) -> Unit,
    onSearch: (String) -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var editingSession by remember { mutableStateOf<ChatSessionSummary?>(null) }
    var titleDraft by remember { mutableStateOf("") }
    Surface(
        onClick = {},
        modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth(0.88f),
        color = WorkbenchTokens.Navigation.copy(alpha = 0.98f),
        shape = RoundedCornerShape(topEnd = 22.dp),
        tonalElevation = 0.dp,
        shadowElevation = 18.dp,
        border = BorderStroke(1.dp, glassEdgeBrush()),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 48.dp, start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Missões",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = WorkbenchTokens.Text,
                    )
                    Text(
                        text = "${sessions.size} salvas no aparelho",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onClose) {
                    Text("Fechar")
                }
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                onClick = onNewChat,
            ) {
                Text("＋  Nova missão")
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChatLibraryView.entries.forEach { view ->
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                        onClick = { onLibraryView(view) },
                    ) {
                        Text(
                            text = when (view) {
                                ChatLibraryView.ACTIVE -> "Ativos"
                                ChatLibraryView.ARCHIVED -> "Arquivo"
                                ChatLibraryView.TRASH -> "Lixeira"
                            },
                            color = if (view == libraryView) WorkbenchTokens.Green else WorkbenchTokens.TextMuted,
                        )
                    }
                }
            }
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { value ->
                    searchQuery = value
                    onSearch(value)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && libraryView == ChatLibraryView.ACTIVE,
                label = { Text("Buscar em títulos e mensagens") },
                singleLine = true,
            )
            if (busy) {
                Text(
                    text = "Finalize ou cancele a execução antes de trocar de conversa.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            HorizontalDivider()
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(sessions, key = { it.id }) { session ->
                    val selected = session.id == activeSessionId
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) {
                                WorkbenchTokens.SurfaceHigh
                            } else {
                                WorkbenchTokens.Surface
                            },
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (selected) WorkbenchTokens.Gold else WorkbenchTokens.Border,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Text(
                                text = session.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = WorkbenchTokens.Text,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${session.providerName} · ${session.modelId}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = DateFormat.getDateTimeInstance(
                                    DateFormat.SHORT,
                                    DateFormat.SHORT,
                                ).format(Date(session.updatedAtMillis)) +
                                    " · ${session.messageCount} mensagens",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (libraryView == ChatLibraryView.ACTIVE) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    TextButton(enabled = !busy, onClick = { onPin(session.id, !session.pinned) }) {
                                        Text(if (session.pinned) "Desafixar" else "Fixar")
                                    }
                                    TextButton(enabled = !busy, onClick = {
                                        editingSession = session
                                        titleDraft = session.title
                                    }) { Text("Renomear") }
                                    TextButton(enabled = !busy, onClick = { onArchive(session.id) }) {
                                        Text("Arquivar")
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    TextButton(enabled = !busy, onClick = { onDelete(session.id) }) {
                                        Text("Lixeira")
                                    }
                                    TextButton(enabled = !busy && !selected, onClick = { onSelect(session.id) }) {
                                        Text(if (selected) "Atual" else "Abrir")
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    TextButton(enabled = !busy, onClick = { onRestore(session.id) }) {
                                        Text("Restaurar")
                                    }
                                    if (libraryView == ChatLibraryView.ARCHIVED) {
                                        TextButton(enabled = !busy, onClick = { onDelete(session.id) }) {
                                            Text("Mover à lixeira")
                                        }
                                    } else {
                                        Text(
                                            "Exclusão automática em 30 dias",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = WorkbenchTokens.TextMuted,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    editingSession?.let { session ->
        AlertDialog(
            onDismissRequest = { editingSession = null },
            title = { Text("Renomear missão") },
            text = {
                OutlinedTextField(
                    value = titleDraft,
                    onValueChange = { titleDraft = it },
                    label = { Text("Título") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = titleDraft.isNotBlank(),
                    onClick = {
                        onRename(session.id, titleDraft)
                        editingSession = null
                    },
                ) { Text("Salvar") }
            },
            dismissButton = {
                TextButton(onClick = { editingSession = null }) { Text("Cancelar") }
            },
        )
    }
}

@Composable
private fun DestinationSlot(
    active: Boolean,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = if (active) {
            Modifier.fillMaxSize()
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
