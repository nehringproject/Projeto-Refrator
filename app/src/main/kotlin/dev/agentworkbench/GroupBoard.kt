package dev.agentworkbench

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import android.graphics.Bitmap
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The team board of a group ("Painel do time"): what each prisma is doing, the team notebook
 * shared by its members, and the backstage (private talks between them and who stayed quiet).
 */

/** The three tabs of the board. */
private enum class BoardTab(val label: String) {
    BOARD("Quadro"),
    NOTEBOOK("Caderno do time"),
    BACKSTAGE("Bastidores"),
    SCREENS("Telas"),
}

/** Status sections of the board, in the order they are read. */
private val boardOrder = listOf(TaskStatus.DOING, TaskStatus.BLOCKED, TaskStatus.REVIEW, TaskStatus.TODO, TaskStatus.DONE)

/** Source written on notes the user adds by hand here (same convention as the memory sheet). */
private const val BOARD_USER_SOURCE = "usuário"

private fun boardStatusColor(status: TaskStatus): Color = when (status) {
    TaskStatus.DOING -> Color(0xFF7AA2FF)
    TaskStatus.BLOCKED -> WorkbenchTokens.Red
    TaskStatus.REVIEW -> Color(0xFFF2A33A)
    TaskStatus.TODO -> WorkbenchTokens.TextMuted
    TaskStatus.DONE -> WorkbenchTokens.Green
}

private fun boardNameOf(id: String?, botsById: Map<String, Bot>): String = when {
    id == null -> "sem dono"
    id == GroupAuthors.USER -> "Você"
    else -> botsById[id]?.name ?: "Prisma"
}

private fun boardAccent(id: String?, botsById: Map<String, Bot>): Color = when {
    id == null -> WorkbenchTokens.TextFaint
    id == GroupAuthors.USER -> WorkbenchTokens.Text
    else -> botsById[id]?.let { Mascots.of(it.mascot).accent } ?: WorkbenchTokens.TextMuted
}

/** "agora", "há 5 min", "há 3 h", "há 2 dias", or the date for older items. */
private fun boardAgo(millis: Long, now: Long): String {
    val minutes = (now - millis) / 60_000L
    return when {
        minutes < 1L -> "agora"
        minutes < 60L -> "há $minutes min"
        minutes < 24L * 60L -> "há ${minutes / 60L} h"
        minutes < 7L * 24L * 60L -> {
            val days = minutes / (24L * 60L)
            if (days == 1L) "há 1 dia" else "há $days dias"
        }
        else -> "em " + SimpleDateFormat("dd/MM", Locale.ROOT).format(Date(millis))
    }
}

/** Clock time for today, date and time for anything older. */
private fun boardWhen(millis: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    return SimpleDateFormat(if (sameDay) "HH:mm" else "dd/MM HH:mm", Locale.getDefault()).format(Date(millis))
}

/** Puts [task] in [list]: a new task is appended; an edited one gets a fresh updatedAt only when it changed. */
private fun boardMergeTask(list: List<GroupTask>, task: GroupTask): List<GroupTask> {
    val now = System.currentTimeMillis()
    val existing = list.firstOrNull { it.id == task.id } ?: return list + task.copy(updatedAtMillis = now)
    val changed = existing.copy(updatedAtMillis = 0L) != task.copy(updatedAtMillis = 0L)
    val stamped = task.copy(updatedAtMillis = if (changed) now else existing.updatedAtMillis)
    return list.map { if (it.id == task.id) stamped else it }
}

// ---------------------------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------------------------

