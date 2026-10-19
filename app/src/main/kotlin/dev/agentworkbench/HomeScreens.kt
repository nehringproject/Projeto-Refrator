package dev.agentworkbench

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** One row of the chat list: a bot (with or without a conversation yet) or a plain conversation. */
internal data class ChatListEntry(
    val key: String,
    val title: String,
    val tag: String?,
    val preview: String,
    val updatedAtMillis: Long,
    val pinned: Boolean,
    val bot: Bot?,
    val sessionId: String?,
)

internal fun buildChatList(
    sessions: List<ChatSessionSummary>,
    bots: List<Bot>,
    includeUnopenedBots: Boolean = true,
    preview: (String) -> String?,
): List<ChatListEntry> {
    val botBySession = bots.filter { it.sessionId != null }.associateBy { it.sessionId }
    val fromSessions = sessions.map { session ->
        val bot = botBySession[session.id]
        ChatListEntry(
            key = session.id,
            title = bot?.name ?: session.title,
            tag = bot?.label,
            preview = preview(session.id) ?: bot?.job?.take(90) ?: "Nova conversa",
            updatedAtMillis = session.updatedAtMillis,
            pinned = session.pinned,
            bot = bot,
            sessionId = session.id,
        )
    }
    // Bots never opened yet still appear, so they can be started from the list.
    val unopened = if (!includeUnopenedBots) emptyList() else bots.filter { bot -> bot.sessionId == null || sessions.none { it.id == bot.sessionId } }
        .map { bot ->
            ChatListEntry(
                key = "bot-${bot.id}",
                title = bot.name,
                tag = bot.label,
                preview = bot.job.take(90),
                updatedAtMillis = bot.createdAtMillis,
                pinned = false,
                bot = bot,
                sessionId = null,
            )
        }
    return (fromSessions + unopened).sortedWith(
        compareByDescending<ChatListEntry> { it.pinned }.thenByDescending { it.updatedAtMillis },
    )
}

private fun listTime(millis: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    val yesterday = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR) == 1
    return when {
        sameDay -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
        yesterday -> "Ontem"
        System.currentTimeMillis() - millis < 6L * 24 * 3_600_000 ->
            SimpleDateFormat("EEE", Locale("pt", "BR")).format(Date(millis)).replaceFirstChar { it.uppercase() }.trimEnd('.')
        else -> SimpleDateFormat("dd/MM", Locale.getDefault()).format(Date(millis))
    }
}

/** Bots saved before the rename may still carry "agente" as their label; show it as "prisma". */
private fun prismaLabel(label: String): String = if (label == "agente") "prisma" else label

@Composable
internal fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    actions: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 14.dp, top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = WorkbenchTokens.Text)
            subtitle?.let {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PulseDot(WorkbenchTokens.Green)
                    Text(it, style = MaterialTheme.typography.labelMedium, color = WorkbenchTokens.TextMuted)
                }
            }
        }
        actions()
    }
}

@Composable
internal fun RoundIconButton(icon: Int, description: String, onClick: () -> Unit) {
    // 48dp touch area around a 44dp drawn circle, so the header stays as it looks.
    Box(
        modifier = Modifier
            .padding(start = 8.dp)
            .size(48.dp)
            .pressScale(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(WorkbenchTokens.Surface)
                .border(1.dp, WorkbenchTokens.BorderSoft, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp), tint = WorkbenchTokens.Text)
        }
    }
}

