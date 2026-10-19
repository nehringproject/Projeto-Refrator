package dev.agentworkbench

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Calendar
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------------------------
// Agenda: the timeline of routines, the routine sheet, the autonomy controls and the Maestro card.
// ---------------------------------------------------------------------------------------------

/** Where a timeline dot sits, measured from the top of a row (the centre of the avatar). */
private val agendaDotY = 34.dp

private const val AGENDA_DAY_MS = 86_400_000L

private val agendaWeekdayLong = listOf("Domingo", "Segunda-feira", "Terça-feira", "Quarta-feira", "Quinta-feira", "Sexta-feira", "Sábado")
private val agendaWeekdayShort = listOf("domingo", "segunda", "terça", "quarta", "quinta", "sexta", "sábado")

/** Day letters with their Calendar.DAY_OF_WEEK values (1 = domingo … 7 = sábado). */
private val agendaWeekLetters = listOf("D" to 1, "S" to 2, "T" to 3, "Q" to 4, "Q" to 5, "S" to 6, "S" to 7)
private val agendaWorkdays = setOf(2, 3, 4, 5, 6)
private val agendaWeekend = setOf(1, 7)

/** Whole calendar days from today to the day of [millis] (0 = today). */
private fun agendaDaysFrom(nowMillis: Long, millis: Long): Long {
    fun startOfDay(value: Long): Long = Calendar.getInstance().apply {
        timeInMillis = value
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    return Math.round((startOfDay(millis) - startOfDay(nowMillis)).toDouble() / AGENDA_DAY_MS)
}

/** 0 = domingo … 6 = sábado. */
private fun agendaWeekdayIndex(millis: Long): Int =
    Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.DAY_OF_WEEK) - 1

private fun agendaDate(millis: Long): String {
    val calendar = Calendar.getInstance().apply { timeInMillis = millis }
    return "%02d/%02d".format(calendar.get(Calendar.DAY_OF_MONTH), calendar.get(Calendar.MONTH) + 1)
}

private fun agendaClock(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)

private fun agendaCount(count: Int, one: String, many: String): String = "$count ${if (count == 1) one else many}"

/** Heading of a day in the timeline: Hoje, Amanhã, Quarta-feira, or with the date when it is a week away. */
private fun agendaSectionLabel(millis: Long, now: Long): String {
    val days = agendaDaysFrom(now, millis)
    return when {
        days == 0L -> "Hoje"
        days == 1L -> "Amanhã"
        days >= 7L -> "${agendaWeekdayLong[agendaWeekdayIndex(millis)]} · ${agendaDate(millis)}"
        else -> agendaWeekdayLong[agendaWeekdayIndex(millis)]
    }
}

/** Short day word for sentences: hoje, amanhã, quarta, or the date. */
private fun agendaWhenLabel(millis: Long, now: Long): String {
    val days = agendaDaysFrom(now, millis)
    return when {
        days == 0L -> "hoje"
        days == 1L -> "amanhã"
        days >= 7L -> agendaDate(millis)
        else -> agendaWeekdayShort[agendaWeekdayIndex(millis)]
    }
}

/** "em 2 h 10 min", counting from [nowMillis]. */
private fun agendaRelative(atMillis: Long, nowMillis: Long): String {
    val minutes = ((atMillis - nowMillis) / 60_000L).coerceAtLeast(0L)
    return when {
        minutes < 1L -> "em instantes"
        minutes < 60L -> "em $minutes min"
        minutes < 24L * 60L -> {
            val hours = minutes / 60L
            val rest = minutes % 60L
            if (rest == 0L) "em $hours h" else "em $hours h $rest min"
        }
        else -> {
            val days = minutes / (24L * 60L)
            if (days == 1L) "em 1 dia" else "em $days dias"
        }
    }
}

/** Steps a minute value by [steps] five-minute notches, wrapping inside the hour. */
private fun agendaStepMinute(minute: Int, steps: Int): Int = ((minute / 5) * 5 + steps * 5 + 60) % 60

/** Moves an hour by [delta], wrapping over the day. */
private fun agendaWrapHour(hour: Int, delta: Int): Int = (hour + delta + 24) % 24

private fun agendaLimitLabel(limit: Int): String =
    if (limit == 0) "Nenhuma mensagem por conta própria" else "Até $limit ${if (limit == 1) "mensagem" else "mensagens"} por dia"

/** Who speaks for a routine: the group's first member when it runs in a group, else its own prisma. */
private class AgendaOwner(val mascot: Int, val name: String, val group: PrismaGroup?)

