package dev.agentworkbench

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Group chats: a WhatsApp-style room where the user and several prismas talk. Maestro moderates,
 * so the room stays organised: rounds, stances, a scoreboard per round, decisions and notices.
 */

// Tints used only by the group screens.
private val GroupAmberTint = Color(0xFFF2A33A)
private val GroupYellowTint = Color(0xFFF2C94C)
private val GroupTealTint = Color(0xFF2DD4BF)
private val GroupBlueTint = Color(0xFF7AA2FF)

/** Who wrote a message, resolved once per render from the bot list. */
private class GroupSpeaker(val name: String, val mascot: Int, val accent: Color, val admin: Boolean)

private fun groupSpeaker(id: String, bots: Map<String, Bot>): GroupSpeaker = when (id) {
    GroupAuthors.USER -> GroupSpeaker(name = "Você", mascot = 0, accent = WorkbenchTokens.Text, admin = false)
    GroupAuthors.SYSTEM -> GroupSpeaker(name = "Sistema", mascot = 0, accent = WorkbenchTokens.TextMuted, admin = false)
    else -> bots[id]?.let { bot ->
        GroupSpeaker(
            name = bot.name,
            mascot = bot.mascot,
            accent = Mascots.of(bot.mascot).accent,
            admin = PrismaAdmin.isAdmin(bot),
        )
    } ?: Mascots.forKey(id).let { index ->
        GroupSpeaker(name = "Prisma", mascot = index, accent = Mascots.of(index).accent, admin = false)
    }
}

private fun groupStanceColor(stance: Stance): Color = when (stance) {
    Stance.FAVOR -> WorkbenchTokens.Green
    Stance.AGAINST -> WorkbenchTokens.Red
    Stance.PROPOSAL -> GroupBlueTint
    Stance.NEUTRAL -> WorkbenchTokens.TextMuted
}

private fun groupKindAccent(kind: GroupMessageKind): Color = when (kind) {
    GroupMessageKind.SUGGESTION -> GroupAmberTint
    GroupMessageKind.ANNOUNCEMENT -> GroupYellowTint
    GroupMessageKind.CONTEMPLATE -> GroupBlueTint
    else -> WorkbenchTokens.Gold
}

private fun groupNextKind(kind: GroupMessageKind): GroupMessageKind = when (kind) {
    GroupMessageKind.TEXT -> GroupMessageKind.SUGGESTION
    GroupMessageKind.SUGGESTION -> GroupMessageKind.ANNOUNCEMENT
    GroupMessageKind.ANNOUNCEMENT -> GroupMessageKind.CONTEMPLATE
    else -> GroupMessageKind.TEXT
}

/** Consecutive bubbles from the same author share one avatar and name, unless they are minutes apart. */
private fun groupContinues(previous: GroupMessage, current: GroupMessage): Boolean =
    previous.authorId == current.authorId &&
        groupIsBubble(previous.kind) && groupIsBubble(current.kind) &&
        current.createdAtMillis - previous.createdAtMillis < 5 * 60_000L

private fun groupIsBubble(kind: GroupMessageKind): Boolean =
    kind == GroupMessageKind.TEXT || kind == GroupMessageKind.SUGGESTION || kind == GroupMessageKind.CONTEMPLATE

/**
 * Mini scoreboard per summary: the latest stance of each prisma inside the messages between the
 * previous summary and this one, counted per stance.
 */
private fun groupScoreboards(messages: List<GroupMessage>): Map<String, List<Pair<Stance, Int>>> {
    val boards = HashMap<String, List<Pair<Stance, Int>>>()
    var windowStart = 0
    for (index in messages.indices) {
        val summary = messages[index]
        if (summary.kind != GroupMessageKind.SUMMARY) continue
        val latest = LinkedHashMap<String, Stance>()
        for (earlier in windowStart until index) {
            val message = messages[earlier]
            val stance = message.stance ?: continue
            if (message.authorId != GroupAuthors.USER && message.authorId != GroupAuthors.SYSTEM) {
                latest[message.authorId] = stance
            }
        }
        boards[summary.id] = Stance.entries.mapNotNull { option ->
            latest.values.count { it == option }.takeIf { it > 0 }?.let { option to it }
        }
        windowStart = index + 1
    }
    return boards
}

private fun groupClock(millis: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

private fun groupDayLabel(millis: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    return when {
        now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR) ->
            groupClock(millis)
        System.currentTimeMillis() - millis < 6L * 24 * 3_600_000 ->
            SimpleDateFormat("EEE", Locale.forLanguageTag("pt-BR")).format(Date(millis)).replaceFirstChar { it.uppercase() }.trimEnd('.')
        else -> SimpleDateFormat("dd/MM", Locale.getDefault()).format(Date(millis))
    }
}

private fun groupPreview(message: GroupMessage, bots: Map<String, Bot>): String {
    val text = message.text.replace('\n', ' ')
    return when (message.kind) {
        GroupMessageKind.SUMMARY -> "Resumo"
        GroupMessageKind.DECISION -> "Decisão: $text"
        GroupMessageKind.SYSTEM -> text
        GroupMessageKind.ANNOUNCEMENT -> "Comunicado de ${groupSpeaker(message.authorId, bots).name}: $text"
        else -> "${groupSpeaker(message.authorId, bots).name}: $text"
    }
}

/** Puts "@Nome " into the draft, keeping a space before it. */
private fun groupWithMention(draft: String, name: String): String {
    val base = if (draft.isEmpty() || draft.endsWith(" ") || draft.endsWith("\n")) draft else "$draft "
    return "$base@$name "
}

private fun groupCopy(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Mensagem do grupo", text))
    Toast.makeText(context, "Mensagem copiada", Toast.LENGTH_SHORT).show()
}

private fun groupBubbleShape(mine: Boolean, first: Boolean) = if (mine) {
    RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
} else {
    RoundedCornerShape(if (first) 6.dp else 20.dp, 20.dp, 20.dp, 20.dp)
}

/** Slots for up to three mascots inside the group circle: fractions of the size (x, y, scale). */
private class GroupSlot(val x: Float, val y: Float, val scale: Float)

private fun groupAvatarSlots(count: Int): List<GroupSlot> = when (count) {
    1 -> listOf(GroupSlot(0.2f, 0.2f, 0.6f))
    2 -> listOf(GroupSlot(0.03f, 0.03f, 0.56f), GroupSlot(0.41f, 0.41f, 0.56f))
    else -> listOf(GroupSlot(0.03f, 0.03f, 0.52f), GroupSlot(0.45f, 0.1f, 0.46f), GroupSlot(0.14f, 0.45f, 0.46f))
}

