package dev.agentworkbench

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------------
// Memória: the notebook of one prisma as the user sees it. Only the prisma and the user read it.
// Every write goes through [PrismaNotebook]; the screen reloads the notebook after each change.
// ---------------------------------------------------------------------------------------------

/** How many one-second checks the summary refresh makes, waiting for a changed summary. */
private const val MEMORY_POLL_ROUNDS = 45

/** The source recorded for notes the user writes or edits on this screen. */
private const val MEMORY_USER_SOURCE = "usuário"

private val memoryKindFilters: List<Pair<String, NoteKind?>> = listOf(
    "Tudo" to null,
    "Compromissos" to NoteKind.TASK,
    "Fatos" to NoteKind.FACT,
    "Preferências" to NoteKind.PREFERENCE,
    "Aprendizados" to NoteKind.LEARNING,
    "Contexto" to NoteKind.CONTEXT,
)

private val memoryPremises = listOf(
    "Só este Prisma e você leem.",
    "Guarda o que é durável, não a conversa.",
    "Segredos são recusados.",
    "Em grupos, o que veio do privado é confidencial.",
    "Compromissos feitos somem depois de 30 dias.",
)

/**
 * The memory sheet of [bot]: its private-chat summary, its notes (filter, pin, edit, mark done,
 * delete, add by hand) and the premises the notebook keeps them under. Only the groups [bot]
 * belongs to are offered as places to filter by (all of them for Maestro).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrismaMemorySheet(bot: Bot, groups: List<PrismaGroup>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notebook = remember { PrismaNotebook(context) }
    var state by remember(bot.id) { mutableStateOf(notebook.state(bot.id)) }
    val accent = Mascots.of(bot.mascot).accent
    val now = System.currentTimeMillis()

    val botGroups = remember(groups, bot.id) {
        groups.filter { PrismaAdmin.isAdmin(bot) || bot.id in it.memberIds }
    }
    val placeChoices: List<Pair<String, String>> = remember(botGroups) {
        listOf(NotebookLogic.PLACE_GENERAL to "Geral", NotebookLogic.PLACE_PRIVATE to "Privado") +
            botGroups.map { NotebookLogic.groupPlace(it.id) to it.name }
    }
    val placeFilters: List<Pair<String?, String>> = remember(placeChoices) {
        listOf<Pair<String?, String>>(null to "Todos os lugares") + placeChoices
    }

    var kindFilter by remember { mutableStateOf<NoteKind?>(null) }
    var placeFilter by remember { mutableStateOf<String?>(null) }
    var doneOpen by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var addError by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Note?>(null) }
    var deleting by remember { mutableStateOf<Note?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    // A short toast-like line: [notice] is set, then cleared after a moment; [lastNotice] keeps the
    // text on screen while the line fades out.
    var notice by remember { mutableStateOf<String?>(null) }
    var lastNotice by remember { mutableStateOf("") }
    LaunchedEffect(notice) {
        val current = notice ?: return@LaunchedEffect
        lastNotice = current
        delay(2_400)
        notice = null
    }
    fun say(message: String) {
        notice = message
    }

    // Writes run off the main thread; the notebook is then reloaded and shown back on the main thread.
    fun <T> write(work: (PrismaNotebook) -> T, after: (T) -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            val outcome = runCatching { work(notebook) }
            val fresh = notebook.state(bot.id)
            withContext(Dispatchers.Main) {
                state = fresh
                outcome
                    .onSuccess(after)
                    .onFailure { say("Não consegui salvar agora. Tente de novo.") }
            }
        }
    }

    fun refreshSummary() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            try {
                val before = state.privateSummary?.updatedAtMillis
                withContext(Dispatchers.IO) { PrivateMemoryKeeper.refreshNow(context, bot) }
                // The summary is written in the background by the model; poll for a changed timestamp (up to ~45 s).
                for (poll in 1..MEMORY_POLL_ROUNDS) {
                    delay(1_000)
                    val fresh = withContext(Dispatchers.IO) { notebook.state(bot.id) }
                    state = fresh
                    if (fresh.privateSummary?.updatedAtMillis != before) break
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                say("Não consegui atualizar o resumo agora.")
            } finally {
                refreshing = false
            }
        }
    }

    fun toggleDone(note: Note) {
        write({ book ->
            book.update(bot.id, note.id) { n ->
                n.copy(status = if (n.status == NoteStatus.DONE) NoteStatus.OPEN else NoteStatus.DONE)
            }
        })
    }

    fun togglePin(note: Note) {
        write({ book -> book.update(bot.id, note.id) { n -> n.copy(pinned = !n.pinned) } })
    }

    fun saveEdit(note: Note, text: String, kind: NoteKind) {
        write(
            { book -> book.update(bot.id, note.id) { n -> n.copy(text = text, kind = kind, source = MEMORY_USER_SOURCE) } },
            after = { editing = null },
        )
    }

    fun removeNote(note: Note) {
        write({ book -> book.delete(bot.id, note.id) })
    }

    fun addNote(text: String, kind: NoteKind, place: String) {
        write(
            { book -> book.add(bot.id, NoteDraft(kind = kind, text = text, place = place, source = MEMORY_USER_SOURCE)) },
        ) { result ->
            when (result) {
                is NoteWrite.Added -> {
                    adding = false
                    say("Anotado")
                }
                is NoteWrite.Merged -> {
                    adding = false
                    say("Atualizei uma anotação parecida")
                }
                is NoteWrite.Rejected -> {
                    addError = result.reason
                }
            }
        }
    }

    val visible = state.notes.filter { note ->
        (kindFilter == null || note.kind == kindFilter) && (placeFilter == null || note.place == placeFilter)
    }
    // Open tasks first (soonest due first, nothing-due last), then the other open notes by recency.
    val tasks = visible
        .filter { it.status == NoteStatus.OPEN && it.kind == NoteKind.TASK }
        .sortedWith(compareBy<Note> { it.dueAtMillis ?: Long.MAX_VALUE }.thenByDescending { it.updatedAtMillis })
    val others = visible
        .filter { it.status == NoteStatus.OPEN && it.kind != NoteKind.TASK }
        .sortedByDescending { it.updatedAtMillis }
    val openNotes = tasks + others
    val doneNotes = visible.filter { it.status == NoteStatus.DONE }.sortedByDescending { it.updatedAtMillis }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = WorkbenchTokens.SurfaceRaised,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding()
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                MascotAvatar(index = bot.mascot, size = 56.dp)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Memória de ${bot.name}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = WorkbenchTokens.Text,
                    )
                    Text(
                        text = "O que o Prisma lembra. Só ele e você veem isto.",
                        style = MaterialTheme.typography.labelMedium,
                        color = WorkbenchTokens.TextMuted,
                    )
                }
            }

            MemoryLabel("Resumo do privado")
            MemorySummaryCard(
                summary = state.privateSummary,
                refreshing = refreshing,
                accent = accent,
                now = now,
                onRefresh = { refreshSummary() },
                onForget = { write({ book -> book.setPrivateSummary(bot.id, null) }) },
            )

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                MemoryLabel("Anotações")
                Spacer(modifier = Modifier.weight(1f))
                // 48dp touch area around the 36dp drawn circle.
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .pressScale(onClick = {
                            addError = null
                            adding = true
                        })
                        .semantics { contentDescription = "Anotar" },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(WorkbenchTokens.Surface)
                            .border(1.dp, WorkbenchTokens.BorderSoft, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(painterResource(R.drawable.ic_plus), contentDescription = null, modifier = Modifier.size(18.dp), tint = accent)
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    memoryKindFilters.forEach { (label, kind) ->
                        MemoryPill(text = label, selected = kindFilter == kind, accent = accent, onClick = { kindFilter = kind })
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    placeFilters.forEach { (place, label) ->
                        MemoryPill(text = label, selected = placeFilter == place, accent = accent, onClick = { placeFilter = place })
                    }
                }
            }

            AnimatedVisibility(
                visible = notice != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Text(
                    text = lastNotice,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(50))
                        .background(WorkbenchTokens.SurfaceHigh)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = accent,
                    textAlign = TextAlign.Center,
                )
            }

            when {
                state.notes.isEmpty() -> MemoryEmpty(
                    "Nada anotado ainda. O Prisma anota sozinho o que for importante, ou toque em + para anotar.",
                )
                openNotes.isEmpty() && doneNotes.isEmpty() -> MemoryEmpty("Nenhuma anotação com esses filtros.")
                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        openNotes.forEachIndexed { index, note ->
                            MemoryNoteRow(
                                note = note,
                                index = index,
                                placeLabel = memoryPlaceLabel(note.place, groups),
                                now = now,
                                onToggleDone = { toggleDone(note) },
                                onTogglePin = { togglePin(note) },
                                onEdit = { editing = note },
                                onDelete = { deleting = note },
                            )
                        }
                    }
                    if (doneNotes.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .pressScale(onClick = { doneOpen = !doneOpen }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Feitos (${doneNotes.size})", style = MaterialTheme.typography.labelLarge, color = WorkbenchTokens.TextMuted)
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = if (doneOpen) "ocultar" else "mostrar",
                                style = MaterialTheme.typography.labelMedium,
                                color = WorkbenchTokens.TextFaint,
                            )
                        }
                        AnimatedVisibility(
                            visible = doneOpen,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically(),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                doneNotes.forEachIndexed { index, note ->
                                    MemoryNoteRow(
                                        note = note,
                                        index = index,
                                        placeLabel = memoryPlaceLabel(note.place, groups),
                                        now = now,
                                        onToggleDone = { toggleDone(note) },
                                        onTogglePin = { togglePin(note) },
                                        onEdit = { editing = note },
                                        onDelete = { deleting = note },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(WorkbenchTokens.Surface)
                    .border(1.dp, WorkbenchTokens.BorderSoft, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Como a memória funciona", style = MaterialTheme.typography.labelLarge, color = WorkbenchTokens.TextFaint)
                memoryPremises.forEach { line ->
                    Text("•  $line", style = MaterialTheme.typography.bodySmall, color = WorkbenchTokens.TextMuted)
                }
            }
            TextButton(onClick = { confirmClear = true }) {
                Text("Limpar toda a memória", color = WorkbenchTokens.Red)
            }
        }
    }

    if (adding) {
        MemoryNoteDialog(
            title = "Nova anotação",
            initialText = "",
            initialKind = NoteKind.FACT,
            places = placeChoices,
            initialPlace = NotebookLogic.PLACE_GENERAL,
            serverError = addError,
            confirmLabel = "Anotar",
            onConfirm = { text, kind, place -> addNote(text, kind, place) },
            onDismiss = {
                adding = false
                addError = null
            },
        )
    }

    editing?.let { target ->
        MemoryNoteDialog(
            title = "Editar anotação",
            initialText = target.text,
            initialKind = target.kind,
            places = null,
            initialPlace = target.place,
            serverError = null,
            confirmLabel = "Salvar",
            onConfirm = { text, kind, _ ->
                saveEdit(target, text, kind)
            },
            onDismiss = { editing = null },
        )
    }

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = WorkbenchTokens.SurfaceRaised,
            title = { Text("Apagar anotação?", color = WorkbenchTokens.Text) },
            text = {
                Text(
                    text = "Ela some da memória deste Prisma e não dá para desfazer.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = WorkbenchTokens.TextMuted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    removeNote(target)
                }) { Text("Apagar", color = WorkbenchTokens.Red) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Cancelar", color = WorkbenchTokens.TextMuted) }
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = WorkbenchTokens.SurfaceRaised,
            title = { Text("Limpar toda a memória?", color = WorkbenchTokens.Text) },
            text = {
                Text(
                    text = "Apaga todas as anotações e o resumo do privado deste Prisma. Não dá para desfazer.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = WorkbenchTokens.TextMuted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    write({ book -> book.clear(bot.id) })
                }) { Text("Limpar", color = WorkbenchTokens.Red) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancelar", color = WorkbenchTokens.TextMuted) }
            },
        )
    }
}

/** Section heading inside the memory sheet. */
@Composable
private fun MemoryLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = WorkbenchTokens.TextFaint)
}