private fun agendaOwner(routine: PrismaRoutine, botsById: Map<String, Bot>, groupsById: Map<String, PrismaGroup>): AgendaOwner {
    val group = routine.groupId?.let { groupsById[it] }
    val bot = group?.memberIds?.firstOrNull()?.let { botsById[it] } ?: botsById[routine.botId]
    val isAdmin = routine.botId == PrismaAdmin.ID
    return AgendaOwner(
        mascot = bot?.mascot ?: if (isAdmin) PrismaAdmin.MASCOT else Mascots.forKey(routine.botId),
        name = bot?.name ?: if (isAdmin) "Maestro" else "Prisma",
        group = group,
    )
}

/** Ready-made routines for the empty state and the editor. A fresh list gives fresh ids. */
private fun agendaTemplates(botId: String): List<PrismaRoutine> = listOf(
    PrismaRoutine(
        botId = botId,
        title = "Briefing matinal",
        instruction = "Me mande um resumo do meu dia: compromissos, clima e o que precisa da minha atenção.",
        hour = 7,
        minute = 30,
    ),
    PrismaRoutine(
        botId = botId,
        title = "Resumo da noite",
        instruction = "Faça um resumo curto do que aconteceu hoje nas minhas conversas e o que ficou pendente.",
        hour = 21,
        minute = 0,
    ),
    PrismaRoutine(
        botId = botId,
        title = "Revisão semanal",
        instruction = "Revise a semana que passou e proponha 3 prioridades para esta semana.",
        days = setOf(Calendar.MONDAY),
        hour = 9,
        minute = 0,
    ),
)

/** One line of the timeline: a day heading, or a routine. */
private sealed interface AgendaItem {
    val key: String

    class Header(override val key: String, val label: String) : AgendaItem

    class Entry(
        val routine: PrismaRoutine,
        /** When it fires next, or null while paused. */
        val nextAt: Long?,
        /** The very next routine to fire; its dot gets the soft pulse. */
        val isNext: Boolean,
    ) : AgendaItem {
        override val key: String get() = routine.id
    }
}

/** Upcoming routines in time order under day headings, then the paused ones. */
private fun agendaTimeline(routines: List<PrismaRoutine>, now: Long): List<AgendaItem> {
    val upcoming = routines
        .mapNotNull { routine -> routine.nextRunAfter(now)?.let { routine to it } }
        .sortedBy { it.second }
    val upcomingIds = upcoming.mapTo(HashSet()) { it.first.id }
    val paused = routines.filter { it.id !in upcomingIds }.sortedBy { it.title.lowercase() }
    val items = mutableListOf<AgendaItem>()
    var currentDay = -1L
    upcoming.forEachIndexed { index, (routine, at) ->
        val day = agendaDaysFrom(now, at)
        if (day != currentDay) {
            currentDay = day
            items += AgendaItem.Header(key = "day-$day", label = agendaSectionLabel(at, now))
        }
        items += AgendaItem.Entry(routine = routine, nextAt = at, isNext = index == 0)
    }
    if (paused.isNotEmpty()) {
        items += AgendaItem.Header(key = "paused", label = "Pausadas")
        paused.forEach { items += AgendaItem.Entry(routine = it, nextAt = null, isNext = false) }
    }
    return items
}

// ---------------------------------------------------------------------------------------------
// Agenda screen
// ---------------------------------------------------------------------------------------------