/** Text with @mentions highlighted and an optional blinking caret while the model writes. */
private fun groupMentionText(text: String, names: List<String>, caret: Float?): AnnotatedString = buildAnnotatedString {
    append(text)
    names.forEach { name ->
        val token = "@$name"
        var from = text.indexOf(token, ignoreCase = true)
        while (from >= 0) {
            addStyle(SpanStyle(color = WorkbenchTokens.Gold, fontWeight = FontWeight.SemiBold), from, from + token.length)
            from = text.indexOf(token, from + token.length, ignoreCase = true)
        }
    }
    if (caret != null) {
        val start = length
        append("|")
        addStyle(SpanStyle(color = WorkbenchTokens.Text.copy(alpha = caret)), start, length)
    }
}

// ---------------------------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------------------------

/** Conversation of one group: header, pinned notice, topic card, messages and the composer. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun GroupChatScreen(
    group: PrismaGroup,
    bots: List<Bot>,
    busy: Boolean,
    speakingId: String?,
    onBack: () -> Unit,
    onSend: (text: String, kind: GroupMessageKind, replyToId: String?) -> Unit,
    onNextRound: () -> Unit,
    onDecide: () -> Unit,
    onStop: () -> Unit,
    onEdit: () -> Unit,
    onToggleAutoReply: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** Everyone typing right now (several prismas can type at once). */
    typingIds: Set<String> = emptySet(),
    /** Opens the team board ("Painel do time"); the caller shows [GroupBoardSheet]. */
    onOpenBoard: () -> Unit = {},
) {
    val botsById = remember(bots) { bots.associateBy { it.id } }
    val members = remember(group.memberIds, botsById) { group.memberIds.mapNotNull { botsById[it] } }
    val memberNames = remember(members) { members.map { it.name } }
    // Everyone who can be tagged (Maestro too) and the short @ of each.
    val taggable = remember(members, botsById) { listOfNotNull(botsById[PrismaAdmin.ID]) + members.filterNot(PrismaAdmin::isAdmin) }
    val handles = remember(taggable) { Mentions.handles(taggable) }
    val mentionTokens = remember(taggable, handles) { handles.values.toList() + taggable.map { it.name } + listOf("você", "voce", "usuário", "usuario") }
    val messages = group.messages
    // Newest first: the list runs reversed, so the newest message sits at the bottom of the screen.
    val ordered = remember(messages) { messages.filterNot { it.streaming }.asReversed() }
    val typingMessages = remember(messages) { messages.filter { it.streaming } }
    val indexById = remember(ordered) { ordered.withIndex().associate { (index, message) -> message.id to index } }
    val messageById = remember(messages) { messages.associateBy { it.id } }
    val boards = remember(messages) { groupScoreboards(messages) }
    val openedAt = remember { System.currentTimeMillis() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var draft by rememberSaveable { mutableStateOf("") }
    var kind by remember { mutableStateOf(GroupMessageKind.TEXT) }
    var replyToId by rememberSaveable { mutableStateOf<String?>(null) }
    var decisionsOpen by rememberSaveable { mutableStateOf(false) }

    val replyTarget = replyToId?.let { messageById[it] }
    val replyName = replyTarget?.let { groupSpeaker(it.authorId, botsById).name }.orEmpty()
    val speakingName = speakingId?.let { groupSpeaker(it, botsById).name }
    val typingNames = typingIds.map { groupSpeaker(it, botsById).name }.ifEmpty { listOfNotNull(speakingName) }
    val subtitle = if (busy && typingNames.isNotEmpty()) {
        when (typingNames.size) {
            1 -> "${typingNames[0]} está digitando…"
            2 -> "${typingNames[0]} e ${typingNames[1]} estão digitando…"
            else -> "${typingNames.size} Prismas estão digitando…"
        }
    } else if (busy) {
        "digitando…"
    } else {
        "${group.memberIds.size} Prismas · ${if (group.autoReply) "time livre" else "respostas pausadas"}"
    }

    // Follow the conversation: jump to the newest message when you sent it or when you are already there.
    val newestId = messages.lastOrNull()?.id
    LaunchedEffect(newestId) {
        val mine = messages.lastOrNull()?.authorId == GroupAuthors.USER
        if (mine || listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0)
    }

    fun send() {
        val text = draft.trim()
        if (text.isEmpty()) return
        onSend(text, kind, replyToId)
        draft = ""
        kind = GroupMessageKind.TEXT
        replyToId = null
        scope.launch { listState.animateScrollToItem(0) }
    }

    Column(modifier = modifier.fillMaxSize().background(WorkbenchTokens.Canvas)) {
        GroupTopBar(
            group = group,
            bots = bots,
            busy = busy,
            subtitle = subtitle,
            onBack = onBack,
            onEdit = onEdit,
            onToggleAutoReply = onToggleAutoReply,
            onOpenDecisions = { decisionsOpen = true },
            openTasks = group.tasks.count { it.status != TaskStatus.DONE },
            onOpenBoard = onOpenBoard,
        )
        GroupPinnedBar(notice = group.pinnedNotice)
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            // Emitted first, so it sits at the bottom of the reversed list.
            val typists = (typingMessages.map { it.authorId } + typingIds + listOfNotNull(speakingId)).distinct()
            if (typists.isNotEmpty()) {
                item(key = "typing") {
                    GroupTypingRow(
                        speakers = typists.map { groupSpeaker(it, botsById) },
                        mascots = typists.map { id -> botsById[id]?.mascot ?: 0 },
                        tools = typingMessages.filter { it.activity.isNotEmpty() }
                            .map { groupSpeaker(it.authorId, botsById).name + ": " + it.activity.last() },
                        placement = Modifier.animateItem(),
                    )
                }
            }
            itemsIndexed(ordered, key = { _, message -> message.id }) { index, message ->
                val previous = ordered.getOrNull(index + 1)
                GroupMessageRow(
                    message = message,
                    // A new message slides into place and older ones glide up to make room.
                    placement = Modifier.animateItem(),
                    first = previous == null || !groupContinues(previous, message),
                    fresh = message.createdAtMillis >= openedAt - 500,
                    botsById = botsById,
                    names = mentionTokens,
                    quoted = message.replyToId?.let { messageById[it] },
                    board = boards[message.id],
                    onReply = { replyToId = message.id },
                    onCopy = { groupCopy(context, message.text) },
                    onQuote = {
                        indexById[message.replyToId.orEmpty()]?.let { target ->
                            scope.launch { listState.animateScrollToItem(target) }
                        }
                    },
                )
            }
            // Emitted last, so it ends up at the top of the list, like a group description.
            item(key = "topic") { GroupTopicCard(group = group, memberNames = memberNames) }
        }
        GroupComposer(
            busy = busy,
            members = taggable,
            handles = handles,
            replyTo = replyTarget,
            replyName = replyName,
            draft = draft,
            kind = kind,
            onDraftChange = { draft = it },
            onKindChange = { kind = it },
            onCancelReply = { replyToId = null },
            onMention = { name -> draft = groupWithMention(draft, name) },
            onSend = { send() },
            onNextRound = onNextRound,
            onDecide = onDecide,
            onStop = onStop,
        )
    }

    if (decisionsOpen) {
        GroupDecisionsSheet(decisions = group.decisions, onDismiss = { decisionsOpen = false })
    }
}