/** A friendly line for an empty list. */
@Composable
private fun MemoryEmpty(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = WorkbenchTokens.TextMuted,
        textAlign = TextAlign.Center,
    )
}

/** A pill chip in the prisma's accent when selected. */
@Composable
private fun MemoryPill(text: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    val edge by animateColorAsState(if (selected) accent else WorkbenchTokens.BorderSoft, label = "memoryPillEdge")
    val fill by animateColorAsState(if (selected) accent.copy(alpha = 0.16f) else Color.Transparent, label = "memoryPillFill")
    Text(
        text = text,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(fill)
            .border(1.dp, edge, RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .pressScale(onClick = onClick),
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) accent else WorkbenchTokens.TextMuted,
        maxLines = 1,
    )
}

/** The private-chat summary card: the text, its age, refresh (with progress) and forget. */
@Composable
private fun MemorySummaryCard(
    summary: PrivateSummary?,
    refreshing: Boolean,
    accent: Color,
    now: Long,
    onRefresh: () -> Unit,
    onForget: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(WorkbenchTokens.Surface)
            .border(1.dp, WorkbenchTokens.BorderSoft, RoundedCornerShape(18.dp))
            .padding(16.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = summary?.text ?: "Ainda não há resumo. Ele é criado sozinho depois de algumas mensagens no privado.",
            style = MaterialTheme.typography.bodyMedium,
            color = if (summary == null) WorkbenchTokens.TextMuted else WorkbenchTokens.Text,
        )
        summary?.let {
            Text(
                text = "atualizado ${memoryAgo(it.updatedAtMillis, now)}",
                style = MaterialTheme.typography.labelSmall,
                color = WorkbenchTokens.TextFaint,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(accent.copy(alpha = 0.14f))
                    .border(1.dp, accent, RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .pressScale(enabled = !refreshing, onClick = onRefresh),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (refreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), color = accent, strokeWidth = 2.dp)
                }
                Text(
                    text = if (refreshing) "Atualizando…" else "Atualizar agora",
                    style = MaterialTheme.typography.labelLarge,
                    color = accent,
                )
            }
            if (summary != null) {
                TextButton(onClick = onForget) { Text("Apagar", color = WorkbenchTokens.Red) }
            }
        }
    }
}

