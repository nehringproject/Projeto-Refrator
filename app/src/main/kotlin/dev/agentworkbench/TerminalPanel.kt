package dev.agentworkbench

import android.content.ClipData
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import com.termux.terminal.TerminalColors
import com.termux.terminal.TextStyle
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File
import java.lang.ref.WeakReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Interactive shells on a real PTY, backed by the same relocated Termux userland the agent uses.
 *
 * Sessions live for the whole process (not the composition), so switching tabs or rotating the
 * screen keeps every shell running. ELF binaries still start through /system/bin/linker64 and the
 * termux-exec preload, exactly like [EmbeddedRuntimePackManager], so targetSdk 36 W^X stays intact.
 */
internal object EmbeddedTerminalSessions {
    init {
        // The scheme is global and is also what `reset`/`tput reset` restore, so set it once here,
        // before any emulator is created: app black, soft white text, one red cursor detail.
        TerminalColors.COLOR_SCHEME.mDefaultColors.apply {
            this[TextStyle.COLOR_INDEX_BACKGROUND] = 0xFF070707.toInt()
            this[TextStyle.COLOR_INDEX_FOREGROUND] = 0xFFE6E6E6.toInt()
            this[TextStyle.COLOR_INDEX_CURSOR] = 0xFFE5383B.toInt()
        }
    }

    val sessions = mutableStateListOf<TerminalSession>()
    var selected by mutableIntStateOf(0)
    var ctrlArmed by mutableStateOf(false)
    var altArmed by mutableStateOf(false)
    private var viewRef = WeakReference<TerminalView>(null)
    private var appContext: Context? = null

    val current: TerminalSession? get() = sessions.getOrNull(selected)

    fun attach(view: TerminalView) {
        viewRef = WeakReference(view)
        appContext = view.context.applicationContext
        current?.let(view::attachSession)
    }

    fun select(index: Int) {
        if (index !in sessions.indices) return
        selected = index
        viewRef.get()?.attachSession(sessions[index])
    }

    fun open(context: Context, workspace: File): Boolean {
        val runtime = DistributionBindings.runtimePackBridge(
            context.applicationContext,
            internalRuntimeRoot(context),
            workspace,
        )
        // commandLine() is [linker64, bash, ...] once the Linux base exists, null before that.
        val probe = runtime.commandLine("true") ?: return false
        val linker = probe[0]
        val bash = probe[1]
        val environment = HashMap(System.getenv())
        runtime.configureEnvironment(environment)
        environment["TERM"] = "xterm-256color"
        environment["COLORTERM"] = "truecolor"
        environment["LANG"] = "C.UTF-8"
        environment["SHELL"] = bash
        val home = File(environment.getValue("HOME")).apply { mkdirs() }
        val rcFile = File(home, RC_FILE_NAME).apply {
            writeText(context.assets.open(RC_ASSET).bufferedReader().use { it.readText() })
        }
        environment["REFRATOR_WORKSPACE"] = workspace.canonicalPath
        environment["ENV"] = rcFile.path
        // CLI agents (Claude Code, OpenCode…) reach Refrator's tools and the Android browser here.
        val shimBin = CliBridgeShims.install(home, bash)
        // Bundled commands (refrator-pkg-install, refrator-linux…), refreshed on every tab so app
        // updates reach existing installs. The shebang points at this runtime's bash.
        context.assets.list(SCRIPTS_ASSET_DIR).orEmpty().forEach { name ->
            File(shimBin, name).apply {
                val body = context.assets.open("$SCRIPTS_ASSET_DIR/$name").bufferedReader().use { it.readText() }
                writeText("#!$bash\n$body")
                setExecutable(true, true)
            }
        }
        // refrator-linux needs direct execve(), which only the targetSdk 28 Dev build has.
        environment["REFRATOR_TARGET_SDK"] = context.applicationInfo.targetSdkVersion.toString()
        environment["PATH"] = "${shimBin.path}:${environment["PATH"].orEmpty()}"
        environment["BROWSER"] = "refrator-open"
        runCatching { CliBridge.ensureStarted(context, workspace) }.getOrNull()?.let { bridge ->
            environment["REFRATOR_BRIDGE_URL"] = bridge.url
            environment["REFRATOR_BRIDGE_TOKEN"] = bridge.token
            // MCP clients may spawn refrator-mcp without the shell's environment.
            File(home, ".refrator/bridge.env").writeText(
                "export REFRATOR_BRIDGE_URL='${bridge.url}'\nexport REFRATOR_BRIDGE_TOKEN='${bridge.token}'\n",
            )
        }
        workspace.mkdirs()
        val session = TerminalSession(
            linker,
            workspace.path,
            // --rcfile would still make Termux's bash read its compiled-in system bashrc under
            // /data/data/com.termux ("Permission denied" when Termux is installed). With --norc and
            // POSIX mode, bash sources $ENV instead; the rc's first line turns POSIX mode back off.
            arrayOf(linker, bash, "--noprofile", "--norc", "--posix", "-i"),
            environment.map { (key, value) -> "$key=$value" }.toTypedArray(),
            TRANSCRIPT_ROWS,
            sessionClient,
        )
        sessions += session
        select(sessions.lastIndex)
        return true
    }