/**
 * Up to three member mascots overlapping inside one circle of [size]. While [active] the members
 * take turns bobbing, so the group looks busy without knowing who is speaking.
 */
@Composable
internal fun GroupAvatar(
    group: PrismaGroup,
    bots: List<Bot>,
    size: Dp,
    active: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val mascots = remember(group.memberIds, bots) {
        group.memberIds.mapNotNull { id -> bots.firstOrNull { it.id == id }?.mascot }
            .take(3)
            .ifEmpty { listOf(PrismaAdmin.MASCOT) }
    }
    var lead by remember { mutableIntStateOf(0) }
    LaunchedEffect(active, mascots.size) {
        while (active) {
            delay(1_300)
            lead = (lead + 1) % mascots.size
        }
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(WorkbenchTokens.SurfaceHigh)
            .border(1.dp, WorkbenchTokens.BorderSoft, CircleShape),
    ) {
        val slots = groupAvatarSlots(mascots.size)
        mascots.forEachIndexed { index, mascot ->
            val slot = slots[index]
            MascotAvatar(
                index = mascot,
                size = size * slot.scale,
                active = active && index == lead % mascots.size,
                modifier = Modifier.offset(x = size * slot.x, y = size * slot.y),
            )
        }
    }
}

/** A home-list row for a group, shaped like the chat rows. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GroupRow(
    group: PrismaGroup,
    bots: List<Bot>,
    busy: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val botsById = remember(bots) { bots.associateBy { it.id } }
    val last = group.lastMessage
    val preview = when {
        busy -> "Prismas estão digitando…"
        last != null -> groupPreview(last, botsById)
        else -> group.topic.ifBlank { "Grupo sem mensagens" }
    }
    val stamp = last?.createdAtMillis?.takeIf { it > 0 } ?: group.updatedAtMillis
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .pressScale(pressedScale = 0.98f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .drawBehind {
                // Hairline under the text column, like a messaging app.
                drawLine(
                    WorkbenchTokens.BorderSoft,
                    Offset(92.dp.toPx(), size.height),
                    Offset(size.width, size.height),
                    1.dp.toPx(),
                )
            }
            .padding(horizontal = 12.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        GroupAvatar(group = group, bots = bots, size = 60.dp, active = busy)
        Column(modifier = Modifier.weight(1f).animateContentSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = WorkbenchTokens.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                GroupTag(text = "grupo", color = GroupTealTint)
            }
            Text(
                text = preview,
                style = MaterialTheme.typography.bodyMedium,
                color = WorkbenchTokens.TextMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(groupDayLabel(stamp), style = MaterialTheme.typography.labelSmall, color = WorkbenchTokens.TextFaint)
            // Discreet "more" button: opens the same actions as the long press.
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .pressScale(onClick = onLongClick)
                    .semantics { contentDescription = "Ações do grupo" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_more_vertical),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = WorkbenchTokens.TextFaint,
                )
            }
        }
    }
}

/** Create or edit a group: name, agenda, mode, members (at least two), rhythm and auto-reply. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupEditorSheet(
    initial: PrismaGroup?,
    bots: List<Bot>,
    onSave: (PrismaGroup) -> Unit,
    onDelete: ((PrismaGroup) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val prismas = remember(bots) { bots.filter { !PrismaAdmin.isAdmin(it) } }
    val key = initial?.id
    var name by remember(key) { mutableStateOf(initial?.name.orEmpty()) }
    var topic by remember(key) { mutableStateOf(initial?.topic.orEmpty()) }
    var mode by remember(key) { mutableStateOf(initial?.mode ?: GroupMode.CHAT) }
    var members by remember(key) { mutableStateOf(initial?.memberIds.orEmpty()) }
    var speakers by remember(key) { mutableIntStateOf(initial?.speakersPerRound ?: 3) }
    var rounds by remember(key) { mutableIntStateOf(initial?.maxRounds ?: 4) }
    var autoReply by remember(key) { mutableStateOf(initial?.autoReply ?: true) }
    var autoApprove by remember(key) { mutableStateOf(initial?.autoApprove ?: true) }
    var roles by remember(key) { mutableStateOf(initial?.roles.orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }

    // Only prismas that still exist can be members; order of selection is the speaking order.
    val chosen = members.filter { id -> prismas.any { it.id == id } }
    val canSave = name.isNotBlank() && chosen.size >= 2
    val saveSource = remember { MutableInteractionSource() }

    val save = {
        val now = System.currentTimeMillis()
        val base = initial
        val saved = if (base == null) {
            PrismaGroup(
                name = name.trim(),
                topic = topic.trim(),
                memberIds = chosen,
                roles = roles.filterKeys { it in chosen },
                mode = mode,
                maxRounds = rounds,
                autoReply = autoReply,
                autoApprove = autoApprove,
                speakersPerRound = speakers,
                messages = listOf(
                    GroupMessage(
                        authorId = GroupAuthors.SYSTEM,
                        kind = GroupMessageKind.SYSTEM,
                        text = "Grupo criado. O time já pode conversar.",
                        createdAtMillis = now,
                    ),
                ),
                createdAtMillis = now,
                updatedAtMillis = now,
            )
        } else {
            base.copy(
                name = name.trim(),
                topic = topic.trim(),
                memberIds = chosen,
                roles = roles.filterKeys { it in chosen },
                mode = mode,
                maxRounds = rounds,
                autoReply = autoReply,
                autoApprove = autoApprove,
                speakersPerRound = speakers,
                updatedAtMillis = now,
            )
        }
        onSave(saved)
    }

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
                .imePadding()
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                GroupAvatar(
                    group = PrismaGroup(id = key ?: "draft", name = name, topic = topic, memberIds = chosen),
                    bots = bots,
                    size = 64.dp,
                    active = true,
                )
                Column {
                    Text(
                        text = if (initial == null) "Novo grupo" else "Editar grupo",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = WorkbenchTokens.Text,
                    )
                    Text(
                        text = "Monte o time e delegue uma função para cada Prisma.",
                        style = MaterialTheme.typography.labelMedium,
                        color = WorkbenchTokens.TextMuted,
                    )
                }
            }

            GroupField(label = "Nome do grupo", value = name, singleLine = true) { name = it.take(40) }
            GroupField(label = "Objetivo do time (o que vocês vão fazer)", value = topic, minLines = 3) { topic = it.take(1_000) }

            GroupSectionLabel("Time")
            if (prismas.isEmpty()) {
                Text(
                    text = "Crie pelo menos dois prismas na aba Prismas para montar um grupo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = WorkbenchTokens.TextMuted,
                )
            } else {
                prismas.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { bot ->
                            GroupMemberTile(
                                bot = bot,
                                order = chosen.indexOf(bot.id),
                                modifier = Modifier.weight(1f),
                            ) {
                                members = if (bot.id in members) members - bot.id else members + bot.id
                            }
                        }
                        repeat(4 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                    }
                }
            }
            Text(
                text = if (chosen.size < 2) {
                    "Escolha pelo menos 2 prismas."
                } else {
                    "${chosen.size} prismas no time"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (chosen.size < 2) WorkbenchTokens.Red else WorkbenchTokens.TextFaint,
            )

            // The user delegates a function to each member; it shapes how they coordinate.
            chosen.forEach { id ->
                val member = prismas.firstOrNull { it.id == id } ?: return@forEach
                GroupField(label = "Função de ${member.name} (ex.: pesquisa, código, revisão)", value = roles[id].orEmpty(), singleLine = true) {
                    roles = roles + (id to it.take(120))
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Resposta automática", style = MaterialTheme.typography.titleSmall, color = WorkbenchTokens.Text)
                    Text(
                        text = "Os prismas conversam e trabalham sozinhos; desligue para só você falar.",
                        style = MaterialTheme.typography.bodySmall,
                        color = WorkbenchTokens.TextMuted,
                    )
                }
                Switch(checked = autoReply, onCheckedChange = { autoReply = it })
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Aprovação automática", style = MaterialTheme.typography.titleSmall, color = WorkbenchTokens.Text)
                    Text(
                        text = "Ferramentas rodam sozinhas; mensagens, ligações, compras, apagar e a tela principal ainda pedem sua permissão.",
                        style = MaterialTheme.typography.bodySmall,
                        color = WorkbenchTokens.TextMuted,
                    )
                }
                Switch(checked = autoApprove, onCheckedChange = { autoApprove = it })
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (initial != null && onDelete != null) {
                    TextButton(onClick = { confirmDelete = true }) { Text("Apagar grupo", color = WorkbenchTokens.Red) }
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancelar", color = WorkbenchTokens.TextMuted) }
                Surface(
                    onClick = { if (canSave) save() },
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

    if (confirmDelete && initial != null && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = WorkbenchTokens.SurfaceRaised,
            title = { Text("Apagar o grupo?", color = WorkbenchTokens.Text) },
            text = {
                Text(
                    text = "A conversa e as decisões somem deste aparelho. Os prismas continuam existindo.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = WorkbenchTokens.TextMuted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(initial)
                }) { Text("Apagar", color = WorkbenchTokens.Red) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancelar", color = WorkbenchTokens.TextMuted) }
            },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Header, banners and the topic card
// ---------------------------------------------------------------------------------------------

@Composable
private fun GroupTopBar(
    group: PrismaGroup,
    bots: List<Bot>,
    busy: Boolean,
    subtitle: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onToggleAutoReply: (Boolean) -> Unit,
    onOpenDecisions: () -> Unit,
    /** Tasks not finished yet, shown on the board pill. */
    openTasks: Int = 0,
    onOpenBoard: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    WorkbenchTokens.BorderSoft,
                    Offset(0f, size.height),
                    Offset(size.width, size.height),
                    1.dp.toPx(),
                )
            }
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GroupHeaderButton(icon = R.drawable.ic_arrow_back, description = "Voltar", onClick = onBack)
        GroupAvatar(group = group, bots = bots, size = 40.dp, active = busy, modifier = Modifier.padding(start = 4.dp))
        Column(modifier = Modifier.padding(start = 10.dp).weight(1f)) {
            Text(
                text = group.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = WorkbenchTokens.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PulseDot(color = if (busy) InkTokens.Glint else WorkbenchTokens.Green)
                AnimatedContent(
                    targetState = subtitle,
                    label = "groupSubtitle",
                    transitionSpec = { fadeIn(tween(UiMotion.Standard)) togetherWith fadeOut(tween(UiMotion.Quick)) },
                ) { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.labelMedium,
                        color = WorkbenchTokens.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        // The pill is drawn small, but its touch area is at least 48dp.
        Box(
            modifier = Modifier
                .padding(end = 4.dp)
                .minimumInteractiveComponentSize()
                .pressScale(onClick = onOpenBoard)
                .semantics { contentDescription = "Painel do time: $openTasks tarefas abertas" },
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(WorkbenchTokens.Surface)
                    .border(1.dp, WorkbenchTokens.BorderSoft, RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_clipboard),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = WorkbenchTokens.Text,
                )
                AnimatedCount(
                    count = openTasks,
                    style = MaterialTheme.typography.labelMedium,
                    color = WorkbenchTokens.Text,
                )
            }
        }
        GroupHeaderButton(icon = R.drawable.ic_edit, description = "Editar grupo", onClick = onEdit)
        Box {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .pressScale(onClick = { menuOpen = true })
                    .semantics { contentDescription = "Mais opções" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_more_horizontal), contentDescription = null, modifier = Modifier.size(20.dp), tint = WorkbenchTokens.Text)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("Resposta automática", modifier = Modifier.weight(1f))
                            Switch(checked = group.autoReply, onCheckedChange = null)
                        }
                    },
                    onClick = {
                        menuOpen = false
                        onToggleAutoReply(!group.autoReply)
                    },
                )
                DropdownMenuItem(
                    text = { Text("Decisões (${group.decisions.size})") },
                    onClick = {
                        menuOpen = false
                        onOpenDecisions()
                    },
                )
            }
        }
    }
}