/**
 * One note: kind emoji, text (struck through when done), a muted line with place, source and age,
 * the due date (red when overdue), a pin mark. Tap or long-press opens its actions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MemoryNoteRow(
    note: Note,
    index: Int,
    placeLabel: String,
    now: Long,
    onToggleDone: () -> Unit,
    onTogglePin: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val done = note.status == NoteStatus.DONE
    val due = note.dueAtMillis
    val overdue = !done && due != null && due < now

    Box(modifier = Modifier.fillMaxWidth().appearOnce(key = "memory:${note.id}", index = index)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(WorkbenchTokens.Surface)
                .border(1.dp, WorkbenchTokens.BorderSoft, RoundedCornerShape(16.dp))
                .padding(start = 12.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)
                .pressScale(pressedScale = 0.98f)
                .combinedClickable(onClick = { menuOpen = true }, onLongClick = { menuOpen = true }),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.size(34.dp).clip(CircleShape).background(WorkbenchTokens.SurfaceHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(noteKindIcon(note)), contentDescription = null, modifier = Modifier.size(18.dp), tint = noteKindTint(note))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = note.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (done) WorkbenchTokens.TextMuted else WorkbenchTokens.Text,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                )
                Text(
                    text = "$placeLabel · ${memorySourceLabel(note.source)} · ${memoryAgo(note.updatedAtMillis, now)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = WorkbenchTokens.TextFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (due != null && !done) {
                    Text(
                        text = if (overdue) "atrasado · ${memoryDue(due)}" else memoryDue(due),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (overdue) WorkbenchTokens.Red else WorkbenchTokens.TextMuted,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (note.pinned) {
                    Icon(painterResource(R.drawable.ic_pin), contentDescription = "Fixada", modifier = Modifier.size(14.dp), tint = WorkbenchTokens.TextMuted)
                }
                Icon(painterResource(R.drawable.ic_more_horizontal), contentDescription = null, modifier = Modifier.size(16.dp), tint = WorkbenchTokens.TextFaint)
            }
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (note.kind == NoteKind.TASK) {
                DropdownMenuItem(
                    text = { Text(if (done) "Reabrir" else "Marcar feito") },
                    onClick = {
                        menuOpen = false
                        onToggleDone()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(if (note.pinned) "Desafixar" else "Fixar") },
                onClick = {
                    menuOpen = false
                    onTogglePin()
                },
            )
            DropdownMenuItem(
                text = { Text("Editar") },
                onClick = {
                    menuOpen = false
                    onEdit()
                },
            )
            DropdownMenuItem(
                text = { Text("Apagar", color = WorkbenchTokens.Red) },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}

/** A labelled dropdown field: shows the selected option and opens the list of [options]. */
@Composable
private fun <T> MemoryPicker(label: String, selected: T, options: List<Pair<T, String>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MemoryLabel(label)
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, WorkbenchTokens.BorderSoft, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .pressScale(onClick = { open = true }),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = options.firstOrNull { it.first == selected }?.second.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = WorkbenchTokens.Text,
                )
                Text("▼", style = MaterialTheme.typography.labelSmall, color = WorkbenchTokens.TextMuted)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = {
                            open = false
                            onPick(value)
                        },
                    )
                }
            }
        }
    }
}