    fun close(session: TerminalSession) {
        session.finishIfRunning()
        remove(session)
    }

    private fun remove(session: TerminalSession) {
        val index = sessions.indexOf(session)
        if (index < 0) return
        sessions.removeAt(index)
        if (sessions.isEmpty()) {
            selected = 0
        } else {
            select(selected.coerceAtMost(sessions.lastIndex))
        }
    }

    /** CTRL/ALT from the extra-keys row apply to exactly one following key, like Termux. */
    fun consumeCtrl(): Boolean = ctrlArmed.also { ctrlArmed = false }
    fun consumeAlt(): Boolean = altArmed.also { altArmed = false }

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            val view = viewRef.get() ?: return
            if (view.currentSession === changedSession) view.onScreenUpdated()
        }

        override fun onTitleChanged(changedSession: TerminalSession) = Unit

        override fun onSessionFinished(finishedSession: TerminalSession) {
            // `exit` closes the tab instead of leaving a dead screen behind.
            remove(finishedSession)
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            val context = appContext ?: return
            context.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("Terminal", text))
        }

        override fun onPasteTextFromClipboard(session: TerminalSession) {
            val context = appContext ?: return
            val text = context.getSystemService(ClipboardManager::class.java)
                ?.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(context)
                ?.toString()
                ?: return
            session.emulator?.paste(text)
        }

        override fun onBell(session: TerminalSession) = Unit
        override fun onColorsChanged(session: TerminalSession) = Unit
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
        override fun logStackTrace(tag: String?, e: Exception?) = Unit
    }

    private const val TRANSCRIPT_ROWS = 4_000
    private const val RC_FILE_NAME = ".refrator_bashrc"

    // Banner, two-line prompt and aliases live in assets/terminal/refrator_bashrc; rewritten on
    // every new tab so app updates reach existing installs. Personal tweaks belong in ~/.bashrc.
    private const val RC_ASSET = "terminal/refrator_bashrc"
    private const val SCRIPTS_ASSET_DIR = "terminal/bin"
}

private sealed interface ExtraKey {
    val label: String

    data class Key(override val label: String, val keyCode: Int) : ExtraKey
    data class Text(override val label: String, val value: String) : ExtraKey
    data object Ctrl : ExtraKey { override val label = "CTRL" }
    data object Alt : ExtraKey { override val label = "ALT" }
}

private val extraKeys = listOf(
    ExtraKey.Key("ESC", KeyEvent.KEYCODE_ESCAPE),
    ExtraKey.Key("TAB", KeyEvent.KEYCODE_TAB),
    ExtraKey.Ctrl,
    ExtraKey.Alt,
    ExtraKey.Key("←", KeyEvent.KEYCODE_DPAD_LEFT),
    ExtraKey.Key("↓", KeyEvent.KEYCODE_DPAD_DOWN),
    ExtraKey.Key("↑", KeyEvent.KEYCODE_DPAD_UP),
    ExtraKey.Key("→", KeyEvent.KEYCODE_DPAD_RIGHT),
    ExtraKey.Text("-", "-"),
    ExtraKey.Text("/", "/"),
    ExtraKey.Text("|", "|"),
    ExtraKey.Text("~", "~"),
    ExtraKey.Key("HOME", KeyEvent.KEYCODE_MOVE_HOME),
    ExtraKey.Key("END", KeyEvent.KEYCODE_MOVE_END),
)