/** The team board sheet of [group]: Quadro, Caderno do time and Bastidores. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupBoardSheet(
    group: PrismaGroup,
    bots: List<Bot>,
    typingIds: Set<String>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember(context) { GroupRepository(context) }
    remember(group.id) { GroupStore.ensureLoaded(repository) }
    val liveGroups by GroupStore.groups.collectAsState()
    val current = liveGroups.firstOrNull { it.id == group.id } ?: group
    val botsById = remember(bots) { bots.associateBy { it.id } }
    val members = remember(current.memberIds, botsById) { current.memberIds.mapNotNull { botsById[it] } }
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    val tab = BoardTab.entries[tabIndex.coerceIn(0, BoardTab.entries.lastIndex)]

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = WorkbenchTokens.SurfaceRaised,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Painel do time · ${current.name}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = WorkbenchTokens.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            BoardTabs(selected = tab.ordinal, onPick = { tabIndex = it })
            // Switching tabs slides the page in the direction of the tab order, with a soft fade.
            AnimatedContent(
                targetState = tab,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    (
                        slideInHorizontally(tween(UiMotion.Standard, easing = FastOutSlowInEasing)) { if (forward) it / 8 else -it / 8 } +
                            fadeIn(tween(UiMotion.Standard))
                        ) togetherWith (
                        slideOutHorizontally(tween(UiMotion.Quick, easing = FastOutSlowInEasing)) { if (forward) -it / 8 else it / 8 } +
                            fadeOut(tween(UiMotion.Quick))
                        )
                },
                label = "boardTabContent",
            ) { page ->
                when (page) {
                    BoardTab.BOARD -> BoardQuadro(
                        groupId = group.id,
                        tasks = current.tasks,
                        botsById = botsById,
                        members = members,
                        typingIds = typingIds,
                        modifier = Modifier.fillMaxSize(),
                        onSave = { task ->
                            GroupStore.update(repository, group.id) { it.copy(tasks = boardMergeTask(it.tasks, task)) }
                        },
                        onDelete = { id ->
                            GroupStore.update(repository, group.id) { g -> g.copy(tasks = g.tasks.filterNot { it.id == id }) }
                        },
                        onMission = { task, start ->
                            // Keep the edits made in the editor, then start or stop the worker of this task.
                            GroupStore.update(repository, group.id) { it.copy(tasks = boardMergeTask(it.tasks, task)) }
                            if (start) {
                                MissionEngine.start(context, group.id, task.id)
                            } else {
                                MissionEngine.stop(context, group.id, task.id)
                            }
                        },
                    )
                    BoardTab.NOTEBOOK -> BoardCaderno(group = current, modifier = Modifier.fillMaxSize())
                    BoardTab.BACKSTAGE -> BoardBackstage(group = current, botsById = botsById, modifier = Modifier.fillMaxSize())
                    BoardTab.SCREENS -> BoardTelas(botsById = botsById, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Tabs
// ---------------------------------------------------------------------------------------------

@Composable
private fun BoardTabs(selected: Int, onPick: (Int) -> Unit) {
    val gap = 4.dp
    val count = BoardTab.entries.size
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(WorkbenchTokens.Surface)
            .border(1.dp, WorkbenchTokens.BorderSoft, RoundedCornerShape(50))
            .padding(4.dp),
    ) {
        val tabWidth = (maxWidth - gap * (count - 1)) / count
        // One pill glides under the chosen tab (it does not fade in each tab on its own).
        val pillStart by animateDpAsState(
            targetValue = (tabWidth + gap) * selected,
            animationSpec = uiSpring(),
            label = "boardTabPill",
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(start = pillStart)
                .width(tabWidth)
                .clip(RoundedCornerShape(50))
                .background(WorkbenchTokens.SurfaceHigh),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            BoardTab.entries.forEachIndexed { index, tab ->
                val active = index == selected
                val ink by animateColorAsState(if (active) WorkbenchTokens.Text else WorkbenchTokens.TextMuted, label = "boardTabInk")
                Text(
                    text = tab.label,
                    modifier = Modifier
                        .weight(1f)
                        .pressScale(onClick = { onPick(index) })
                        .padding(vertical = 8.dp, horizontal = 6.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge,
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Quadro: summary strip, status sections, task rows and the task editor
// ---------------------------------------------------------------------------------------------

@Composable
private fun BoardQuadro(
    groupId: String,
    tasks: List<GroupTask>,
    botsById: Map<String, Bot>,
    members: List<Bot>,
    typingIds: Set<String>,
    modifier: Modifier,
    onSave: (GroupTask) -> Unit,
    onDelete: (String) -> Unit,
    onMission: (GroupTask, Boolean) -> Unit,
) {
    var doneOpen by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<GroupTask?>(null) }
    var creating by remember { mutableStateOf(false) }
    val running by MissionEngine.running.collectAsState()

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "board-summary") {
            BoardSummary(tasks = tasks, modifier = Modifier.animateItem())
        }
        if (tasks.isEmpty()) {
            item(key = "board-empty") { BoardEmpty(modifier = Modifier.animateItem()) }
        }
        boardOrder.forEach { status ->
            val column = tasks.filter { it.status == status }.sortedByDescending { it.updatedAtMillis }
            if (column.isNotEmpty()) {
                val collapsible = status == TaskStatus.DONE
                val open = !collapsible || doneOpen
                item(key = "board-head-${status.name}") {
                    BoardSectionHead(
                        status = status,
                        count = column.size,
                        collapsible = collapsible,
                        open = open,
                        onToggle = { doneOpen = !doneOpen },
                        modifier = Modifier.animateItem(),
                    )
                }
                if (open) {
                    items(column, key = { it.id }) { task ->
                        BoardTaskRow(
                            task = task,
                            botsById = botsById,
                            typing = task.ownerId?.let { it in typingIds } == true,
                            working = MissionEngine.key(groupId, task.id) in running,
                            modifier = Modifier.animateItem(),
                            onClick = { editing = task },
                        )
                    }
                }
            }
        }
        item(key = "board-add") {
            BoardAddButton(label = "+ Nova tarefa", onClick = { creating = true }, modifier = Modifier.animateItem())
        }
    }

    if (creating || editing != null) {
        val opened = editing
        // The live copy of the task: the worker keeps writing its log and counters while the editor is open.
        val target = opened?.let { shown -> tasks.firstOrNull { it.id == shown.id } ?: shown }
        BoardTaskDialog(
            initial = target,
            groupId = groupId,
            members = members,
            onSave = { task ->
                onSave(task)
                creating = false
                editing = null
            },
            onMission = onMission,
            onDelete = {
                target?.let { onDelete(it.id) }
                creating = false
                editing = null
            },
            onDismiss = {
                creating = false
                editing = null
            },
        )
    }
}

@Composable
private fun BoardSummary(tasks: List<GroupTask>, modifier: Modifier) {
    val total = tasks.size
    val done = tasks.count { it.status == TaskStatus.DONE }
    val fraction = if (total == 0) 0f else done.toFloat() / total
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .clip(shape)
            .background(WorkbenchTokens.Surface)
            .border(1.dp, WorkbenchTokens.BorderSoft, shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Progresso do time", style = MaterialTheme.typography.labelLarge, color = WorkbenchTokens.TextMuted)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = if (total == 0) "sem tarefas" else "$done de $total feitas · ${(fraction * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = WorkbenchTokens.Text,
            )
        }
        BoardProgressBar(fraction = fraction, color = WorkbenchTokens.Green, thickness = 6.dp)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            boardOrder.forEach { status ->
                BoardCountPill(status = status, count = tasks.count { it.status == status })
            }
        }
    }
}

@Composable
private fun BoardCountPill(status: TaskStatus, count: Int) {
    val color = boardStatusColor(status)
    val strength = if (count == 0) 0.45f else 1f
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f * strength))
            .border(1.dp, color.copy(alpha = 0.45f * strength), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(status.label, style = MaterialTheme.typography.labelMedium, color = color.copy(alpha = strength))
        AnimatedCount(
            count = count,
            style = MaterialTheme.typography.labelLarge,
            color = color.copy(alpha = strength),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun BoardProgressBar(fraction: Float, color: Color, thickness: Dp, modifier: Modifier = Modifier) {
    val shown by animateFloatAsState(targetValue = fraction.coerceIn(0f, 1f), label = "boardProgress")
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(thickness)
            .clip(CircleShape)
            .background(WorkbenchTokens.SurfaceHigh),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(shown)
                .height(thickness)
                .clip(CircleShape)
                .background(color),
        )
    }
}

@Composable
private fun BoardSectionHead(
    status: TaskStatus,
    count: Int,
    collapsible: Boolean,
    open: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier,
) {
    val color = boardStatusColor(status)
    val base = modifier.fillMaxWidth().padding(top = 6.dp)
    Row(
        modifier = if (collapsible) base.pressScale(onClick = onToggle).padding(vertical = 4.dp) else base,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(
            text = status.label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = WorkbenchTokens.Text,
        )
        Text("$count", style = MaterialTheme.typography.labelMedium, color = WorkbenchTokens.TextFaint)
        Spacer(modifier = Modifier.weight(1f))
        if (collapsible) {
            Text(
                text = if (open) "ocultar" else "mostrar",
                style = MaterialTheme.typography.labelMedium,
                color = WorkbenchTokens.TextFaint,
            )
        }
    }
}

/** One task as a row of the table: owner, title, note, progress on the left; status and age on the right. */
@Composable
private fun BoardTaskRow(
    task: GroupTask,
    botsById: Map<String, Bot>,
    typing: Boolean,
    working: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val color = boardStatusColor(task.status)
    val done = task.status == TaskStatus.DONE
    val owner = task.ownerId
    val now = System.currentTimeMillis()
    // Criteria and note stay folded until the user asks for them; a tap on the row still opens the editor.
    var details by remember(task.id) { mutableStateOf(false) }
    val hasDetails = task.criteria.isNotBlank() || task.note.isNotBlank()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .clip(shape)
            .background(WorkbenchTokens.Surface)
            .border(1.dp, WorkbenchTokens.BorderSoft, shape)
            .pressScale(pressedScale = 0.98f)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BoardAvatar(id = owner, botsById = botsById, size = 28.dp, typing = typing)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = boardNameOf(owner, botsById),
                style = MaterialTheme.typography.labelMedium,
                color = boardAccent(owner, botsById),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = task.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (done) WorkbenchTokens.TextMuted else WorkbenchTokens.Text,
                textDecoration = if (done) TextDecoration.LineThrough else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (hasDetails) {
                BoardDetailsToggle(open = details, onToggle = { details = !details })
            }
            if (details) {
                if (task.criteria.isNotBlank()) {
                    Text(
                        text = "Pronto quando: ${task.criteria}",
                        style = MaterialTheme.typography.labelSmall,
                        color = WorkbenchTokens.TextFaint,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (task.note.isNotBlank()) {
                    Text(
                        text = task.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = WorkbenchTokens.TextMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            task.progress?.let { percent ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BoardProgressBar(
                        fraction = percent / 100f,
                        color = color,
                        thickness = 4.dp,
                        modifier = Modifier.weight(1f),
                    )
                    Text("$percent%", style = MaterialTheme.typography.labelSmall, color = WorkbenchTokens.TextFaint)
                }
            }
            if (task.autonomous) {
                BoardMissionLine(task = task, working = working, now = now)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BoardStatusChip(status = task.status)
            Text(
                text = "atualizado ${boardAgo(task.updatedAtMillis, now)}",
                style = MaterialTheme.typography.labelSmall,
                color = WorkbenchTokens.TextFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun BoardStatusChip(status: TaskStatus) {
    val color = boardStatusColor(status)
    Text(
        text = status.label,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = color,
    )
}

/** "Ver detalhes" / "Ocultar detalhes": opens the criteria and note of a task row without opening the editor. */
@Composable
private fun BoardDetailsToggle(open: Boolean, onToggle: () -> Unit) {
    val turn by animateFloatAsState(
        targetValue = if (open) 90f else 0f,
        animationSpec = tween(UiMotion.Quick),
        label = "boardDetailsTurn",
    )
    Row(
        modifier = Modifier
            .pressScale(onClick = onToggle)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = if (open) "Ocultar detalhes" else "Ver detalhes",
            style = MaterialTheme.typography.labelSmall,
            color = WorkbenchTokens.TextMuted,
        )
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            modifier = Modifier.size(14.dp).graphicsLayer { rotationZ = turn },
            tint = WorkbenchTokens.TextMuted,
        )
    }
}

/** Owner avatar: the prisma's mascot (working while it is typing), "V" for the user, "?" for nobody. */
@Composable
private fun BoardAvatar(id: String?, botsById: Map<String, Bot>, size: Dp, typing: Boolean = false) {
    if (id == null || id == GroupAuthors.USER) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(WorkbenchTokens.SurfaceHigh)
                .border(
                    1.dp,
                    if (id == null) WorkbenchTokens.BorderSoft else WorkbenchTokens.Gold.copy(alpha = 0.6f),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (id == null) "?" else "V",
                style = MaterialTheme.typography.labelMedium,
                color = if (id == null) WorkbenchTokens.TextFaint else WorkbenchTokens.Text,
            )
        }
    } else {
        MascotAvatar(
            index = botsById[id]?.mascot ?: Mascots.forKey(id),
            size = size,
            mood = if (typing) MascotMood.WORKING else MascotMood.IDLE,
            showActivity = false,
        )
    }
}

@Composable
private fun BoardEmpty(modifier: Modifier) {
    Text(
        text = "O time ainda não criou tarefas. Peça no grupo: “organizem o trabalho no quadro”.",
        modifier = modifier.fillMaxWidth().padding(vertical = 12.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = WorkbenchTokens.TextMuted,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun BoardAddButton(label: String, onClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(WorkbenchTokens.GoldSoft)
            .border(1.dp, WorkbenchTokens.Gold.copy(alpha = 0.45f), shape)
            .pressScale(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = WorkbenchTokens.Gold)
    }
}

/** Create or edit a task: title, owner, status, note, and an optional progress slider. */
@Composable
private fun BoardTaskDialog(
    initial: GroupTask?,
    groupId: String,
    members: List<Bot>,
    onSave: (GroupTask) -> Unit,
    onMission: (GroupTask, Boolean) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var owner by remember { mutableStateOf(initial?.ownerId) }
    var status by remember { mutableStateOf(initial?.status ?: TaskStatus.TODO) }
    var note by remember { mutableStateOf(initial?.note.orEmpty()) }
    var criteria by remember { mutableStateOf(initial?.criteria.orEmpty()) }
    var tracked by remember { mutableStateOf(initial?.progress != null) }
    var progress by remember { mutableFloatStateOf((initial?.progress ?: 0).toFloat()) }
    var diaryOpen by rememberSaveable { mutableStateOf(false) }
    val running by MissionEngine.running.collectAsState()
    val cleanTitle = title.trim()
    val ownerOptions = buildList<Pair<String?, String>> {
        add(null to "sem dono")
        add(GroupAuthors.USER to "Você")
        members.forEach { add(it.id to it.name) }
        owner?.takeIf { id -> id != GroupAuthors.USER && members.none { it.id == id } }?.let { add(it to "Prisma fora do time") }
    }
    // The task as the editor shows it now; worker fields (log, counters, sleep) come from the live task.
    fun draftTask(): GroupTask = (initial ?: GroupTask(title = cleanTitle)).copy(
        title = cleanTitle,
        ownerId = owner,
        status = status,
        note = note.trim(),
        criteria = criteria.trim(),
        progress = if (tracked) progress.roundToInt().coerceIn(0, 100) else null,
    )
    val working = initial != null && MissionEngine.key(groupId, initial.id) in running

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = WorkbenchTokens.SurfaceRaised,
        title = {
            Text(if (initial == null) "Nova tarefa" else "Editar tarefa", color = WorkbenchTokens.Text)
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(160) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Título") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = boardFieldColors(),
                )
                OutlinedTextField(
                    value = criteria,
                    onValueChange = { criteria = it.take(300) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Critério de pronto") },
                    placeholder = { Text("Ex.: site no ar e testado no celular", color = WorkbenchTokens.TextFaint) },
                    minLines = 1,
                    maxLines = 3,
                    shape = RoundedCornerShape(16.dp),
                    colors = boardFieldColors(),
                )
                BoardPicker(label = "Responsável", selected = owner, options = ownerOptions) { owner = it }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Situação", style = MaterialTheme.typography.labelLarge, color = WorkbenchTokens.TextFaint)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        TaskStatus.entries.forEach { option ->
                            BoardChoicePill(
                                text = option.label,
                                picked = option == status,
                                tint = boardStatusColor(option),
                                onClick = { status = option },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(300) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Observação (andamento, bloqueio ou resultado)") },
                    minLines = 2,
                    maxLines = 4,
                    shape = RoundedCornerShape(16.dp),
                    colors = boardFieldColors(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Acompanhar progresso",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        color = WorkbenchTokens.Text,
                    )
                    Switch(checked = tracked, onCheckedChange = { tracked = it })
                }
                if (tracked) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Slider(
                            value = progress,
                            onValueChange = { progress = it },
                            valueRange = 0f..100f,
                            steps = 19,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${progress.roundToInt()}%",
                            modifier = Modifier.width(44.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = WorkbenchTokens.Text,
                            textAlign = TextAlign.End,
                        )
                    }
                }
                if (initial != null) {
                    BoardMissionSection(
                        working = working,
                        canStart = owner != null && owner != GroupAuthors.USER,
                        log = initial.log,
                        diaryOpen = diaryOpen,
                        onToggleDiary = { diaryOpen = !diaryOpen },
                        onStart = {
                            if (status == TaskStatus.TODO || status == TaskStatus.BLOCKED) status = TaskStatus.DOING
                            onMission(draftTask(), true)
                        },
                        onStop = { onMission(draftTask(), false) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = cleanTitle.isNotEmpty(),
                onClick = { onSave(draftTask()) },
            ) {
                Text("Salvar", color = if (cleanTitle.isNotEmpty()) WorkbenchTokens.Text else WorkbenchTokens.TextFaint)
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (initial != null) {
                    TextButton(onClick = onDelete) { Text("Apagar", color = WorkbenchTokens.Red) }
                }
                TextButton(onClick = onDismiss) { Text("Cancelar", color = WorkbenchTokens.TextMuted) }
            }
        },
    )
}

/** A labelled dropdown: the selected option shown in a field, the list opened on tap. */
@Composable
private fun <T> BoardPicker(label: String, selected: T, options: List<Pair<T, String>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = WorkbenchTokens.TextFaint)
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, WorkbenchTokens.BorderSoft, RoundedCornerShape(16.dp))
                    .pressScale(onClick = { open = true })
                    .padding(horizontal = 14.dp, vertical = 12.dp),
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

@Composable
private fun BoardChoicePill(text: String, picked: Boolean, tint: Color, onClick: () -> Unit) {
    Text(
        text = text,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (picked) tint.copy(alpha = 0.22f) else WorkbenchTokens.Surface)
            .border(1.dp, if (picked) tint.copy(alpha = 0.7f) else WorkbenchTokens.BorderSoft, RoundedCornerShape(50))
            .pressScale(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        style = MaterialTheme.typography.labelLarge,
        color = if (picked) tint else WorkbenchTokens.TextMuted,
    )
}

@Composable
private fun boardFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = WorkbenchTokens.Border,
    unfocusedBorderColor = WorkbenchTokens.BorderSoft,
    cursorColor = WorkbenchTokens.Text,
    focusedLabelColor = WorkbenchTokens.TextMuted,
)

// ---------------------------------------------------------------------------------------------
// Caderno do time: the team notebook (shared by every member of the group)
// ---------------------------------------------------------------------------------------------

@Composable
private fun BoardCaderno(group: PrismaGroup, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notebook = remember(context) { PrismaNotebook(context) }
    val notebookId = remember(group.id) { teamNotebookId(group.id) }
    var state by remember(notebookId) { mutableStateOf(notebook.state(notebookId)) }
    var doneOpen by rememberSaveable { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var addError by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Note?>(null) }

    // Writes run off the main thread; the notebook is then reloaded and shown back on the main thread.
    fun write(work: (PrismaNotebook) -> NoteWrite?, after: (NoteWrite?) -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            val outcome = runCatching { work(notebook) }
                .getOrElse { NoteWrite.Rejected("Não consegui salvar agora. Tente de novo.") }
            val fresh = notebook.state(notebookId)
            withContext(Dispatchers.Main) {
                state = fresh
                after(outcome)
            }
        }
    }

    fun toggleDone(note: Note) {
        write({ book ->
            book.update(notebookId, note.id) { n ->
                n.copy(status = if (n.status == NoteStatus.DONE) NoteStatus.OPEN else NoteStatus.DONE)
            }
            null
        })
    }

    fun addNote(text: String, kind: NoteKind) {
        write(
            { book -> book.add(notebookId, NoteDraft(kind = kind, text = text, place = NotebookLogic.PLACE_GENERAL, source = BOARD_USER_SOURCE)) },
            after = { result ->
                if (result is NoteWrite.Rejected) {
                    addError = result.reason
                } else {
                    adding = false
                    addError = null
                }
            },
        )
    }

    val now = System.currentTimeMillis()
    val open = state.notes.filter { it.status == NoteStatus.OPEN }.sortedByDescending { it.updatedAtMillis }
    val done = state.notes.filter { it.status == NoteStatus.DONE }.sortedByDescending { it.updatedAtMillis }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "team-intro") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BoardHint("Anotações compartilhadas por todos do grupo: decisões, links, padrões combinados.")
                BoardAddButton(
                    label = "+ Anotar no caderno",
                    onClick = {
                        addError = null
                        adding = true
                    },
                    modifier = Modifier,
                )
            }
        }
        if (open.isEmpty() && done.isEmpty()) {
            item(key = "team-empty") {
                BoardHint("Nada anotado no caderno do time ainda. Quando o grupo combinar algo, ele aparece aqui.")
            }
        }
        items(open, key = { "team-${it.id}" }) { note ->
            BoardNoteRow(
                note = note,
                now = now,
                onToggle = { toggleDone(note) },
                onDelete = { deleting = note },
                modifier = Modifier.animateItem(),
            )
        }
        if (done.isNotEmpty()) {
            item(key = "team-done-head") {
                BoardFoldHead(
                    title = "Feitos (${done.size})",
                    open = doneOpen,
                    onToggle = { doneOpen = !doneOpen },
                    modifier = Modifier.animateItem(),
                )
            }
            if (doneOpen) {
                items(done, key = { "team-${it.id}" }) { note ->
                    BoardNoteRow(
                        note = note,
                        now = now,
                        onToggle = { toggleDone(note) },
                        onDelete = { deleting = note },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    if (adding) {
        BoardNoteDialog(
            serverError = addError,
            onConfirm = { text, kind -> addNote(text, kind) },
            onDismiss = {
                adding = false
                addError = null
            },
        )
    }

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = WorkbenchTokens.SurfaceRaised,
            title = { Text("Apagar anotação?", color = WorkbenchTokens.Text) },
            text = {
                Text(
                    text = "Ela some do caderno do time e não dá para desfazer.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = WorkbenchTokens.TextMuted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    write({ book ->
                        book.delete(notebookId, target.id)
                        null
                    })
                }) { Text("Apagar", color = WorkbenchTokens.Red) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Cancelar", color = WorkbenchTokens.TextMuted) }
            },
        )
    }
}