@Composable
private fun GroupHeaderButton(icon: Int, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .pressScale(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp), tint = WorkbenchTokens.Text)
    }
}

/** Pinned notice under the header: two lines, tap to expand. */
@Composable
private fun GroupPinnedBar(notice: String?) {
    AnimatedVisibility(
        visible = notice != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        var expanded by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(WorkbenchTokens.GoldSoft)
                .border(1.dp, WorkbenchTokens.Gold.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                .clickable { expanded = !expanded }
                .animateContentSize()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_pin),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = WorkbenchTokens.Gold,
            )
            Text(
                text = notice.orEmpty(),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = WorkbenchTokens.Text,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The group description: agenda, mode hint and who is in the room. */
@Composable
private fun GroupTopicCard(group: PrismaGroup, memberNames: List<String>) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(shape)
            .background(WorkbenchTokens.Surface)
            .border(1.dp, WorkbenchTokens.BorderSoft, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Objetivo do time", style = MaterialTheme.typography.labelLarge, color = WorkbenchTokens.TextFaint)
        Text(
            text = group.topic.ifBlank { "Sem objetivo ainda. Conte ao time o que vocês vão fazer juntos." },
            style = MaterialTheme.typography.bodyLarge,
            color = if (group.topic.isBlank()) WorkbenchTokens.TextMuted else WorkbenchTokens.Text,
        )
        Text(
            text = (if (memberNames.isEmpty()) "Nenhum prisma no grupo ainda." else "Time: ${memberNames.joinToString(", ")}.") +
                (if (group.tokensTotal > 0) " Gasto do grupo: ${group.tokensTotal / 1_000} mil tokens (${group.tokensSinceUser / 1_000} mil desde a sua última mensagem)." else ""),
            style = MaterialTheme.typography.labelSmall,
            color = WorkbenchTokens.TextFaint,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Messages
// ---------------------------------------------------------------------------------------------

@Composable
private fun GroupMessageRow(
    message: GroupMessage,
    first: Boolean,
    fresh: Boolean,
    botsById: Map<String, Bot>,
    names: List<String>,
    quoted: GroupMessage?,
    board: List<Pair<Stance, Int>>?,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onQuote: () -> Unit,
    /** Lazy-list placement (animateItem), supplied by the list. */
    placement: Modifier = Modifier,
) {
    val speaker = groupSpeaker(message.authorId, botsById)
    val base = placement
        .fillMaxWidth()
        .padding(top = if (first) 10.dp else 2.dp)
    // Only messages that arrived while this screen is open get the entrance; history just appears.
    val modifier = if (fresh) base.appearOnce(key = "gmsg:${message.id}") else base
    when (message.kind) {
        GroupMessageKind.SYSTEM -> GroupSystemPill(text = message.text, modifier = modifier)
        GroupMessageKind.ANNOUNCEMENT -> GroupAnnouncementCard(message, speaker, modifier, onReply, onCopy)
        GroupMessageKind.SUMMARY -> GroupSummaryCard(message, speaker, board, modifier, onReply, onCopy)
        GroupMessageKind.DECISION -> GroupDecisionCard(message, speaker, modifier, onReply, onCopy)
        GroupMessageKind.TEXT, GroupMessageKind.SUGGESTION, GroupMessageKind.CONTEMPLATE -> GroupBubbleRow(
            message = message,
            speaker = speaker,
            first = first,
            quoted = quoted,
            quotedSpeaker = quoted?.let { groupSpeaker(it.authorId, botsById) },
            names = names,
            modifier = modifier,
            onReply = onReply,
            onCopy = onCopy,
            onQuote = onQuote,
        )
    }
}

/** Plain text bubble (user or prisma). Suggestions are right-aligned with an amber edge. */
@Composable
private fun GroupBubbleRow(
    message: GroupMessage,
    speaker: GroupSpeaker,
    first: Boolean,
    quoted: GroupMessage?,
    quotedSpeaker: GroupSpeaker?,
    names: List<String>,
    modifier: Modifier,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onQuote: () -> Unit,
) {
    val mine = message.authorId == GroupAuthors.USER
    val suggestion = message.kind == GroupMessageKind.SUGGESTION
    val right = mine || suggestion
    val shape = groupBubbleShape(mine = right, first = first)
    val edge = when {
        suggestion -> GroupAmberTint.copy(alpha = 0.65f)
        mine -> WorkbenchTokens.UserBubbleBorder
        else -> WorkbenchTokens.BorderSoft
    }
    val mood = when {
        message.error != null -> MascotMood.ANNOYED
        message.streaming -> MascotMood.WORKING
        else -> MascotMood.IDLE
    }
    val waiting = message.streaming && message.text.isEmpty()

    Row(
        modifier = modifier,
        horizontalArrangement = if (right) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!right) {
            Box(modifier = Modifier.width(28.dp)) {
                if (first) MascotAvatar(index = speaker.mascot, size = 28.dp, mood = mood)
            }
            Spacer(modifier = Modifier.width(8.dp))
        }
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(start = if (right) 48.dp else 0.dp, end = if (right) 0.dp else 40.dp),
            horizontalAlignment = if (right) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (suggestion) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(painterResource(R.drawable.ic_lightbulb), contentDescription = null, modifier = Modifier.size(14.dp), tint = GroupAmberTint)
                    Text(
                        text = "Sugestão" + (if (mine) "" else " · ${speaker.name}"),
                        style = MaterialTheme.typography.labelMedium,
                        color = GroupAmberTint,
                    )
                }
            } else if (first) {
                Text(
                    text = speaker.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = speaker.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            message.stance?.let { GroupStanceChip(stance = it, confidence = message.confidence) }
            GroupActionBox(onReply = onReply, onCopy = onCopy) {
                Column(
                    modifier = Modifier
                        .clip(shape)
                        .background(if (right) WorkbenchTokens.UserBubble else WorkbenchTokens.Surface)
                        .border(1.dp, edge, shape)
                        .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (quoted != null && quotedSpeaker != null) GroupQuote(quoted, quotedSpeaker, onQuote)
                    if (message.reasoning.isNotBlank()) {
                        GroupReasoningFold(reasoning = message.reasoning, thinkingMillis = message.thinkingMillis)
                    }
                    if (waiting) {
                        GroupTypingDots(color = speaker.accent)
                    } else {
                        GroupBody(text = message.text, names = names, streaming = message.streaming)
                    }
                    // What the prisma did with tools for this message (agenda, web, files…).
                    if (message.activity.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(painterResource(R.drawable.ic_tools), contentDescription = null, modifier = Modifier.size(12.dp), tint = WorkbenchTokens.TextMuted)
                            Text(
                                text = message.activity.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = WorkbenchTokens.TextMuted,
                            )
                        }
                    }
                    if (message.createdAtMillis > 0 && !waiting) {
                        Text(
                            text = groupClock(message.createdAtMillis),
                            modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = WorkbenchTokens.TextFaint,
                        )
                    }
                }
            }
            message.error?.let { error ->
                Text(
                    text = error,
                    modifier = Modifier.padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = WorkbenchTokens.Red,
                )
            }
        }
    }
}

/** "Pensou por Xs": the prisma's reasoning, folded above its answer and opened on tap. */
@Composable
private fun GroupReasoningFold(reasoning: String, thinkingMillis: Long) {
    var open by remember { mutableStateOf(false) }
    val label = if (thinkingMillis > 0) "Pensou por ${((thinkingMillis + 500) / 1_000).coerceAtLeast(1)}s" else "Pensamento"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { open = !open }
            .animateContentSize()
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (open) "▾" else "▸", style = MaterialTheme.typography.labelSmall, color = WorkbenchTokens.TextFaint)
            Text(label, style = MaterialTheme.typography.labelMedium, color = WorkbenchTokens.TextMuted)
        }
        AnimatedVisibility(
            visible = open,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Text(
                text = reasoning,
                modifier = Modifier.padding(start = 14.dp, end = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = WorkbenchTokens.TextMuted,
            )
        }
    }
}