@Composable
internal fun TerminalPanel(workspacePath: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Same folder the chat agent uses (ChatPanel: filesDir/workspace), so both see the same files.
    val workspace = remember(workspacePath) { File(workspacePath, "workspace") }
    var terminalView by remember { mutableStateOf<TerminalView?>(null) }
    var installing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val sessions = EmbeddedTerminalSessions.sessions

    fun openTab() {
        error = null
        runCatching { EmbeddedTerminalSessions.open(context, workspace) }
            .onSuccess { opened ->
                if (!opened) error = "Instale o Linux base para abrir um terminal."
            }
            .onFailure { error = it.message ?: "Falha ao abrir o shell." }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WorkbenchTokens.Canvas),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            sessions.forEachIndexed { index, session ->
                val active = index == EmbeddedTerminalSessions.selected
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (active) WorkbenchTokens.SurfaceHigh else Color.Transparent)
                        .border(1.dp, WorkbenchTokens.BorderSoft, RoundedCornerShape(50))
                        .clickable { EmbeddedTerminalSessions.select(index) }
                        .animateContentSize()
                        .padding(start = 12.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (active) {
                        Box(modifier = Modifier.size(6.dp).background(InkTokens.Glint, CircleShape))
                    }
                    Text(
                        text = session.title?.takeIf(String::isNotBlank) ?: "bash ${index + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = if (active) WorkbenchTokens.Text else WorkbenchTokens.TextMuted,
                        maxLines = 1,
                        modifier = Modifier.widthIn(max = 140.dp),
                    )
                    Text(
                        text = "×",
                        color = WorkbenchTokens.TextMuted,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { EmbeddedTerminalSessions.close(session) }
                            .padding(horizontal = 6.dp)
                            .semantics { contentDescription = "Fechar terminal ${index + 1}" },
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(WorkbenchTokens.SurfaceHigh)
                    .clickable(enabled = !installing) { openTab() }
                    .semantics { contentDescription = "Nova aba" },
                contentAlignment = Alignment.Center,
            ) {
                Text("+", style = MaterialTheme.typography.titleMedium, color = WorkbenchTokens.Text)
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (sessions.isEmpty()) {
                TerminalSetup(
                    installing = installing,
                    progress = progress,
                    error = error,
                    onOpen = ::openTab,
                    onInstall = {
                        installing = true
                        error = null
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    DistributionBindings.runtimePackBridge(
                                        context.applicationContext,
                                        internalRuntimeRoot(context),
                                        workspace,
                                    ).install("linux-base") { progress = it }
                                }
                            }.onSuccess {
                                progress = null
                                openTab()
                            }.onFailure {
                                error = "Falha na instalação: ${it.message ?: it::class.java.simpleName}"
                            }
                            installing = false
                        }
                    },
                )
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext ->
                        TerminalView(viewContext, null).apply {
                            setTerminalViewClient(TerminalClient(this))
                            // setTextSize creates the renderer (monospace by default); setTypeface
                            // dereferences it, so calling that first crashed with an NPE.
                            setTextSize((TEXT_SIZE_SP * resources.displayMetrics.scaledDensity).toInt())
                            setBackgroundColor(0xFF070707.toInt())
                            isFocusable = true
                            isFocusableInTouchMode = true
                            keepScreenOn = true
                            EmbeddedTerminalSessions.attach(this)
                            terminalView = this
                        }
                    },
                    update = { view ->
                        val session = EmbeddedTerminalSessions.current
                        if (session != null && view.currentSession !== session) view.attachSession(session)
                    },
                )
            }
        }

        if (sessions.isNotEmpty()) {
            ExtraKeysRow(terminalView)
        }
    }

    DisposableEffect(Unit) {
        onDispose { terminalView = null }
    }
}