@Composable
private fun BoardNoteRow(
    note: Note,
    now: Long,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val done = note.status == NoteStatus.DONE
    val shape = RoundedCornerShape(16.dp)
    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(WorkbenchTokens.Surface)
                .border(1.dp, WorkbenchTokens.BorderSoft, shape)
                .pressScale(pressedScale = 0.98f)
                .clickable { menuOpen = true }
                .padding(12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(WorkbenchTokens.SurfaceHigh)
                    .pressScale(onClick = onToggle)
                    .semantics { contentDescription = if (done) "Reabrir" else "Marcar como feito" },
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
                    text = boardNoteMeta(note, now),
                    style = MaterialTheme.typography.labelSmall,
                    color = WorkbenchTokens.TextFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(painterResource(R.drawable.ic_more_horizontal), contentDescription = null, modifier = Modifier.size(16.dp), tint = WorkbenchTokens.TextFaint)
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(if (done) "Reabrir" else "Marcar feito") },
                onClick = {
                    menuOpen = false
                    onToggle()
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

@Composable
private fun BoardNoteDialog(serverError: String?, onConfirm: (String, NoteKind) -> Unit, onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(NoteKind.FACT) }
    val clean = draft.trim().replace(Regex("\\s+"), " ")
    val problem = if (clean.isEmpty()) null else NotebookLogic.validate(clean, SensitiveDataPolicy::containsSecret)
    val canSave = clean.isNotEmpty() && problem == null
    val shown = problem ?: serverError

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = WorkbenchTokens.SurfaceRaised,
        title = { Text("Nova anotação do time", color = WorkbenchTokens.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text("Ex.: Commits em português, prefixo feat/fix", color = WorkbenchTokens.TextFaint)
                    },
                    minLines = 2,
                    maxLines = 5,
                    shape = RoundedCornerShape(16.dp),
                    colors = boardFieldColors(),
                )
                Text("Tipo", style = MaterialTheme.typography.labelLarge, color = WorkbenchTokens.TextFaint)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    NoteKind.entries.forEach { option ->
                        BoardChoicePill(
                            text = option.label,
                            picked = option == kind,
                            tint = WorkbenchTokens.Gold,
                            onClick = { kind = option },
                        )
                    }
                }
                shown?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = WorkbenchTokens.Red)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = canSave, onClick = { onConfirm(clean, kind) }) {
                Text("Anotar", color = if (canSave) WorkbenchTokens.Text else WorkbenchTokens.TextFaint)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar", color = WorkbenchTokens.TextMuted) }
        },
    )
}