@Composable
private fun GroupBody(text: String, names: List<String>, streaming: Boolean) {
    val caret = groupCaretAlpha()
    Text(
        text = groupMentionText(text, names, if (streaming) caret else null),
        style = MaterialTheme.typography.bodyLarge,
        color = WorkbenchTokens.Text,
    )
}

/** Soft blinking caret alpha for a message that is still being written. */
@Composable
private fun groupCaretAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "groupCaret")
    return transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(520, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "groupCaretAlpha",
    ).value
}

/** Three dots that rise in turn: the "is writing" indicator. */
@Composable
private fun GroupTypingDots(color: Color) {
    BouncingDots(color = color, modifier = Modifier.padding(vertical = 4.dp, horizontal = 2.dp))
}

/** Reply target quoted above the bubble: author and one line of text. Tap to jump to it. */
@Composable
private fun GroupQuote(quoted: GroupMessage, speaker: GroupSpeaker, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.28f))
            .clickable(onClick = onClick)
            .drawBehind { drawRect(speaker.accent, size = Size(3.dp.toPx(), size.height)) }
            .padding(start = 12.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Text(
            text = speaker.name,
            style = MaterialTheme.typography.labelMedium,
            color = speaker.accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = quoted.text.replace('\n', ' '),
            style = MaterialTheme.typography.bodySmall,
            color = WorkbenchTokens.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun GroupStanceChip(stance: Stance, confidence: Int?) {
    val color = groupStanceColor(stance)
    val label = if (confidence == null) stance.label else "${stance.label} · ${confidence.coerceIn(0, 100)}%"
    Text(
        text = label,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
}

/** Centred announcement ("Comunicado") with a soft yellow tint. */
@Composable
private fun GroupAnnouncementCard(
    message: GroupMessage,
    speaker: GroupSpeaker,
    modifier: Modifier,
    onReply: () -> Unit,
    onCopy: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        GroupActionBox(onReply = onReply, onCopy = onCopy, modifier = Modifier.fillMaxWidth(0.94f)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(GroupYellowTint.copy(alpha = 0.10f))
                    .border(1.dp, GroupYellowTint.copy(alpha = 0.40f), shape)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(painterResource(R.drawable.ic_megaphone), contentDescription = null, modifier = Modifier.size(16.dp), tint = GroupYellowTint)
                    Text("Comunicado", style = MaterialTheme.typography.labelLarge, color = GroupYellowTint)
                    Text("· ${speaker.name}", style = MaterialTheme.typography.labelMedium, color = WorkbenchTokens.TextMuted)
                }
                Text(message.text, style = MaterialTheme.typography.bodyLarge, color = WorkbenchTokens.Text)
            }
        }
    }
}