/**
 * Add or edit a note. [places] = null hides the place picker (editing keeps the note's place).
 * Text is checked live with the same rules the notebook uses; [serverError] is the reason a
 * write was refused.
 */
@Composable
private fun MemoryNoteDialog(
    title: String,
    initialText: String,
    initialKind: NoteKind,
    places: List<Pair<String, String>>?,
    initialPlace: String,
    serverError: String?,
    confirmLabel: String,
    onConfirm: (String, NoteKind, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(initialText) }
    var kind by remember { mutableStateOf(initialKind) }
    var place by remember { mutableStateOf(initialPlace) }
    val clean = draft.trim().replace(Regex("\\s+"), " ")
    val problem = if (clean.isEmpty()) null else NotebookLogic.validate(clean, SensitiveDataPolicy::containsSecret)
    val canSave = clean.isNotEmpty() && problem == null
    val shownError = problem ?: serverError

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = WorkbenchTokens.SurfaceRaised,
        title = { Text(title, color = WorkbenchTokens.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Ex.: Prefere reuniões de manhã", color = WorkbenchTokens.TextFaint) },
                    minLines = 2,
                    maxLines = 5,
                    shape = RoundedCornerShape(16.dp),
                    colors = memoryFieldColors(),
                )
                MemoryPicker(label = "Tipo", selected = kind, options = NoteKind.entries.map { it to it.label }) { kind = it }
                if (places != null) {
                    MemoryPicker(label = "Lugar", selected = place, options = places) { place = it }
                }
                shownError?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = WorkbenchTokens.Red)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = canSave, onClick = { onConfirm(clean, kind, place) }) {
                Text(confirmLabel, color = if (canSave) WorkbenchTokens.Text else WorkbenchTokens.TextFaint)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar", color = WorkbenchTokens.TextMuted) }
        },
    )
}