@Composable
private fun BoardFoldHead(title: String, open: Boolean, onToggle: () -> Unit, modifier: Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .pressScale(onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = WorkbenchTokens.TextMuted)
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = if (open) "ocultar" else "mostrar",
            style = MaterialTheme.typography.labelMedium,
            color = WorkbenchTokens.TextFaint,
        )
    }
}

/** "Compromisso · você · há 2 h" */
private fun boardNoteMeta(note: Note, now: Long): String {
    val who = when {
        note.source == BOARD_USER_SOURCE -> "você"
        note.source == "rotina" -> "rotina"
        note.source == "privado" -> "conversa privada"
        note.source.startsWith("grupo:") -> "conversa do grupo"
        else -> note.source
    }
    return "${note.kind.label} · $who · ${boardAgo(note.updatedAtMillis, now)}"
}

// ---------------------------------------------------------------------------------------------
// Bastidores: private talks between the group's prismas, and who read and stayed quiet
// ---------------------------------------------------------------------------------------------

@Composable
private fun BoardBackstage(group: PrismaGroup, botsById: Map<String, Bot>, modifier: Modifier) {
    val context = LocalContext.current
    remember(group.id) { PrismaDms.ensureLoaded(context) }
    val allDms by PrismaDms.all.collectAsState()
    val members = remember(group.memberIds) { group.memberIds.toSet() + PrismaAdmin.ID }
    val chat = remember(allDms, members) { PrismaDms.among(members, limit = 60) }
    val quiet = remember(group.quietLog) { group.quietLog.sortedByDescending { it.atMillis } }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "back-chat-title") {
            BoardSectionTitle(
                title = "Conversas privadas entre Prismas",
                hint = "O que os Prismas deste time disseram entre si, fora do grupo.",
            )
        }
        if (chat.isEmpty()) {
            item(key = "back-chat-empty") {
                BoardHint("Nenhuma conversa privada entre os Prismas deste time ainda.")
            }
        } else {
            items(chat, key = { it.id }) { dm ->
                BoardDmLine(dm = dm, botsById = botsById)
            }
        }
        item(key = "back-quiet-title") {
            BoardSectionTitle(
                title = "Quem leu e ficou quieto",
                hint = "Quando um Prisma lê o grupo e decide não responder, o motivo fica registrado aqui.",
            )
        }
        if (quiet.isEmpty()) {
            item(key = "back-quiet-empty") {
                BoardHint("Ninguém ficou quieto ainda.")
            }
        } else {
            itemsIndexed(quiet, key = { index, note -> "quiet-$index-${note.atMillis}" }) { _, note ->
                BoardQuietRow(note = note, botsById = botsById)
            }
        }
    }
}