/** Maestro's end-of-round summary with the stance scoreboard. */
@Composable
private fun GroupSummaryCard(
    message: GroupMessage,
    speaker: GroupSpeaker,
    board: List<Pair<Stance, Int>>?,
    modifier: Modifier,
    onReply: () -> Unit,
    onCopy: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    GroupActionBox(onReply = onReply, onCopy = onCopy, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(WorkbenchTokens.SurfaceRaised)
                .border(1.dp, WorkbenchTokens.Border, shape)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MascotAvatar(index = speaker.mascot, size = 28.dp)
                Column {
                    Text("Resumo", style = MaterialTheme.typography.titleSmall, color = WorkbenchTokens.Text)
                    Text(speaker.name, style = MaterialTheme.typography.labelSmall, color = WorkbenchTokens.TextFaint)
                }
            }
            Text(message.text, style = MaterialTheme.typography.bodyMedium, color = WorkbenchTokens.Text)
            if (board.isNullOrEmpty()) {
                Text(
                    text = "Resumo do Maestro.",
                    style = MaterialTheme.typography.labelSmall,
                    color = WorkbenchTokens.TextFaint,
                )
            } else {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    board.forEach { (stance, count) -> GroupScorePill(stance = stance, count = count) }
                }
            }
        }
    }
}

@Composable
private fun GroupScorePill(stance: Stance, count: Int) {
    val color = groupStanceColor(stance)
    Text(
        text = "${stance.label} $count",
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = color,
    )
}

/** A decision taken by the group: green, full width and emphasised. */
@Composable
private fun GroupDecisionCard(
    message: GroupMessage,
    speaker: GroupSpeaker,
    modifier: Modifier,
    onReply: () -> Unit,
    onCopy: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    GroupActionBox(onReply = onReply, onCopy = onCopy, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(WorkbenchTokens.GreenSoft)
                .border(1.dp, WorkbenchTokens.Green.copy(alpha = 0.55f), shape)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null, modifier = Modifier.size(18.dp), tint = WorkbenchTokens.Green)
                Text("Decisão", style = MaterialTheme.typography.titleSmall, color = WorkbenchTokens.Green)
                Spacer(modifier = Modifier.weight(1f))
                Text(speaker.name, style = MaterialTheme.typography.labelSmall, color = WorkbenchTokens.TextFaint)
            }
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = WorkbenchTokens.Text,
            )
        }
    }
}