/** The timeline of every routine: today, tomorrow, the weekdays ahead, and the paused ones. */
@Composable
internal fun AgendaScreen(
    routines: List<PrismaRoutine>,
    bots: List<Bot>,
    groups: List<PrismaGroup>,
    onBack: () -> Unit,
    onEdit: (PrismaRoutine?) -> Unit,
    onToggle: (PrismaRoutine, Boolean) -> Unit,
    onRunNow: (PrismaRoutine) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The clock ticks every half minute, so the "em 2 h 10 min" countdowns stay true.
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(30_000L)
        }
    }
    val timeline = remember(routines, now) { agendaTimeline(routines, now) }
    val botsById = remember(bots) { bots.associateBy { it.id } }
    val groupsById = remember(groups) { groups.associateBy { it.id } }
    val activeCount = routines.count { it.enabled }
    val subtitle = if (routines.isEmpty()) {
        "Nenhuma rotina ainda"
    } else {
        "${agendaCount(activeCount, "ativa", "ativas")} · ${agendaCount(routines.size - activeCount, "pausada", "pausadas")}"
    }

    Column(modifier = modifier.fillMaxSize()) {
        AgendaTopBar(subtitle = subtitle, onBack = onBack, onNew = { onEdit(null) })
        if (routines.isEmpty()) {
            AgendaEmpty(onEdit = onEdit, modifier = Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 32.dp),
            ) {
                itemsIndexed(timeline, key = { _, item -> item.key }) { index, item ->
                    when (item) {
                        is AgendaItem.Header -> AgendaDayHeader(
                            key = item.key,
                            label = item.label,
                            index = index,
                            placement = Modifier.animateItem(),
                        )
                        is AgendaItem.Entry -> AgendaRoutineRow(
                            placement = Modifier.animateItem(),
                            entry = item,
                            now = now,
                            index = index,
                            isLast = index == timeline.lastIndex,
                            botsById = botsById,
                            groupsById = groupsById,
                            onEdit = onEdit,
                            onToggle = onToggle,
                            onRunNow = onRunNow,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AgendaTopBar(subtitle: String, onBack: () -> Unit, onNew: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 14.dp, top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgendaCircleButton(description = "Voltar", onClick = onBack) {
            Icon(
                painterResource(R.drawable.ic_arrow_back),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = WorkbenchTokens.Text,
            )
        }
        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
            Text(
                "Agenda dos Prismas",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = WorkbenchTokens.Text,
            )
            Text(subtitle, style = MaterialTheme.typography.labelMedium, color = WorkbenchTokens.TextMuted)
        }
        AgendaCircleButton(description = "Nova rotina", onClick = onNew) {
            Icon(painterResource(R.drawable.ic_plus), contentDescription = null, modifier = Modifier.size(20.dp), tint = WorkbenchTokens.Text)
        }
    }
}

@Composable
private fun AgendaCircleButton(description: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .pressScale(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        // 48dp touch area around a 44dp drawn circle.
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(WorkbenchTokens.Surface)
                .border(1.dp, WorkbenchTokens.BorderSoft, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

/** Empty state: a friendly mascot and three ready-made routines to start from. */
@Composable
private fun AgendaEmpty(onEdit: (PrismaRoutine?) -> Unit, modifier: Modifier = Modifier) {
    val templates = remember { agendaTemplates(PrismaAdmin.ID) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MascotAvatar(index = 2, size = 84.dp, mood = MascotMood.HAPPY)
        Text(
            "Nenhuma rotina ainda.",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = WorkbenchTokens.Text,
        )
        Text(
            "Agende quando um Prisma deve te chamar primeiro, como um briefing de manhã ou um resumo à noite.",
            style = MaterialTheme.typography.bodyMedium,
            color = WorkbenchTokens.TextMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(6.dp))
        templates.forEach { template ->
            AgendaTemplateCard(template = template, onClick = { onEdit(template) })
        }
    }
}

@Composable
private fun AgendaTemplateCard(template: PrismaRoutine, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(WorkbenchTokens.Surface)
            .border(1.dp, WorkbenchTokens.BorderSoft, RoundedCornerShape(20.dp))
            .padding(14.dp)
            .pressScale(pressedScale = 0.98f, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(template.title, style = MaterialTheme.typography.titleSmall, color = WorkbenchTokens.Text)
            Text(template.scheduleLabel(), style = MaterialTheme.typography.labelMedium, color = WorkbenchTokens.TextFaint)
            Text(
                template.instruction,
                style = MaterialTheme.typography.bodySmall,
                color = WorkbenchTokens.TextMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            painterResource(R.drawable.ic_plus),
            contentDescription = "Usar esta rotina",
            modifier = Modifier.size(20.dp),
            tint = WorkbenchTokens.TextMuted,
        )
    }
}

/** A day heading inside the timeline; the gutter line runs straight through it. */
@Composable
private fun AgendaDayHeader(key: String, label: String, index: Int, placement: Modifier = Modifier) {
    Row(
        modifier = placement
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .appearOnce(key = "agenda:$key", index = index),
    ) {
        Box(
            modifier = Modifier
                .width(28.dp)
                .fillMaxHeight()
                .drawBehind {
                    drawLine(
                        color = WorkbenchTokens.Border,
                        start = Offset(size.width / 2f, 0f),
                        end = Offset(size.width / 2f, size.height),
                        strokeWidth = 2.dp.toPx(),
                    )
                },
        )
        Text(
            label,
            modifier = Modifier.padding(start = 8.dp, top = 22.dp, bottom = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = WorkbenchTokens.TextMuted,
        )
    }
}

/** One routine on the timeline: dot and line, avatar, text, run-now button and the switch. */
@Composable
private fun AgendaRoutineRow(
    entry: AgendaItem.Entry,
    now: Long,
    index: Int,
    isLast: Boolean,
    botsById: Map<String, Bot>,
    groupsById: Map<String, PrismaGroup>,
    onEdit: (PrismaRoutine?) -> Unit,
    onToggle: (PrismaRoutine, Boolean) -> Unit,
    onRunNow: (PrismaRoutine) -> Unit,
    placement: Modifier = Modifier,
) {
    val routine = entry.routine
    val owner = agendaOwner(routine, botsById, groupsById)
    val accent = Mascots.of(owner.mascot).accent
    val paused = entry.nextAt == null
    val dotColor = if (paused) WorkbenchTokens.TextFaint else accent
    val who = owner.group?.let { "${owner.name} no grupo ${it.name}" } ?: owner.name
    val lastLine = routine.lastResult
        ?.lineSequence()
        ?.map { it.trim() }
        ?.firstOrNull { it.isNotEmpty() }
        ?.take(120)

    Row(
        modifier = placement
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .appearOnce(key = "agenda:${routine.id}", index = index),
    ) {
        // The timeline gutter: a thread down the list, with a dot where the row starts.
        Box(
            modifier = Modifier
                .width(28.dp)
                .fillMaxHeight()
                .drawBehind {
                    drawLine(
                        color = WorkbenchTokens.Border,
                        start = Offset(size.width / 2f, 0f),
                        end = Offset(size.width / 2f, if (isLast) agendaDotY.toPx() else size.height),
                        strokeWidth = 2.dp.toPx(),
                    )
                },
        ) {
            val dot = Modifier.align(Alignment.TopCenter).padding(top = agendaDotY - 5.dp)
            when {
                paused -> Box(dot.size(10.dp).background(dotColor, CircleShape))
                entry.isNext -> PulseDot(color = dotColor, modifier = dot, diameter = 10.dp)
                else -> Box(dot.size(10.dp).background(dotColor, CircleShape))
            }
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .padding(top = 10.dp, bottom = 10.dp, end = 4.dp)
                .pressScale(pressedScale = 0.98f, onClick = { onEdit(routine) }),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MascotAvatar(index = owner.mascot, size = 48.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = routine.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (paused) WorkbenchTokens.TextMuted else WorkbenchTokens.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "$who · ${routine.scheduleLabel()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = WorkbenchTokens.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.nextAt?.let { agendaRelative(it, now) } ?: "pausada",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = when {
                        paused -> WorkbenchTokens.TextFaint
                        entry.isNext -> accent
                        else -> WorkbenchTokens.TextMuted
                    },
                )
                lastLine?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = WorkbenchTokens.TextFaint,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.padding(top = 10.dp).height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AgendaRunButton(accent = accent, onClick = { onRunNow(routine) })
            Switch(
                checked = routine.enabled,
                onCheckedChange = { onToggle(routine, it) },
                colors = agendaSwitchColors(accent),
            )
        }
    }
}

@Composable
private fun AgendaRunButton(accent: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(WorkbenchTokens.Surface)
            .border(1.dp, WorkbenchTokens.BorderSoft, CircleShape)
            .pressScale(onClick = onClick)
            .semantics { contentDescription = "Rodar agora" },
        contentAlignment = Alignment.Center,
    ) {
        Text("▶︎", style = MaterialTheme.typography.labelMedium, color = accent)
    }
}

@Composable
private fun agendaSwitchColors(accent: Color): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = accent,
    checkedBorderColor = accent,
    uncheckedThumbColor = WorkbenchTokens.TextMuted,
    uncheckedTrackColor = WorkbenchTokens.SurfaceHigh,
    uncheckedBorderColor = WorkbenchTokens.Border,
)

@Composable
private fun agendaFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = WorkbenchTokens.Border,
    unfocusedBorderColor = WorkbenchTokens.BorderSoft,
    cursorColor = WorkbenchTokens.Text,
)

// ---------------------------------------------------------------------------------------------
// Routine editor
// ---------------------------------------------------------------------------------------------

/**
 * Create or edit a routine: who calls, what they do, which days and when, optional group, on/off.
 * [onDelete] is shown only for existing routines; the title reads "Nova rotina" when it is null.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RoutineEditorSheet(
    initial: PrismaRoutine?,
    bots: List<Bot>,
    groups: List<PrismaGroup>,
    defaultBotId: String?,
    onSave: (PrismaRoutine) -> Unit,
    onDelete: ((PrismaRoutine) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val start = remember(initial?.id) {
        initial ?: PrismaRoutine(botId = defaultBotId ?: PrismaAdmin.ID, title = "", instruction = "")
    }
    var draft by remember(start.id) { mutableStateOf(start) }
    var groupMenu by remember { mutableStateOf(false) }
    val templates = remember { agendaTemplates(PrismaAdmin.ID) }
    // Maestro is always first in the picker, even if the list handed in does not carry it.
    val prismas = remember(bots) {
        val admin = bots.firstOrNull { it.id == PrismaAdmin.ID } ?: PrismaAdmin.default()
        listOf(admin) + bots.filter { it.id != PrismaAdmin.ID }
    }
    val chosen = prismas.firstOrNull { it.id == draft.botId }
    val accent = Mascots.of(chosen?.mascot ?: PrismaAdmin.MASCOT).accent
    val group = groups.firstOrNull { it.id == draft.groupId }
    val now = System.currentTimeMillis()
    val nextAt = draft.nextRunAfter(now)
    val everyDay = draft.days.isEmpty() || draft.days.size >= 7
    // An empty set means every day, so show all seven as lit.
    val shownDays = if (draft.days.isEmpty()) (1..7).toSet() else draft.days
    val canSave = draft.instruction.isNotBlank()
    val isNew = initial == null || onDelete == null
    val saveSource = remember { MutableInteractionSource() }

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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (isNew) "Nova rotina" else "Editar rotina",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = WorkbenchTokens.Text,
                )
                Text(
                    "Um Prisma te chama primeiro, no horário que você escolher.",
                    style = MaterialTheme.typography.labelMedium,
                    color = WorkbenchTokens.TextMuted,
                )
            }

            AgendaSection("Quem chama") {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    prismas.forEach { bot ->
                        AgendaPrismaChip(
                            bot = bot,
                            selected = bot.id == draft.botId,
                            onClick = { draft = draft.copy(botId = bot.id) },
                        )
                    }
                }
            }

            AgendaSection("Título") {
                OutlinedTextField(
                    value = draft.title,
                    onValueChange = { draft = draft.copy(title = it.take(60)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Ex.: Briefing matinal", color = WorkbenchTokens.TextFaint) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = agendaFieldColors(),
                )
            }

            AgendaSection("O que o Prisma faz") {
                OutlinedTextField(
                    value = draft.instruction,
                    onValueChange = { draft = draft.copy(instruction = it.take(1_500)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text("O que o Prisma deve fazer nesse horário?", color = WorkbenchTokens.TextFaint)
                    },
                    minLines = 3,
                    maxLines = 6,
                    shape = RoundedCornerShape(16.dp),
                    colors = agendaFieldColors(),
                )
            }

            AgendaSection("Modelos prontos") {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    templates.forEach { template ->
                        AgendaTextChip(
                            text = template.title,
                            selected = draft.title == template.title && draft.instruction == template.instruction,
                            accent = accent,
                            onClick = { draft = draft.copy(title = template.title, instruction = template.instruction) },
                        )
                    }
                }
            }

            AgendaSection("Dias") {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    agendaWeekLetters.forEach { (letter, day) ->
                        AgendaDayToggle(
                            letter = letter,
                            active = day in shownDays,
                            accent = accent,
                            onClick = {
                                val next = if (day in shownDays) shownDays - day else shownDays + day
                                // Never leave the routine with no day at all.
                                if (next.isNotEmpty()) {
                                    draft = draft.copy(days = if (next.size >= 7) emptySet() else next)
                                }
                            },
                        )
                    }
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AgendaTextChip(
                        text = "Todo dia",
                        selected = everyDay,
                        accent = accent,
                        onClick = { draft = draft.copy(days = emptySet()) },
                    )
                    AgendaTextChip(
                        text = "Dias úteis",
                        selected = draft.days == agendaWorkdays,
                        accent = accent,
                        onClick = { draft = draft.copy(days = agendaWorkdays) },
                    )
                    AgendaTextChip(
                        text = "Fim de semana",
                        selected = draft.days == agendaWeekend,
                        accent = accent,
                        onClick = { draft = draft.copy(days = agendaWeekend) },
                    )
                }
            }

            AgendaSection("Horário") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    AgendaStepper(
                        valueText = "%02d".format(draft.hour),
                        onMinus = { draft = draft.copy(hour = (draft.hour + 23) % 24) },
                        onPlus = { draft = draft.copy(hour = (draft.hour + 1) % 24) },
                    )
                    Text(
                        ":",
                        modifier = Modifier.padding(horizontal = 10.dp),
                        style = MaterialTheme.typography.titleLarge,
                        color = WorkbenchTokens.TextMuted,
                    )
                    AgendaStepper(
                        valueText = "%02d".format(draft.minute),
                        onMinus = { draft = draft.copy(minute = agendaStepMinute(draft.minute, -1)) },
                        onPlus = { draft = draft.copy(minute = agendaStepMinute(draft.minute, 1)) },
                    )
                }
                Text(
                    text = nextAt?.let { "Próxima vez: ${agendaWhenLabel(it, now)}, ${agendaClock(draft.hour, draft.minute)}" }
                        ?: "Pausada: não dispara até ser ativada.",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (nextAt == null) WorkbenchTokens.TextMuted else accent,
                    textAlign = TextAlign.Center,
                )
            }

            AgendaSection("Rodar num grupo") {
                Box {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(WorkbenchTokens.Surface)
                            .border(1.dp, WorkbenchTokens.BorderSoft, RoundedCornerShape(16.dp))
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                            .pressScale(onClick = { groupMenu = true }),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = group?.let { "${it.name} · ${agendaCount(it.memberIds.size, "prisma", "prismas")}" }
                                ?: "Conversa direta com o Prisma",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = WorkbenchTokens.Text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text("▾︎", style = MaterialTheme.typography.labelMedium, color = WorkbenchTokens.TextMuted)
                    }
                    DropdownMenu(expanded = groupMenu, onDismissRequest = { groupMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Conversa direta com o Prisma") },
                            onClick = {
                                groupMenu = false
                                draft = draft.copy(groupId = null)
                            },
                        )
                        groups.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.name) },
                                onClick = {
                                    groupMenu = false
                                    draft = draft.copy(groupId = option.id)
                                },
                            )
                        }
                    }
                }
                val hint = when {
                    group != null -> "A rodada acontece dentro do grupo, com os prismas dele."
                    groups.isEmpty() -> "Crie um grupo na aba Prismas para que vários Prismas conversem."
                    else -> null
                }
                hint?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = WorkbenchTokens.TextFaint)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Ativa", style = MaterialTheme.typography.titleSmall, color = WorkbenchTokens.Text)
                    Text(
                        text = if (draft.enabled) "Ela dispara no horário escolhido." else "Pausada: não dispara sozinha.",
                        style = MaterialTheme.typography.bodySmall,
                        color = WorkbenchTokens.TextMuted,
                    )
                }
                Switch(
                    checked = draft.enabled,
                    onCheckedChange = { draft = draft.copy(enabled = it) },
                    colors = agendaSwitchColors(accent),
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (onDelete != null && initial != null) {
                    TextButton(onClick = { onDelete(draft) }) { Text("Apagar rotina", color = WorkbenchTokens.Red) }
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancelar", color = WorkbenchTokens.TextMuted) }
                Surface(
                    onClick = {
                        if (canSave) {
                            onSave(
                                draft.copy(
                                    title = draft.title.trim().ifBlank { "Rotina" },
                                    instruction = draft.instruction.trim(),
                                    days = if (draft.days.size >= 7) emptySet() else draft.days.filter { it in 1..7 }.toSet(),
                                    groupId = group?.id,
                                ),
                            )
                        }
                    },
                    modifier = Modifier.pressScale(saveSource),
                    interactionSource = saveSource,
                    shape = RoundedCornerShape(50),
                    color = if (canSave) WorkbenchTokens.Text else WorkbenchTokens.SurfaceHigh,
                ) {
                    Text(
                        text = "Salvar",
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 11.dp),
                        color = WorkbenchTokens.Canvas,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

/** A labelled block inside the editor: a small heading above its content. */
@Composable
private fun AgendaSection(label: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = WorkbenchTokens.TextFaint)
        content()
    }
}

/** A prisma as a small card with its face; the chosen one lights up in its own accent. */
@Composable
private fun AgendaPrismaChip(bot: Bot, selected: Boolean, onClick: () -> Unit) {
    val accent = Mascots.of(bot.mascot).accent
    val edge by animateColorAsState(if (selected) accent else WorkbenchTokens.BorderSoft, label = "agendaPrismaEdge")
    val fill by animateColorAsState(if (selected) accent.copy(alpha = 0.14f) else Color.Transparent, label = "agendaPrismaFill")
    Column(
        modifier = Modifier
            .width(84.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(fill)
            .border(if (selected) 2.dp else 1.dp, edge, RoundedCornerShape(18.dp))
            .padding(vertical = 10.dp, horizontal = 6.dp)
            .pressScale(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MascotAvatar(index = bot.mascot, size = 44.dp)
        Text(
            text = bot.name,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) WorkbenchTokens.Text else WorkbenchTokens.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A pill chip in the accent colour when selected. */
@Composable
private fun AgendaTextChip(text: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    val edge by animateColorAsState(if (selected) accent else WorkbenchTokens.BorderSoft, label = "agendaChipEdge")
    val fill by animateColorAsState(if (selected) accent.copy(alpha = 0.16f) else Color.Transparent, label = "agendaChipFill")
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
    )
}

/** One round day toggle: a letter that lights up in the prisma's accent. */
@Composable
private fun AgendaDayToggle(letter: String, active: Boolean, accent: Color, onClick: () -> Unit) {
    val fill by animateColorAsState(if (active) accent.copy(alpha = 0.22f) else Color.Transparent, label = "agendaDayFill")
    val edge by animateColorAsState(if (active) accent else WorkbenchTokens.BorderSoft, label = "agendaDayEdge")
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(fill)
            .border(1.dp, edge, CircleShape)
            .pressScale(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            letter,
            style = MaterialTheme.typography.labelLarge,
            color = if (active) accent else WorkbenchTokens.TextMuted,
        )
    }
}

/** A value between − and +, in a pill. */
@Composable
private fun AgendaStepper(valueText: String, onMinus: () -> Unit, onPlus: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(WorkbenchTokens.Surface)
            .border(1.dp, WorkbenchTokens.BorderSoft, RoundedCornerShape(50))
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgendaRoundKey(label = "−", description = "Diminuir", onClick = onMinus)
        Text(
            text = valueText,
            modifier = Modifier.widthIn(min = 52.dp).padding(horizontal = 4.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = WorkbenchTokens.Text,
            textAlign = TextAlign.Center,
        )
        AgendaRoundKey(label = "+", description = "Aumentar", onClick = onPlus)
    }
}

@Composable
private fun AgendaRoundKey(label: String, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(WorkbenchTokens.SurfaceHigh)
            .pressScale(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = WorkbenchTokens.Text)
    }
}

// ---------------------------------------------------------------------------------------------
// Autonomy
// ---------------------------------------------------------------------------------------------

/**
 * How freely a prisma may reach out on its own: three cards, then (when not "Nunca") the daily
 * message allowance and the quiet hours. Every change reports all four values back through [onChange].
 */
@Composable
internal fun AutonomyEditor(
    autonomy: PrismaAutonomy,
    dailyLimit: Int,
    quietFrom: Int,
    quietUntil: Int,
    onChange: (PrismaAutonomy, Int, Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val quietText = if (quietFrom == quietUntil) {
        "Sem horário de silêncio."
    } else {
        "Silêncio das ${"%02d".format(quietFrom)} h às ${"%02d".format(quietUntil)} h"
    }
    Column(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Pode te chamar primeiro?", style = MaterialTheme.typography.titleSmall, color = WorkbenchTokens.Text)
        PrismaAutonomy.values().forEach { option ->
            AgendaAutonomyCard(
                option = option,
                selected = option == autonomy,
                onClick = { onChange(option, dailyLimit, quietFrom, quietUntil) },
            )
        }
        AnimatedVisibility(
            visible = autonomy != PrismaAutonomy.NEVER,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = agendaLimitLabel(dailyLimit),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = WorkbenchTokens.Text,
                    )
                    AgendaStepper(
                        valueText = dailyLimit.toString(),
                        onMinus = { onChange(autonomy, (dailyLimit - 1).coerceIn(0, 20), quietFrom, quietUntil) },
                        onPlus = { onChange(autonomy, (dailyLimit + 1).coerceIn(0, 20), quietFrom, quietUntil) },
                    )
                }
                Text(quietText, style = MaterialTheme.typography.bodyMedium, color = WorkbenchTokens.Text)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AgendaHourStepper(
                        label = "Começa",
                        hour = quietFrom,
                        modifier = Modifier.weight(1f),
                        onStep = { delta -> onChange(autonomy, dailyLimit, agendaWrapHour(quietFrom, delta), quietUntil) },
                    )
                    AgendaHourStepper(
                        label = "Termina",
                        hour = quietUntil,
                        modifier = Modifier.weight(1f),
                        onStep = { delta -> onChange(autonomy, dailyLimit, quietFrom, agendaWrapHour(quietUntil, delta)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AgendaHourStepper(label: String, hour: Int, onStep: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = WorkbenchTokens.TextFaint)
        AgendaStepper(
            valueText = "%02d h".format(hour),
            onMinus = { onStep(-1) },
            onPlus = { onStep(1) },
        )
    }
}

@Composable
private fun AgendaAutonomyCard(option: PrismaAutonomy, selected: Boolean, onClick: () -> Unit) {
    val edge by animateColorAsState(if (selected) WorkbenchTokens.Gold else WorkbenchTokens.BorderSoft, label = "autonomyEdge")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(WorkbenchTokens.Surface)
            .border(if (selected) 1.5f.dp else 1.dp, edge, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .pressScale(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(option.label, style = MaterialTheme.typography.titleSmall, color = WorkbenchTokens.Text)
            Text(option.hint, style = MaterialTheme.typography.bodySmall, color = WorkbenchTokens.TextMuted)
        }
        AgendaCheck(selected = selected)
    }
}

/** A small disc that fills with a check mark when its card is chosen. */
@Composable
private fun AgendaCheck(selected: Boolean) {
    val fill by animateColorAsState(if (selected) WorkbenchTokens.Gold else Color.Transparent, label = "autonomyCheckFill")
    val edge by animateColorAsState(if (selected) WorkbenchTokens.Gold else WorkbenchTokens.Border, label = "autonomyCheckEdge")
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(fill)
            .border(1.dp, edge, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible = selected,
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut(),
        ) {
            Canvas(modifier = Modifier.size(12.dp)) {
                drawPath(
                    path = Path().apply {
                        moveTo(size.width * 0.12f, size.height * 0.55f)
                        lineTo(size.width * 0.42f, size.height * 0.82f)
                        lineTo(size.width * 0.9f, size.height * 0.2f)
                    },
                    color = WorkbenchTokens.Canvas,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Central card (top of the Prismas tab)
// ---------------------------------------------------------------------------------------------

/** Hero card for the Prismas tab: Maestro, the next routine, and the three entry points. */
@Composable
internal fun CentralCard(
    admin: Bot,
    routinesCount: Int,
    groupsCount: Int,
    nextRoutine: PrismaRoutine?,
    onOpenAdmin: () -> Unit,
    onOpenAgenda: () -> Unit,
    onNewGroup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val glow = Mascots.of(admin.mascot).accent
    val transition = rememberInfiniteTransition(label = "centralGlow")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(5_200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "centralDrift",
    )
    val shape = RoundedCornerShape(24.dp)
    val edge = Brush.linearGradient(
        listOf(glow.copy(alpha = 0.55f), WorkbenchTokens.BorderSoft, WorkbenchTokens.Border),
    )
    val nextLine = nextRoutine
        ?.let { "Próxima: ${it.title} às ${agendaClock(it.hour, it.minute)}" }
        ?: "Nenhuma rotina agendada"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(shape)
            .background(WorkbenchTokens.Surface)
            .drawBehind {
                // A soft glow in the Maestro's colour that drifts slowly across the top of the card.
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(glow.copy(alpha = 0.2f), Color.Transparent),
                        center = Offset(size.width * (0.15f + 0.7f * drift), 0f),
                        radius = size.width * 0.7f,
                    ),
                )
            }
            .border(1.dp, edge, shape)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "Central de comando",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = WorkbenchTokens.Text,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MascotAvatar(index = admin.mascot, size = 64.dp, pokeOnTap = true)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "${admin.name} · administrador geral",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = WorkbenchTokens.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    nextLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = WorkbenchTokens.TextMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${agendaCount(groupsCount, "grupo", "grupos")} · ${agendaCount(routinesCount, "rotina", "rotinas")}",
                    style = MaterialTheme.typography.labelMedium,
                    color = WorkbenchTokens.TextFaint,
                )
            }
        }
        AgendaPill(
            text = "Falar com o Maestro",
            primary = true,
            modifier = Modifier.fillMaxWidth(),
            onClick = onOpenAdmin,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AgendaPill(
                text = "Agenda ($routinesCount)",
                primary = false,
                modifier = Modifier.weight(1f),
                onClick = onOpenAgenda,
            )
            AgendaPill(
                text = "Novo grupo",
                primary = false,
                modifier = Modifier.weight(1f),
                onClick = onNewGroup,
            )
        }
    }
}

@Composable
private fun AgendaPill(text: String, primary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(50))
            .background(if (primary) WorkbenchTokens.Text else WorkbenchTokens.SurfaceHigh)
            .border(1.dp, if (primary) WorkbenchTokens.Text else WorkbenchTokens.BorderSoft, RoundedCornerShape(50))
            .padding(horizontal = 14.dp)
            .pressScale(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (primary) WorkbenchTokens.Canvas else WorkbenchTokens.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