@Composable
private fun BoardSectionTitle(title: String, hint: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = WorkbenchTokens.Text)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = WorkbenchTokens.TextMuted)
    }
}

@Composable
private fun BoardHint(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = WorkbenchTokens.TextMuted,
    )
}

/** One private message as a compact chat line: "Nina → Code: texto", with the sender's mascot and the time. */
@Composable
private fun BoardDmLine(dm: PrismaDm, botsById: Map<String, Bot>) {
    val fromName = boardNameOf(dm.fromId, botsById)
    val toName = boardNameOf(dm.toId, botsById)
    val fromAccent = boardAccent(dm.fromId, botsById)
    val toAccent = boardAccent(dm.toId, botsById)
    val shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BoardAvatar(id = dm.fromId, botsById = botsById, size = 24.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = fromAccent, fontWeight = FontWeight.SemiBold)) { append(fromName) }
                    withStyle(SpanStyle(color = WorkbenchTokens.TextFaint)) { append(" → ") }
                    withStyle(SpanStyle(color = toAccent, fontWeight = FontWeight.SemiBold)) { append(toName) }
                    append(": ")
                    append(dm.text)
                },
                modifier = Modifier
                    .clip(shape)
                    .background(WorkbenchTokens.Surface)
                    .border(1.dp, WorkbenchTokens.BorderSoft, shape)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = WorkbenchTokens.Text,
            )
            Text(
                text = boardWhen(dm.atMillis),
                modifier = Modifier.padding(start = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = WorkbenchTokens.TextFaint,
            )
        }
    }
}