/** Small centred grey pill for system lines. */
@Composable
private fun GroupSystemPill(text: String, modifier: Modifier) {
    Box(modifier = modifier.padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(WorkbenchTokens.Surface)
                .border(1.dp, WorkbenchTokens.BorderSoft, RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = WorkbenchTokens.TextMuted,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * One "digitando…" bubble for everyone typing at once, like WhatsApp: their mascots in a row
 * (overlapping, each busy), the names, the three dots, and what tools are running right now.
 */
@Composable
private fun GroupTypingRow(
    speakers: List<GroupSpeaker>,
    mascots: List<Int>,
    tools: List<String>,
    placement: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp)
    val first = speakers.firstOrNull() ?: return
    Row(
        modifier = placement.fillMaxWidth().padding(top = 10.dp).animateContentSize(),
        verticalAlignment = Alignment.Top,
    ) {
        Box(modifier = Modifier.width((28 + (mascots.size.coerceAtMost(4) - 1) * 16).dp).height(28.dp)) {
            mascots.take(4).forEachIndexed { index, mascot ->
                MascotAvatar(
                    index = mascot,
                    size = 28.dp,
                    mood = MascotMood.WORKING,
                    modifier = Modifier.offset(x = (index * 16).dp),
                    showActivity = false,
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = when (speakers.size) {
                    1 -> first.name
                    2 -> "${speakers[0].name} e ${speakers[1].name}"
                    else -> speakers.dropLast(1).joinToString(", ") { it.name } + " e " + speakers.last().name
                },
                style = MaterialTheme.typography.labelLarge,
                color = first.accent,
            )
            Box(
                modifier = Modifier
                    .clip(shape)
                    .background(WorkbenchTokens.Surface)
                    .border(1.dp, WorkbenchTokens.BorderSoft, shape)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                GroupTypingDots(color = first.accent)
            }
            tools.take(3).forEach { line ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(painterResource(R.drawable.ic_tools), contentDescription = null, modifier = Modifier.size(12.dp), tint = WorkbenchTokens.TextMuted)
                    Text(line, style = MaterialTheme.typography.labelSmall, color = WorkbenchTokens.TextMuted)
                }
            }
        }
    }
}

/**
 * Wraps a message so that a long press opens "Responder" and "Copiar". Used for every message
 * kind except system lines.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupActionBox(
    onReply: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier = modifier.combinedClickable(onClick = {}, onLongClick = { menuOpen = true })) {
        content()
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Responder") },
                onClick = {
                    menuOpen = false
                    onReply()
                },
            )
            DropdownMenuItem(
                text = { Text("Copiar") },
                onClick = {
                    menuOpen = false
                    onCopy()
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Composer: reply bar, mentions, round actions, kind selector and the text pill
// ---------------------------------------------------------------------------------------------

@Composable
private fun GroupComposer(
    busy: Boolean,
    members: List<Bot>,
    replyTo: GroupMessage?,
    replyName: String,
    draft: String,
    kind: GroupMessageKind,
    onDraftChange: (String) -> Unit,
    onKindChange: (GroupMessageKind) -> Unit,
    onCancelReply: () -> Unit,
    onMention: (String) -> Unit,
    handles: Map<String, String> = emptyMap(),
    onSend: () -> Unit,
    onNextRound: () -> Unit,
    onDecide: () -> Unit,
    onStop: () -> Unit,
) {
    val accent = groupKindAccent(kind)
    val sendable = draft.isNotBlank()
    val placeholder = when (kind) {
        GroupMessageKind.SUGGESTION -> "Sugira algo para os prismas…"
        GroupMessageKind.ANNOUNCEMENT -> "Escreva um comunicado para o grupo…"
        GroupMessageKind.CONTEMPLATE -> "Pergunta para o time pensar junto…"
        else -> "Mensagem para o grupo…"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(WorkbenchTokens.Canvas)
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AnimatedVisibility(
            visible = replyTo != null,
            enter = fadeIn(tween(UiMotion.Quick)) + expandVertically(),
            exit = fadeOut(tween(UiMotion.Quick)) + shrinkVertically(),
        ) {
            replyTo?.let { target ->
                GroupReplyBar(name = replyName, text = target.text, onCancel = onCancelReply)
            }
        }

        if (members.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                members.forEach { bot ->
                    val handle = handles[bot.id] ?: bot.name
                    GroupMentionChip(name = handle, accent = Mascots.of(bot.mascot).accent) { onMention(handle) }
                }
            }
        }

        // While the group works, the round actions give way to a stop button.
        AnimatedContent(targetState = busy, label = "groupActions") { working ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (working) {
                    GroupActionChip(label = "■ Parar", color = WorkbenchTokens.Red, onClick = onStop)
                } else {
                    GroupActionChip(label = "Resumo", color = WorkbenchTokens.Gold, icon = R.drawable.ic_doc, onClick = onNextRound)
                    GroupActionChip(label = "Decidir", color = WorkbenchTokens.Green, icon = R.drawable.ic_check_circle, onClick = onDecide)
                }
            }
        }

        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GroupKindButton(kind = kind, onClick = { onKindChange(groupNextKind(kind)) })
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(WorkbenchTokens.Surface)
                    .border(
                        1.dp,
                        if (kind == GroupMessageKind.TEXT) WorkbenchTokens.BorderSoft else accent.copy(alpha = 0.55f),
                        RoundedCornerShape(22.dp),
                    )
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            text = placeholder,
                            color = WorkbenchTokens.TextFaint,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    minLines = 1,
                    maxLines = 5,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        cursorColor = WorkbenchTokens.Text,
                        focusedTextColor = WorkbenchTokens.Text,
                        unfocusedTextColor = WorkbenchTokens.Text,
                    ),
                )
            }
            // The send disc warms up to the kind's colour instead of snapping when text appears.
            val sendFill by animateColorAsState(
                if (sendable) accent else WorkbenchTokens.SurfaceHigh,
                animationSpec = uiQuick(),
                label = "groupSendFill",
            )
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(sendFill)
                    .pressScale(enabled = sendable, onClick = onSend)
                    .semantics { contentDescription = "Enviar" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_send),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun GroupReplyBar(name: String, text: String, onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(WorkbenchTokens.Surface)
            .border(1.dp, WorkbenchTokens.BorderSoft, RoundedCornerShape(14.dp))
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(30.dp)
                .background(WorkbenchTokens.Gold, RoundedCornerShape(50)),
        )
        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
            Text(
                text = "Respondendo a $name",
                style = MaterialTheme.typography.labelMedium,
                color = WorkbenchTokens.Gold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = text.replace('\n', ' '),
                style = MaterialTheme.typography.bodySmall,
                color = WorkbenchTokens.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .pressScale(onClick = onCancel)
                .semantics { contentDescription = "Cancelar resposta" },
            contentAlignment = Alignment.Center,
        ) {
            Text("×", style = MaterialTheme.typography.titleLarge, color = WorkbenchTokens.TextMuted)
        }
    }
}

@Composable
private fun GroupMentionChip(name: String, accent: Color, onClick: () -> Unit) {
    // Touch area at least 48dp; the chip keeps its size in the middle.
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .pressScale(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "@$name",
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(accent.copy(alpha = 0.12f))
                .border(1.dp, accent.copy(alpha = 0.40f), RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = accent,
        )
    }
}

@Composable
private fun GroupActionChip(label: String, color: Color, onClick: () -> Unit, icon: Int? = null) {
    // Touch area at least 48dp; the chip itself keeps its size.
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .pressScale(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(color.copy(alpha = 0.14f))
                .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            icon?.let { Icon(painterResource(it), contentDescription = null, modifier = Modifier.size(16.dp), tint = color) }
            Text(label, style = MaterialTheme.typography.labelLarge, color = color)
        }
    }
}

/** Cycles Mensagem → Sugestão → Aviso → Pensar juntos; the icon and the placeholder say which one is active. */
@Composable
private fun GroupKindButton(kind: GroupMessageKind, onClick: () -> Unit) {
    val (icon, label) = when (kind) {
        GroupMessageKind.SUGGESTION -> R.drawable.ic_lightbulb to "Sugestão"
        GroupMessageKind.ANNOUNCEMENT -> R.drawable.ic_megaphone to "Aviso"
        GroupMessageKind.CONTEMPLATE -> R.drawable.ic_thought to "Pensar juntos"
        else -> R.drawable.ic_chat to "Mensagem"
    }
    val tint by animateColorAsState(groupKindAccent(kind), label = "groupKindTint")
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (kind == GroupMessageKind.TEXT) WorkbenchTokens.Surface else tint.copy(alpha = 0.16f))
            .border(
                1.dp,
                if (kind == GroupMessageKind.TEXT) WorkbenchTokens.BorderSoft else tint.copy(alpha = 0.55f),
                CircleShape,
            )
            .pressScale(onClick = onClick)
            .semantics { contentDescription = "Tipo de mensagem: $label. Toque para trocar." },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (kind == GroupMessageKind.TEXT) WorkbenchTokens.Text else tint,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Sheets and small controls
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupDecisionsSheet(decisions: List<String>, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = WorkbenchTokens.SurfaceRaised,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Decisões",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = WorkbenchTokens.Text,
            )
            if (decisions.isEmpty()) {
                Text(
                    text = "Nenhuma decisão ainda. Use o botão Decidir quando o grupo chegar a um consenso.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = WorkbenchTokens.TextMuted,
                )
            } else {
                decisions.forEachIndexed { index, decision ->
                    if (index > 0) HorizontalDivider(color = WorkbenchTokens.BorderSoft)
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(
                            painterResource(R.drawable.ic_check_circle),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = WorkbenchTokens.Green,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Decisão ${index + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = WorkbenchTokens.TextFaint,
                            )
                            Text(decision, style = MaterialTheme.typography.bodyLarge, color = WorkbenchTokens.Text)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupTag(text: String, color: Color) {
    Text(
        text = text,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(50))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
}

@Composable
private fun GroupSectionLabel(text: String) {
    Text(text = text, style = MaterialTheme.typography.labelLarge, color = WorkbenchTokens.TextMuted)
}

@Composable
private fun GroupField(
    label: String,
    value: String,
    singleLine: Boolean = false,
    minLines: Int = 1,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = singleLine,
        minLines = minLines,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = WorkbenchTokens.Border,
            unfocusedBorderColor = WorkbenchTokens.BorderSoft,
            cursorColor = WorkbenchTokens.Text,
            focusedLabelColor = WorkbenchTokens.TextMuted,
        ),
    )
}

@Composable
private fun GroupPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val fill by animateColorAsState(
        if (selected) WorkbenchTokens.SurfaceHigh else Color.Transparent,
        label = "groupPillFill",
    )
    Text(
        text = text,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(fill)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) WorkbenchTokens.Text else WorkbenchTokens.BorderSoft,
                RoundedCornerShape(50),
            )
            .pressScale(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) WorkbenchTokens.Text else WorkbenchTokens.TextMuted,
    )
}