@Composable
private fun memoryFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = WorkbenchTokens.Border,
    unfocusedBorderColor = WorkbenchTokens.BorderSoft,
    cursorColor = WorkbenchTokens.Text,
)

/** Icon of a note kind (a done task shows the check). Shared by the notebook and the team board. */
internal fun noteKindIcon(note: Note): Int = when (note.kind) {
    NoteKind.TASK -> if (note.status == NoteStatus.DONE) R.drawable.ic_check_circle else R.drawable.ic_clock
    NoteKind.FACT -> R.drawable.ic_pin
    NoteKind.PREFERENCE -> R.drawable.ic_heart
    NoteKind.LEARNING -> R.drawable.ic_lightbulb
    NoteKind.CONTEXT -> R.drawable.ic_folder
}

internal fun noteKindTint(note: Note): Color = when (note.kind) {
    NoteKind.TASK -> if (note.status == NoteStatus.DONE) WorkbenchTokens.Green else WorkbenchTokens.Gold
    NoteKind.FACT -> WorkbenchTokens.Sky
    NoteKind.PREFERENCE -> WorkbenchTokens.Red
    NoteKind.LEARNING -> WorkbenchTokens.Amber
    NoteKind.CONTEXT -> WorkbenchTokens.Purple
}

/** "geral", "privado" or "grupo <nome>" (removed groups read as "grupo removido"). */
private fun memoryPlaceLabel(place: String, groups: List<PrismaGroup>): String {
    if (place == NotebookLogic.PLACE_GENERAL) return "geral"
    if (place == NotebookLogic.PLACE_PRIVATE) return "privado"
    val groupId = NotebookLogic.groupIdOf(place) ?: return place
    return "grupo " + (groups.firstOrNull { it.id == groupId }?.name ?: "removido")
}

/** Who wrote the note, in words. */
private fun memorySourceLabel(source: String): String = when {
    source == MEMORY_USER_SOURCE -> "você"
    source == "privado" -> "conversa privada"
    source == "rotina" -> "rotina"
    source.startsWith("grupo:") -> "conversa em grupo"
    else -> source
}

/** "há 5 min", "há 3 h", "há 2 dias"; older notes show their date. */
private fun memoryAgo(millis: Long, now: Long): String {
    val minutes = (now - millis) / 60_000L
    return when {
        minutes < 1L -> "há instantes"
        minutes < 60L -> "há $minutes min"
        minutes < 24L * 60L -> "há ${minutes / 60L} h"
        minutes < 7L * 24L * 60L -> {
            val days = minutes / (24L * 60L)
            if (days == 1L) "há 1 dia" else "há $days dias"
        }
        else -> "em " + SimpleDateFormat("dd/MM/yy", Locale.ROOT).format(Date(millis))
    }
}

private fun memoryDue(millis: Long): String =
    "prazo " + SimpleDateFormat("dd/MM HH:mm", Locale.ROOT).format(Date(millis))