/** "Quem leu e ficou quieto": mascot, name, the reason (muted italic) and the time. */
@Composable
private fun BoardQuietRow(note: QuietNote, botsById: Map<String, Bot>) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(WorkbenchTokens.Surface)
            .border(1.dp, WorkbenchTokens.BorderSoft, shape)
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BoardAvatar(id = note.botId, botsById = botsById, size = 28.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = boardNameOf(note.botId, botsById),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = boardAccent(note.botId, botsById),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = boardWhen(note.atMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = WorkbenchTokens.TextFaint,
                )
            }
            Text(
                text = note.reason.ifBlank { "sem motivo registrado" },
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = WorkbenchTokens.TextMuted,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Trabalho em segundo plano: the worker's status line, the editor's controls and diary
// ---------------------------------------------------------------------------------------------

/** "3 sessões · 120 mil tokens" */
private fun boardMissionStats(task: GroupTask): String {
    val sessions = if (task.sessions == 1) "1 sessão" else "${task.sessions} sessões"
    val tokens = when {
        task.tokens < 1_000L -> "${task.tokens} tokens"
        task.tokens < 1_000_000L -> "${task.tokens / 1_000L} mil tokens"
        else -> "%.1f mi tokens".format(Locale.forLanguageTag("pt-BR"), task.tokens / 1_000_000.0)
    }
    return "$sessions · $tokens"
}

/** A small dot that breathes while the worker is busy. */
@Composable
private fun BoardPulseDot(color: Color) {
    val transition = rememberInfiniteTransition(label = "boardPulse")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(800), repeatMode = RepeatMode.Reverse),
        label = "boardPulseAlpha",
    )
    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color.copy(alpha = alpha)))
}