@Composable
internal fun ChatsHome(
    entries: List<ChatListEntry>,
    subtitle: String,
    busySessionId: String?,
    view: Int,
    query: String,
    onViewChange: (Int) -> Unit,
    onQueryChange: (String) -> Unit,
    actionsFor: (ChatListEntry) -> List<Pair<String, () -> Unit>>,
    onOpen: (ChatListEntry) -> Unit,
    onNewChat: () -> Unit,
    cliShortcuts: List<Pair<String, () -> Unit>> = emptyList(),
    /** Prisma groups, mixed with the conversations by last activity (WhatsApp style). */
    groups: List<PrismaGroup> = emptyList(),
    groupRow: @Composable (PrismaGroup) -> Unit = {},
    onNewGroup: (() -> Unit)? = null,
) {
    var searching by remember { mutableStateOf(false) }
    val revealed = remember { mutableSetOf<String>() }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 14.dp, top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MascotAvatar(index = 0, size = 48.dp, active = busySessionId != null, pokeOnTap = true)
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Text("Refrator", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = WorkbenchTokens.Text)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PulseDot(WorkbenchTokens.Green)
                    Text(subtitle, style = MaterialTheme.typography.labelMedium, color = WorkbenchTokens.TextMuted)
                }
            }
            RoundIconButton(R.drawable.ic_search, "Buscar") {
                searching = !searching
                if (!searching) onQueryChange("")
            }
            Box {
                var newMenu by remember { mutableStateOf(false) }
                RoundIconButton(R.drawable.ic_edit, "Nova conversa ou CLI") { newMenu = true }
                DropdownMenu(expanded = newMenu, onDismissRequest = { newMenu = false }) {
                    DropdownMenuItem(text = { Text("Nova conversa") }, onClick = { newMenu = false; onNewChat() })
                    onNewGroup?.let { newGroup ->
                        DropdownMenuItem(text = { Text("Novo grupo de Prismas") }, onClick = { newMenu = false; newGroup() })
                    }
                    cliShortcuts.forEach { (label, action) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { newMenu = false; action() })
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = searching,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                singleLine = true,
                placeholder = { Text("Buscar no texto das conversas", color = WorkbenchTokens.TextFaint) },
                shape = RoundedCornerShape(22.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = WorkbenchTokens.Border,
                    unfocusedBorderColor = WorkbenchTokens.BorderSoft,
                    cursorColor = WorkbenchTokens.Text,
                ),
            )
        }
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("Conversas" to WorkbenchTokens.Blue, "Arquivadas" to WorkbenchTokens.Amber, "Lixeira" to WorkbenchTokens.Pink)
                .forEachIndexed { index, (label, color) ->
                    val active = index == view
                    // Chips ease between states instead of snapping.
                    val chipText by animateColorAsState(if (active) color else WorkbenchTokens.TextMuted, label = "chipText")
                    val chipFill by animateColorAsState(if (active) color.copy(alpha = 0.16f) else Color.Transparent, label = "chipFill")
                    val chipEdge by animateColorAsState(if (active) color.copy(alpha = 0.55f) else WorkbenchTokens.BorderSoft, label = "chipEdge")
                    // The touch area is at least 48dp; the drawn pill keeps its size in the middle.
                    Box(
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .pressScale(onClick = { onViewChange(index) }),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(chipFill)
                                .border(1.dp, chipEdge, RoundedCornerShape(50))
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = chipText,
                        )
                    }
                }
        }
        // A gentle crossfade whenever the tab (Conversas / Arquivadas / Lixeira) changes.
        AnimatedContent(
            targetState = view,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                fadeIn(tween(UiMotion.Standard, easing = FastOutSlowInEasing)) togetherWith fadeOut(tween(UiMotion.Quick))
            },
            label = "chatView",
        ) { current ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Pinned conversations stay on top; groups and the rest are mixed by last activity.
                val shownGroups = if (current == 0) groups else emptyList()
                val merged: List<Any> = entries.filter { it.pinned } +
                    (entries.filterNot { it.pinned } + shownGroups).sortedByDescending { item ->
                        if (item is PrismaGroup) item.updatedAtMillis else (item as ChatListEntry).updatedAtMillis
                    }
                itemsIndexed(merged, key = { _, item -> if (item is PrismaGroup) "group-${item.id}" else (item as ChatListEntry).key }) { index, item ->
                    val itemKey = if (item is PrismaGroup) "group-${item.id}" else (item as ChatListEntry).key
                    // Rows cascade in once; scrolling back does not replay the animation.
                    val appear = remember(itemKey) { Animatable(if (itemKey in revealed) 1f else 0f) }
                    LaunchedEffect(itemKey) {
                        if (appear.value < 1f) {
                            kotlinx.coroutines.delay(minOf(index, 8) * 40L)
                            appear.animateTo(1f, tween(UiMotion.Entrance, easing = FastOutSlowInEasing))
                        }
                        revealed += itemKey
                    }
                    // animateItem: a row that moves (new message, pin, reorder) glides to its place
                    // and a removed one fades out, instead of jumping.
                    val rowModifier = Modifier.animateItem().graphicsLayer {
                        alpha = appear.value
                        translationY = (1f - appear.value) * 18.dp.toPx()
                    }
                    if (item is PrismaGroup) {
                        Box(modifier = rowModifier) { groupRow(item) }
                    } else {
                        val entry = item as ChatListEntry
                        ChatRow(
                            modifier = rowModifier,
                            entry = entry,
                            working = entry.sessionId != null && entry.sessionId == busySessionId,
                            actions = actionsFor(entry),
                        ) { onOpen(entry) }
                    }
                }
                if (merged.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 32.dp, vertical = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            MascotAvatar(index = 4, size = 72.dp)
                            Text(
                                text = when {
                                    query.isNotBlank() -> "Nada encontrado."
                                    current == 1 -> "Nenhuma conversa arquivada."
                                    current == 2 -> "A lixeira está vazia. Itens apagados somem sozinhos depois de um tempo."
                                    else -> "Nenhuma conversa ainda."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = WorkbenchTokens.TextMuted,
                                textAlign = TextAlign.Center,
                            )
                            // Same action as the pencil button in the header, with a label that says what it does.
                            if (query.isBlank() && current == 0) {
                                TextButton(onClick = onNewChat) {
                                    Text("Começar uma conversa", color = WorkbenchTokens.Text, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                }
                item {
                    Text(
                        text = "Toque e segure uma conversa para fixar, arquivar ou apagar.",
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = WorkbenchTokens.TextFaint,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(
    modifier: Modifier = Modifier,
    entry: ChatListEntry,
    working: Boolean,
    actions: List<Pair<String, () -> Unit>>,
    onClick: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val mascotIndex = entry.bot?.mascot ?: Mascots.forKey(entry.key)
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .pressScale(pressedScale = 0.98f)
                .combinedClickable(onClick = onClick, onLongClick = { if (actions.isNotEmpty()) menuOpen = true })
                .drawBehind {
                    // Hairline under the text column, like a messaging app.
                    drawLine(
                        WorkbenchTokens.BorderSoft,
                        androidx.compose.ui.geometry.Offset(92.dp.toPx(), size.height),
                        androidx.compose.ui.geometry.Offset(size.width, size.height),
                        1.dp.toPx(),
                    )
                }
                .padding(horizontal = 12.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Every conversation gets its own little friend; bots show the one they were given.
            MascotAvatar(
                index = mascotIndex,
                size = 60.dp,
                active = working,
                // A reply that just landed gets a little celebration in the list.
                mood = when {
                    working -> MascotMood.WORKING
                    System.currentTimeMillis() - entry.updatedAtMillis < 90_000 -> MascotMood.HAPPY
                    else -> MascotMood.IDLE
                },
            )
            Column(modifier = Modifier.weight(1f).animateContentSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = WorkbenchTokens.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    entry.tag?.let { Tag(prismaLabel(it), Mascots.of(mascotIndex).accent) }
                    if (entry.pinned) {
                        Icon(
                            painterResource(R.drawable.ic_pin),
                            contentDescription = "Fixada",
                            modifier = Modifier.size(14.dp),
                            tint = WorkbenchTokens.TextMuted,
                        )
                    }
                }
                Text(
                    text = if (working) "trabalhando…" else entry.preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = WorkbenchTokens.TextMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(listTime(entry.updatedAtMillis), style = MaterialTheme.typography.labelSmall, color = WorkbenchTokens.TextFaint)
                if (actions.isNotEmpty()) {
                    // Discreet "more" button: opens the same menu as the long press.
                    Box {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .pressScale(onClick = { menuOpen = true })
                                .semantics { contentDescription = "Ações da conversa" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_more_vertical),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = WorkbenchTokens.TextFaint,
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            actions.forEach { (label, action) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = { menuOpen = false; action() })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Tag(text: String, color: Color = WorkbenchTokens.TextMuted) {
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BotsScreen(
    bots: List<Bot>,
    onOpen: (Bot) -> Unit,
    onEdit: (Bot) -> Unit,
    onCreateFrom: (Bot?) -> Unit,
    /** The Sociedade Prisma (plaza + command centre) shown under the title. */
    header: @Composable () -> Unit = {},
) {
    var hintDismissed by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
    ) {
        item {
            ScreenHeader(title = "Sociedade Prisma", subtitle = "${bots.size} ${if (bots.size == 1) "prisma" else "prismas"} vivendo aqui") {
                RoundIconButton(R.drawable.ic_edit, "Criar prisma") { onCreateFrom(null) }
            }
        }
        item { header() }
        item {
            AnimatedVisibility(
                visible = !hintDismissed,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                PrismaHint(onDismiss = { hintDismissed = true })
            }
        }
        if (bots.isNotEmpty()) {
            item { SectionLabel("Seus prismas") }
            itemsIndexed(bots, key = { _, bot -> bot.id }) { index, bot ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateItem()
                        .appearOnce(key = "bot:${bot.id}", index = index)
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(WorkbenchTokens.Surface)
                        .border(1.dp, WorkbenchTokens.BorderSoft, RoundedCornerShape(20.dp))
                        .pressScale(pressedScale = 0.98f)
                        .combinedClickable(onClick = { onOpen(bot) }, onLongClick = { onEdit(bot) })
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    MascotAvatar(index = bot.mascot, size = 54.dp)
                    Column(modifier = Modifier.weight(1f).animateContentSize()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(bot.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = WorkbenchTokens.Text)
                            Tag(prismaLabel(bot.label), Mascots.of(bot.mascot).accent)
                        }
                        Text(bot.job, style = MaterialTheme.typography.bodySmall, color = WorkbenchTokens.TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { onEdit(bot) }) { Text("Editar", color = WorkbenchTokens.TextMuted) }
                }
            }
        }
        item { SectionLabel("Modelos prontos") }
        itemsIndexed(BotTemplates.all, key = { _, template -> template.id }) { index, template ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .appearOnce(key = "tpl:${template.id}", index = index)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .pressScale(pressedScale = 0.98f, onClick = { onCreateFrom(template) })
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                MascotAvatar(index = template.mascot, size = 46.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(template.name, style = MaterialTheme.typography.titleSmall, color = WorkbenchTokens.Text)
                    Text(template.job, style = MaterialTheme.typography.bodySmall, color = WorkbenchTokens.TextFaint, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Icon(
                    painterResource(R.drawable.ic_plus),
                    contentDescription = "Criar prisma a partir de ${template.name}",
                    modifier = Modifier.size(20.dp),
                    tint = WorkbenchTokens.TextMuted,
                )
            }
        }
        item {
            Text(
                text = "Toque e segure um prisma para editar. Em breve: rotinas automáticas e grupos entre prismas.",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                style = MaterialTheme.typography.labelSmall,
                color = WorkbenchTokens.TextFaint,
            )
        }
    }
}

/** One-line explainer at the top of the Prismas tab. Dismissed state survives rotation. */
@Composable
private fun PrismaHint(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(WorkbenchTokens.Surface)
            .border(1.dp, WorkbenchTokens.BorderSoft, RoundedCornerShape(16.dp))
            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Um prisma é um agente com um trabalho fixo, rosto próprio e conversa própria.",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = WorkbenchTokens.TextMuted,
        )
        TextButton(onClick = onDismiss) { Text("Entendi", color = WorkbenchTokens.Text) }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelLarge,
        color = WorkbenchTokens.TextFaint,
    )
}

/** Create or edit a prisma: live face preview, name, job, rules, look and colour. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BotEditorSheet(
    initial: Bot,
    isNew: Boolean,
    onSave: (Bot) -> Unit,
    onDelete: (Bot) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MascotAvatar(index = draft.mascot, size = 76.dp, active = true, pokeOnTap = true)
                Column {
                    Text(if (isNew) "Novo prisma" else "Editar prisma", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("Um trabalho claro por prisma funciona melhor.", style = MaterialTheme.typography.labelMedium, color = WorkbenchTokens.TextMuted)
                }
            }
            EditorField("Nome", draft.name, singleLine = true) { draft = draft.copy(name = it.take(40)) }
            EditorField("Trabalho (objetivo, fontes, ferramentas, estilo)", draft.job, minLines = 3) { draft = draft.copy(job = it.take(2_000)) }
            EditorField("Regras que ele sempre segue (opcional)", draft.rules, minLines = 2) { draft = draft.copy(rules = it.take(1_000)) }
            // Its own brain: any provider the user set up, any model (Sol, Luna, DeepSeek…).
            PrismaModelPicker(
                providerPreset = draft.providerPreset,
                modelId = draft.modelId,
                onChange = { preset, model -> draft = draft.copy(providerPreset = preset, modelId = model) },
            )
            // How freely this prisma may message first (routines, free check-ins, quiet hours).
            AutonomyEditor(
                autonomy = draft.autonomy,
                dailyLimit = draft.dailyLimit,
                quietFrom = draft.quietFrom,
                quietUntil = draft.quietUntil,
                onChange = { autonomy, limit, from, until ->
                    draft = draft.copy(autonomy = autonomy, dailyLimit = limit, quietFrom = from, quietUntil = until)
                },
            )
            // Its own computer: latest commands and the files of its folder (read-only).
            if (!isNew) PrismaComputerSection(bot = initial)
            Text("Mascote", style = MaterialTheme.typography.labelLarge, color = WorkbenchTokens.TextMuted)
            Mascots.all.indices.chunked(6).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { index ->
                        Choice(selected = draft.mascot == index, onClick = { draft = draft.copy(mascot = index) }) {
                            MascotAvatar(index = index, size = 40.dp)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("prisma", "assistente").forEach { label ->
                    Choice(selected = prismaLabel(draft.label) == label, onClick = { draft = draft.copy(label = label) }) {
                        Text(label, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), color = WorkbenchTokens.Text)
                    }
                }
            }
            val canSave = draft.name.isNotBlank() && draft.job.isNotBlank()
            val saveSource = remember { MutableInteractionSource() }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!isNew) {
                    TextButton(onClick = { onDelete(draft) }) { Text("Apagar prisma", color = WorkbenchTokens.Red) }
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancelar", color = WorkbenchTokens.TextMuted) }
                Surface(
                    onClick = { if (canSave) onSave(draft) },
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

@Composable
private fun EditorField(label: String, value: String, singleLine: Boolean = false, minLines: Int = 1, onChange: (String) -> Unit) {
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
private fun Choice(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .border(if (selected) 2.dp else 1.dp, if (selected) WorkbenchTokens.Text else WorkbenchTokens.BorderSoft, RoundedCornerShape(16.dp))
            .pressScale(onClick = onClick)
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Header inside a conversation: back, face, name with live status, menu and tune. */
@Composable
internal fun ChatTopBar(
    title: String,
    bot: Bot?,
    mascot: Int,
    busy: Boolean,
    modelLabel: String,
    menuItems: List<Pair<String, () -> Unit>>,
    onBack: () -> Unit,
    tune: @Composable () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    WorkbenchTokens.BorderSoft,
                    androidx.compose.ui.geometry.Offset(0f, size.height),
                    androidx.compose.ui.geometry.Offset(size.width, size.height),
                    1.dp.toPx(),
                )
            }
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Touch areas are 48dp; the icons keep their drawn size.
        Box(
            modifier = Modifier.size(48.dp).clip(CircleShape).pressScale(onClick = onBack).semantics { contentDescription = "Voltar" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = null, tint = WorkbenchTokens.Text)
        }
        Column(modifier = Modifier.padding(start = 10.dp).weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = WorkbenchTokens.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PulseDot(color = if (busy) InkTokens.Glint else WorkbenchTokens.Green)
                val dots = thinkingDots(busy)
                // "Online" and "pensando…" crossfade instead of snapping.
                AnimatedContent(
                    targetState = busy,
                    label = "chatStatus",
                    transitionSpec = { fadeIn(tween(UiMotion.Quick)) togetherWith fadeOut(tween(UiMotion.Quick)) },
                ) { working ->
                    Text(
                        text = if (working) "pensando" + ".".repeat(dots) else "Online",
                        style = MaterialTheme.typography.labelMedium,
                        color = WorkbenchTokens.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // The model is secondary information: a smaller line under the status, not part of it.
            if (modelLabel.isNotBlank()) {
                Text(
                    text = modelLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = WorkbenchTokens.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box {
            Box(
                modifier = Modifier.size(48.dp).clip(CircleShape).pressScale(onClick = { menuOpen = true }).semantics { contentDescription = "Mais opções" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_more_horizontal), contentDescription = null, modifier = Modifier.size(20.dp), tint = WorkbenchTokens.Text)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                menuItems.forEach { (label, action) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { menuOpen = false; action() })
                }
            }
        }
        tune()
    }
}

/** 1→3 dots cycling while busy, for a calm "pensando…" in the header. */
@Composable
private fun thinkingDots(busy: Boolean): Int {
    var dots by remember { mutableStateOf(1) }
    androidx.compose.runtime.LaunchedEffect(busy) {
        while (busy) {
            kotlinx.coroutines.delay(420)
            dots = dots % 3 + 1
        }
    }
    return dots
}

/** Two-way segmented switch used by Explorar (Terminal | Ferramentas). */
@Composable
internal fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(50))
            .background(WorkbenchTokens.Surface)
            .border(1.dp, WorkbenchTokens.BorderSoft, RoundedCornerShape(50))
            .padding(4.dp),
    ) {
        options.forEachIndexed { index, label ->
            val active = index == selected
            val fill by animateColorAsState(if (active) WorkbenchTokens.SurfaceHigh else Color.Transparent, label = "segFill")
            Text(
                text = label,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(fill)
                    .pressScale(onClick = { onSelect(index) })
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = if (active) WorkbenchTokens.Text else WorkbenchTokens.TextMuted,
            )
        }
    }
}

/** Bottom bar for the top-level screens (hidden inside a conversation). */
@Composable
internal fun HomeBottomBar(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val items = listOf(
        R.drawable.ic_chat to "Chats",
        R.drawable.ic_explore to "Explorar",
        R.drawable.ic_bots to "Prismas",
        R.drawable.ic_person to "Perfil",
    )
    val tabColors = listOf(WorkbenchTokens.Blue, WorkbenchTokens.Teal, WorkbenchTokens.Amber, WorkbenchTokens.Pink)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(WorkbenchTokens.Canvas)
            .drawBehind {
                drawLine(
                    WorkbenchTokens.BorderSoft,
                    androidx.compose.ui.geometry.Offset(0f, 0f),
                    androidx.compose.ui.geometry.Offset(size.width, 0f),
                    1.dp.toPx(),
                )
            }
            .navigationBarsPadding()
            .height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { index, (icon, label) ->
            val active = index == selected
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(22.dp))
                    .pressScale(onClick = { onSelect(index) })
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val tint by androidx.compose.animation.animateColorAsState(
                    if (active) tabColors[index] else WorkbenchTokens.TextFaint,
                    label = "tabTint",
                )
                val labelInk by animateColorAsState(
                    if (active) WorkbenchTokens.Text else WorkbenchTokens.TextFaint,
                    label = "tabLabelInk",
                )
                // The coloured underline grows and shrinks with a soft spring instead of popping.
                val underline by animateDpAsState(
                    targetValue = if (active) 14.dp else 0.dp,
                    animationSpec = uiSpring(),
                    label = "tabUnderline",
                )
                val hop by animateFloatAsState(
                    targetValue = if (active) 1.12f else 1f,
                    animationSpec = spring(dampingRatio = 0.42f, stiffness = 420f),
                    label = "tabHop",
                )
                Icon(
                    painterResource(icon),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp).graphicsLayer {
                        scaleX = hop
                        scaleY = hop
                        translationY = (1f - hop) * 20.dp.toPx()
                    },
                    tint = tint,
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, color = labelInk)
                Box(
                    modifier = Modifier
                        .padding(top = 3.dp)
                        .width(underline)
                        .height(2.dp)
                        .background(tabColors[index], RoundedCornerShape(50)),
                )
            }
        }
    }
}