/** A prisma tile in the member picker; the badge shows its place in the speaking order. */
@Composable
private fun GroupMemberTile(bot: Bot, order: Int, modifier: Modifier, onClick: () -> Unit) {
    val selected = order >= 0
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) WorkbenchTokens.SurfaceHigh else WorkbenchTokens.Surface)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) WorkbenchTokens.Text else WorkbenchTokens.BorderSoft,
                RoundedCornerShape(16.dp),
            )
            .pressScale(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            MascotAvatar(index = bot.mascot, size = 40.dp)
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(WorkbenchTokens.Gold),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${order + 1}", style = MaterialTheme.typography.labelSmall, color = WorkbenchTokens.Canvas)
                }
            }
        }
        Text(
            text = bot.name,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) WorkbenchTokens.Text else WorkbenchTokens.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun GroupStepper(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            color = WorkbenchTokens.Text,
        )
        GroupStepButton(symbol = "−", enabled = value > range.first) { onChange(value - 1) }
        Text(
            text = "$value",
            modifier = Modifier.widthIn(min = 28.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
            color = WorkbenchTokens.Text,
        )
        GroupStepButton(symbol = "+", enabled = value < range.last) { onChange(value + 1) }
    }
}

@Composable
private fun GroupStepButton(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(WorkbenchTokens.Surface)
            .border(1.dp, WorkbenchTokens.BorderSoft, CircleShape)
            .pressScale(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = symbol,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) WorkbenchTokens.Text else WorkbenchTokens.TextFaint,
        )
    }
}