/** The worker's state on a Quadro row: working, asleep until a time, or paused; then sessions and tokens. */
@Composable
private fun BoardMissionLine(task: GroupTask, working: Boolean, now: Long) {
    val sleeping = !working && task.wakeAtMillis > now
    val tint = when {
        working -> boardStatusColor(TaskStatus.DOING)
        sleeping -> WorkbenchTokens.Gold
        else -> WorkbenchTokens.TextMuted
    }
    val label = when {
        working -> "trabalhando"
        sleeping -> "dorme até ${SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(task.wakeAtMillis))}"
        else -> "pausada"
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                working -> BoardPulseDot(color = tint)
                sleeping -> Icon(painterResource(R.drawable.ic_moon), contentDescription = null, modifier = Modifier.size(14.dp), tint = tint)
                else -> Icon(painterResource(R.drawable.ic_pause), contentDescription = null, modifier = Modifier.size(14.dp), tint = tint)
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = tint,
            )
        }
        Text(
            text = boardMissionStats(task),
            style = MaterialTheme.typography.labelSmall,
            color = WorkbenchTokens.TextFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The editor's background-work block: start or stop the worker, and the expandable diary. */
@Composable
private fun BoardMissionSection(
    working: Boolean,
    canStart: Boolean,
    log: List<String>,
    diaryOpen: Boolean,
    onToggleDiary: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Trabalho em segundo plano", style = MaterialTheme.typography.labelLarge, color = WorkbenchTokens.TextFaint)
        Text(
            text = when {
                working -> "O Prisma trabalha nesta tarefa sozinho, na própria tela, e avisa no grupo quando terminar."
                canStart -> "O responsável pode trabalhar nesta tarefa sozinho, em segundo plano."
                else -> "Escolha um Prisma como responsável para trabalhar em segundo plano."
            },
            style = MaterialTheme.typography.bodySmall,
            color = WorkbenchTokens.TextMuted,
        )
        if (working) {
            BoardActionButton(label = "■ Parar", tint = WorkbenchTokens.Red, onClick = onStop)
        } else if (canStart) {
            BoardActionButton(label = "▶ Trabalhar em segundo plano", tint = WorkbenchTokens.Gold, onClick = onStart)
        }
        Row(
            modifier = Modifier.fillMaxWidth().pressScale(onClick = onToggleDiary).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Diário do trabalho (${log.size})",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                color = WorkbenchTokens.Text,
            )
            Text(
                text = if (diaryOpen) "ocultar" else "mostrar",
                style = MaterialTheme.typography.labelMedium,
                color = WorkbenchTokens.TextFaint,
            )
        }
        if (diaryOpen) BoardDiary(log = log)
    }
}