@Composable
private fun TerminalSetup(
    installing: Boolean,
    progress: String?,
    error: String?,
    onOpen: () -> Unit,
    onInstall: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The brand mark floating in ink; the ink moves faster while something installs.
        Box(modifier = Modifier.size(120.dp), contentAlignment = Alignment.Center) {
            InkBlobs(
                modifier = Modifier.matchParentSize(),
                intensity = if (installing) 1f else 0.55f,
                periodMillis = if (installing) 3_200 else 8_000,
            )
            Icon(
                painter = painterResource(R.drawable.ic_refrator_mark),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = WorkbenchTokens.Gold,
            )
        }
        Text(
            text = "terminal",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = WorkbenchTokens.Text,
        )
        Text(
            text = "Bash com apt/pkg dentro do Refrator, sem root. Mesmo workspace do agente; " +
                "rode ubuntu para um Linux completo.",
            style = MaterialTheme.typography.bodyMedium,
            color = WorkbenchTokens.TextMuted,
            textAlign = TextAlign.Center,
        )
        if (installing) {
            AnimatedContent(
                targetState = progress ?: "Preparando…",
                transitionSpec = {
                    (fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 2 }) togetherWith
                        (fadeOut(tween(180)) + slideOutVertically(tween(180)) { -it / 2 })
                },
                label = "installProgress",
            ) { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = WorkbenchTokens.TextMuted,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Button(
                onClick = onOpen,
                colors = ButtonDefaults.buttonColors(
                    containerColor = WorkbenchTokens.Text,
                    contentColor = WorkbenchTokens.Canvas,
                ),
            ) { Text("Abrir terminal") }
            TextButton(onClick = onInstall) {
                Text("Instalar Linux base (≈32 MB)", color = WorkbenchTokens.TextMuted)
            }
        }
        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ExtraKeysRow(view: TerminalView?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(WorkbenchTokens.Surface)
            .drawBehind { drawLine(WorkbenchTokens.BorderSoft, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        extraKeys.forEach { key ->
            val armed = when (key) {
                ExtraKey.Ctrl -> EmbeddedTerminalSessions.ctrlArmed
                ExtraKey.Alt -> EmbeddedTerminalSessions.altArmed
                else -> false
            }
            Text(
                text = key.label,
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
                color = WorkbenchTokens.Text,
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (armed) InkTokens.Mid else Color.Transparent)
                    .clickable {
                        when (key) {
                            ExtraKey.Ctrl -> EmbeddedTerminalSessions.ctrlArmed = !EmbeddedTerminalSessions.ctrlArmed
                            ExtraKey.Alt -> EmbeddedTerminalSessions.altArmed = !EmbeddedTerminalSessions.altArmed
                            is ExtraKey.Key -> view?.let { target ->
                                target.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key.keyCode))
                                target.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key.keyCode))
                            }
                            is ExtraKey.Text -> EmbeddedTerminalSessions.current?.let { session ->
                                key.value.codePoints().forEach { session.writeCodePoint(false, it) }
                            }
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

private class TerminalClient(private val view: TerminalView) : TerminalViewClient {
    override fun onScale(scale: Float): Float {
        // Pinch-to-zoom: change the font size once the gesture has moved far enough.
        if (scale < 0.9f || scale > 1.1f) {
            val density = view.resources.displayMetrics.scaledDensity
            currentSizeSp = (currentSizeSp * if (scale > 1f) 1.1f else 0.9f).coerceIn(MIN_SP, MAX_SP)
            view.setTextSize((currentSizeSp * density).toInt())
            return 1f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent?) {
        view.requestFocus()
        view.context.getSystemService(InputMethodManager::class.java)
            ?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    // Avoids IME composition (autocorrect, swipe) mangling shell input.
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
    override fun onLongPress(event: MotionEvent?): Boolean = false
    override fun readControlKey(): Boolean = EmbeddedTerminalSessions.consumeCtrl()
    override fun readAltKey(): Boolean = EmbeddedTerminalSessions.consumeAlt()
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
    override fun onEmulatorSet() = Unit
    override fun logError(tag: String?, message: String?) = Unit
    override fun logWarn(tag: String?, message: String?) = Unit
    override fun logInfo(tag: String?, message: String?) = Unit
    override fun logDebug(tag: String?, message: String?) = Unit
    override fun logVerbose(tag: String?, message: String?) = Unit
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
    override fun logStackTrace(tag: String?, e: Exception?) = Unit

    private var currentSizeSp = TEXT_SIZE_SP

    private companion object {
        const val MIN_SP = 8f
        const val MAX_SP = 28f
    }
}

private const val TEXT_SIZE_SP = 13f