@Composable
private fun BoardActionButton(label: String, tint: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tint.copy(alpha = 0.14f))
            .border(1.dp, tint.copy(alpha = 0.5f), shape)
            .pressScale(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

/** The worker's log, newest line at the bottom; it scrolls down when a line is added. */
@Composable
private fun BoardDiary(log: List<String>) {
    val scroll = rememberScrollState()
    LaunchedEffect(log.size) {
        delay(16L) // let the new line be laid out before measuring the bottom
        scroll.animateScrollTo(scroll.maxValue)
    }
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .clip(shape)
            .background(WorkbenchTokens.Surface)
            .border(1.dp, WorkbenchTokens.BorderSoft, shape)
            .verticalScroll(scroll)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (log.isEmpty()) {
            Text(
                text = "Ainda sem registros. Assim que o Prisma começar, os passos aparecem aqui.",
                style = MaterialTheme.typography.labelSmall,
                color = WorkbenchTokens.TextFaint,
            )
        }
        log.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = WorkbenchTokens.TextMuted,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Telas: live thumbnails of every prisma's own screen
// ---------------------------------------------------------------------------------------------

/** One prisma's own screen on the "Telas" tab: a half-size copy of its latest frame (null = no frame yet). */
private class MissionScreenFrame(val botId: String, val displayId: Int?, val ratio: Float, val bitmap: Bitmap?)

/** Copies the latest frame of every running prisma screen. Call off the main thread. */
private fun missionCaptureScreens(): List<MissionScreenFrame> = PrismaDisplays.active().map { display ->
    val full = display.latestFrame()
    val ratio = full?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: (9f / 16f)
    val small = full?.let { source ->
        Bitmap.createScaledBitmap(source, (source.width / 2).coerceAtLeast(1), (source.height / 2).coerceAtLeast(1), true)
            .also { source.recycle() }
    }
    MissionScreenFrame(botId = display.botId, displayId = display.snapshot().displayId, ratio = ratio, bitmap = small)
}

private fun boardDisplayLabel(displayId: Int?): String =
    if (displayId == null) "tela própria" else "tela própria · display $displayId"

@Composable
private fun BoardTelas(botsById: Map<String, Bot>, modifier: Modifier) {
    var frames by remember { mutableStateOf<List<MissionScreenFrame>>(emptyList()) }
    var zoomed by rememberSaveable { mutableStateOf<String?>(null) }

    // Refreshes about once a second while the tab is open. A replaced frame is recycled one tick later,
    // when it is certainly off the screen.
    LaunchedEffect(Unit) {
        var shown: List<MissionScreenFrame> = emptyList()
        var retired: List<Bitmap> = emptyList()
        try {
            while (true) {
                retired.forEach { if (!it.isRecycled) it.recycle() }
                retired = shown.mapNotNull { it.bitmap }
                shown = withContext(Dispatchers.IO) { missionCaptureScreens() }
                frames = shown
                delay(1_000L)
            }
        } finally {
            (retired + shown.mapNotNull { it.bitmap }).forEach { if (!it.isRecycled) it.recycle() }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "screens-intro") {
            BoardHint("Cada Prisma usa a própria tela, em paralelo com as outras. Toque numa tela para ampliar.")
        }
        if (frames.isEmpty()) {
            item(key = "screens-empty") {
                BoardHint("Nenhum Prisma está usando a tela própria agora.")
            }
        } else {
            items(frames.chunked(2), key = { row -> row.joinToString("|") { it.botId } }) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    row.forEach { frame ->
                        BoardScreenCard(
                            frame = frame,
                            botsById = botsById,
                            modifier = Modifier.weight(1f),
                            onOpen = { zoomed = frame.botId },
                        )
                    }
                    if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }

    zoomed?.let { id ->
        val frame = frames.firstOrNull { it.botId == id }
        val name = botsById[id]?.name ?: "Prisma"
        Dialog(onDismissRequest = { zoomed = null }) {
            val shape = RoundedCornerShape(20.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(WorkbenchTokens.SurfaceRaised)
                    .border(1.dp, WorkbenchTokens.BorderSoft, shape)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = boardAccent(id, botsById),
                )
                if (frame == null) {
                    BoardHint("Esta tela não está mais em uso.")
                } else {
                    BoardScreenPicture(
                        bitmap = frame.bitmap,
                        ratio = frame.ratio,
                        label = "Tela de $name",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = boardDisplayLabel(frame.displayId),
                        style = MaterialTheme.typography.labelSmall,
                        color = WorkbenchTokens.TextFaint,
                    )
                }
            }
        }
    }
}

@Composable
private fun BoardScreenCard(frame: MissionScreenFrame, botsById: Map<String, Bot>, modifier: Modifier, onOpen: () -> Unit) {
    val bot = botsById[frame.botId]
    val name = bot?.name ?: "Prisma"
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(WorkbenchTokens.Surface)
            .border(1.dp, WorkbenchTokens.BorderSoft, shape)
            .pressScale(onClick = onOpen)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BoardScreenPicture(
            bitmap = frame.bitmap,
            ratio = frame.ratio,
            label = "Tela de $name",
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MascotAvatar(index = bot?.mascot ?: Mascots.forKey(frame.botId), size = 22.dp, showActivity = false)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = boardAccent(frame.botId, botsById),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = boardDisplayLabel(frame.displayId),
                    style = MaterialTheme.typography.labelSmall,
                    color = WorkbenchTokens.TextFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun BoardScreenPicture(bitmap: Bitmap?, ratio: Float, label: String, modifier: Modifier) {
    val shape = RoundedCornerShape(10.dp)
    val image = bitmap?.takeUnless { it.isRecycled }?.asImageBitmap()
    Box(
        modifier = modifier
            .aspectRatio(ratio)
            .clip(shape)
            .background(WorkbenchTokens.SurfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = label,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text("Sem imagem ainda", style = MaterialTheme.typography.labelSmall, color = WorkbenchTokens.TextFaint)
        }
    }
}
