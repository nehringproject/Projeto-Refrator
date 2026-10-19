package dev.agentworkbench

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.LocusId
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import androidx.compose.ui.graphics.toArgb
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * WhatsApp-style notifications for the Prismas.
 *
 * - Four channels: private messages, groups, permission requests, notices and routines.
 * - Private chats and groups use [Notification.MessagingStyle]: each Prisma is a [Person] with a
 *   round avatar, the user is "Você", groups get a conversation title. The last few messages of
 *   each conversation stay in memory, so new ones stack instead of replacing the notification.
 * - Inline "Responder" (RemoteInput) and "Marcar como lida" are handled by [PrismaNotificationReceiver].
 * - Each conversation has its own notification id and a long-lived dynamic shortcut, so Android
 *   lists it in the Conversations section. A summary notification appears when several
 *   conversations have unread messages.
 * - Nothing is alerted for the chat or group that is open on screen (see [setVisible]).
 */
internal object PrismaNotifications {

    /** Where a notification lands. Each kind has its own channel, so the user can tune them apart. */
    enum class Kind(val channelId: String, val label: String, val description: String, val importance: Int) {
        PRIVATE(
            "prisma_private",
            "Mensagens privadas",
            "Mensagens dos Prismas no seu chat particular, com resposta direta na notificação.",
            NotificationManager.IMPORTANCE_HIGH,
        ),
        GROUP(
            "prisma_groups",
            "Grupos",
            "Menções e avisos dos grupos da Sociedade Prisma.",
            NotificationManager.IMPORTANCE_HIGH,
        ),
        PERMISSION(
            "prisma_permissions",
            "Pedidos de permissão",
            "Ações que um Prisma quer fazer e precisam da sua aprovação.",
            NotificationManager.IMPORTANCE_HIGH,
        ),
        ROUTINE(
            "prisma_routines",
            "Avisos e rotinas",
            "Resultados de rotinas, avisos do sistema e decisões dos grupos.",
            NotificationManager.IMPORTANCE_DEFAULT,
        ),
    }

    const val EXTRA_KEY = "refrator.conversation_key"
    const val ACTION_OPEN = "dev.agentworkbench.PRISMA_OPEN"
    const val ACTION_REPLY = "dev.agentworkbench.PRISMA_REPLY"
    const val ACTION_MARK_READ = "dev.agentworkbench.PRISMA_MARK_READ"
    const val KEY_REPLY = "prisma_reply_text"

    /** The single channel used before this file existed; it is deleted on the first post. */
    private const val LEGACY_CHANNEL = "prismas"
    private const val GROUP_KEY = "dev.agentworkbench.PRISMA_CONVERSATIONS"
    private const val SUMMARY_ID = 1
    private const val ALERT_BASE = 5_000
    private const val ALERT_SPAN = 10_000
    private const val CONVERSATION_BASE = 100_000
    private const val MAX_ENTRIES = 6
    private const val MAX_TEXT = 500
    private const val CATEGORY_CONVERSATION = "android.shortcut.conversation"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val alertCounter = AtomicInteger(0)
    private val avatars = ConcurrentHashMap<String, Bitmap>()
    private val conversations = LinkedHashMap<String, Conversation>()

    @Volatile private var foreground = false
    @Volatile private var visibleKey: String? = null

    private class Who(val id: String, val name: String, val mascot: Int)

    /** One message in a conversation; [who] is null for the user ("Você"). */
    private class Entry(val who: Who?, val text: String, val at: Long)

    private class Conversation(
        val key: String,
        val isGroup: Boolean,
        var title: String,
        val sessionId: String?,
        val groupId: String?,
        val mascot: Int,
    ) {
        val entries = ArrayList<Entry>()
        var unread = 0
        /** True while its notification is up in the tray. */
        var posted = false
        var lastAt = 0L
        val notificationId = CONVERSATION_BASE + (key.hashCode() and 0x7fffffff) % 1_000_000
        val shortcutId = "conv:$key"

        fun add(entry: Entry) {
            entries += entry
            while (entries.size > MAX_ENTRIES) entries.removeAt(0)
            lastAt = entry.at
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Called by the app (MainActivity and the notifiers)
    // ---------------------------------------------------------------------------------------------

    /** The shell tells us whether the app is in front; alerts for the open chat only apply then. */
    fun setForeground(value: Boolean) {
        foreground = value
    }

    /** The chat or group on screen right now (null when none). Its notification is cleared and not reposted. */
    @Synchronized
    fun setVisible(context: Context, sessionId: String?, groupId: String?) {
        val key = keyOf(sessionId, groupId)
        visibleKey = key
        if (foreground && key != null) markRead(context, key)
    }

    /** The user opened this chat or group: its notification goes away. */
    fun markSeen(context: Context, sessionId: String? = null, groupId: String? = null) {
        keyOf(sessionId, groupId)?.let { markRead(context, it) }
    }

    /** "Marcar como lida" from the notification, or the chat was opened. */
    @Synchronized
    fun markRead(context: Context, key: String) {
        val conversation = conversations[key] ?: return
        conversation.unread = 0
        val manager = notificationManager(context) ?: return
        if (conversation.posted) {
            conversation.posted = false
            manager.cancel(conversation.notificationId)
        }
        updateSummary(context, manager)
    }

    /** A Prisma wrote to the user's private chat [sessionId]. */
    @Synchronized
    fun privateMessage(context: Context, bot: Bot, text: String, sessionId: String?) {
        val chat = sessionId ?: bot.sessionId ?: return
        val clean = text.trim()
        if (clean.isEmpty()) return
        val conversation = privateChat(chat, bot)
        conversation.add(Entry(Who(bot.id, bot.name, bot.mascot), clean.take(MAX_TEXT), System.currentTimeMillis()))
        conversation.unread++
        post(context, conversation, quiet = false)
    }

    /** A prisma tagged the user (or raised something for them) in group [groupId]. */
    @Synchronized
    fun groupMention(context: Context, groupId: String, speaker: Bot, text: String) {
        val group = GroupStore.get(groupId) ?: return
        val conversation = groupChat(groupId, group)
        rebuildGroup(context, conversation, group)
        val clean = text.trim()
        // The message is already in the group; this only covers a store that does not show it yet.
        if (clean.isNotEmpty() && conversation.entries.none { it.text.contains(clean.take(60)) }) {
            conversation.add(Entry(Who(speaker.id, speaker.name, speaker.mascot), clean.take(MAX_TEXT), System.currentTimeMillis()))
        }
        conversation.unread++
        post(context, conversation, quiet = false)
    }

    /**
     * A plain notice (routine result, permission request, decision, team stalled). [groupId] or
     * [sessionId] make a tap open that place.
     */
    fun alert(
        context: Context,
        kind: Kind,
        title: String,
        text: String,
        sessionId: String? = null,
        groupId: String? = null,
    ) {
        val manager = notificationManager(context) ?: return
        if (!canNotify(context, manager)) return
        if (groupId != null && foreground && visibleKey == "group:$groupId") return
        ensureChannels(manager)
        val id = ALERT_BASE + Math.floorMod(alertCounter.getAndIncrement(), ALERT_SPAN)
        val notification = Notification.Builder(context, kind.channelId)
            .setSmallIcon(R.drawable.ic_refrator_mark)
            .setContentTitle(title)
            .setContentText(text.take(120))
            .setStyle(Notification.BigTextStyle().bigText(text.take(900)))
            .setContentIntent(openPending(context, id, sessionId, groupId))
            .setAutoCancel(true)
            .setCategory(if (kind == Kind.ROUTINE) Notification.CATEGORY_REMINDER else Notification.CATEGORY_MESSAGE)
            .build()
        runCatching { manager.notify(id, notification) }
    }

    /**
     * Inline reply from the notification: sends [text] as the user into the group, or into the
     * prisma's private chat (which runs a turn and then posts the answer). [onDone] runs at the end.
     */
    fun reply(context: Context, key: String, text: String, onDone: () -> Unit) {
        val app = context.applicationContext
        scope.launch {
            var finished = false
            fun done() {
                if (!finished) {
                    finished = true
                    onDone()
                }
            }
            try {
                when {
                    key.startsWith("group:") -> replyToGroup(app, key.removePrefix("group:"), text)
                    // The receiver must finish in seconds: release it once the line is shown, then let the turn run.
                    key.startsWith("dm:") -> replyToPrivate(app, key.removePrefix("dm:"), text, onShown = ::done)
                }
            } catch (error: Exception) {
                // The reply did not go through; the notification keeps showing the last state.
            } finally {
                done()
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Replies
    // ---------------------------------------------------------------------------------------------

    private suspend fun replyToGroup(context: Context, groupId: String, text: String) {
        GroupStore.ensureLoaded(GroupRepository(context))
        if (GroupStore.get(groupId) == null) return
        withContext(Dispatchers.Main.immediate) {
            GroupEngine.send(context, groupId, text, GroupMessageKind.TEXT, null)
        }
        synchronized(this) {
            val group = GroupStore.get(groupId) ?: return
            val conversation = groupChat(groupId, group)
            rebuildGroup(context, conversation, group)
            conversation.unread = 0
            post(context, conversation, quiet = true)
        }
    }

    private suspend fun replyToPrivate(context: Context, sessionId: String, text: String, onShown: () -> Unit) {
        val bot = BotRepository(context).bySession(sessionId) ?: return
        synchronized(this) {
            val conversation = privateChat(sessionId, bot)
            conversation.add(Entry(null, text.take(MAX_TEXT), System.currentTimeMillis()))
            conversation.unread = 0
            post(context, conversation, quiet = true)
        }
        onShown()
        // Keep the process alive (foreground service) while the prisma answers.
        TeamKeepAliveService.ensure(context)
        // Same path as typing in the chat: the prisma answers with its tools and the answer lands here.
        val answer = PrismaDispatcher.runTurn(context, bot, text)?.trim()
        if (!answer.isNullOrBlank()) privateMessage(context, bot, answer, sessionId)
    }

    // ---------------------------------------------------------------------------------------------
    // Conversations
    // ---------------------------------------------------------------------------------------------

    private fun keyOf(sessionId: String?, groupId: String?): String? = when {
        groupId != null -> "group:$groupId"
        sessionId != null -> "dm:$sessionId"
        else -> null
    }

    private fun privateChat(sessionId: String, bot: Bot): Conversation {
        val key = "dm:$sessionId"
        return conversations.getOrPut(key) { Conversation(key, false, bot.name, sessionId, null, bot.mascot) }
            .also { it.title = bot.name }
    }

    private fun groupChat(groupId: String, group: PrismaGroup): Conversation {
        val key = "group:$groupId"
        return conversations.getOrPut(key) { Conversation(key, true, group.name, null, groupId, PrismaAdmin.MASCOT) }
    }

    /** Rebuilds the last messages of a group from the group store (the source of truth). */
    private fun rebuildGroup(context: Context, conversation: Conversation, group: PrismaGroup) {
        val bots = BotRepository(context).list().associateBy { it.id }
        conversation.title = group.name
        conversation.entries.clear()
        group.messages
            .filter { it.authorId != GroupAuthors.SYSTEM && it.kind != GroupMessageKind.SYSTEM && !it.streaming && it.text.isNotBlank() }
            .takeLast(MAX_ENTRIES)
            .forEach { message ->
                val who = if (message.authorId == GroupAuthors.USER) {
                    null
                } else {
                    bots[message.authorId]?.let { Who(it.id, it.name, it.mascot) } ?: Who(message.authorId, "Prisma", 0)
                }
                conversation.add(Entry(who, message.text.take(MAX_TEXT), message.createdAtMillis))
            }
    }

    // ---------------------------------------------------------------------------------------------
    // Posting
    // ---------------------------------------------------------------------------------------------

    /** Shows or updates the notification of [conversation]. [quiet] updates it without a sound. */
    @Synchronized
    private fun post(context: Context, conversation: Conversation, quiet: Boolean) {
        val manager = notificationManager(context) ?: return
        // The user is looking at this chat: the message is in it already, so no alert.
        if (foreground && visibleKey == conversation.key) {
            conversation.unread = 0
            return
        }
        if (!canNotify(context, manager)) return
        ensureChannels(manager)
        pushShortcut(context, conversation)
        val notification = buildConversation(context, conversation, quiet)
        if (runCatching { manager.notify(conversation.notificationId, notification) }.isSuccess) {
            conversation.posted = true
        }
        updateSummary(context, manager)
    }

    private fun buildConversation(context: Context, conversation: Conversation, quiet: Boolean): Notification {
        val style = Notification.MessagingStyle(userPerson()).setGroupConversation(conversation.isGroup)
        if (conversation.isGroup) style.setConversationTitle(conversation.title)
        conversation.entries.forEach { entry ->
            style.addMessage(Notification.MessagingStyle.Message(entry.text, entry.at, entry.who?.let { person(it) }))
        }
        val channel = if (conversation.isGroup) Kind.GROUP else Kind.PRIVATE
        val builder = Notification.Builder(context, channel.channelId)
            .setSmallIcon(R.drawable.ic_refrator_mark)
            .setLargeIcon(avatar(conversation.title, conversation.mascot))
            .setStyle(style)
            .setColor(Mascots.of(conversation.mascot).accent.toArgb())
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(openPending(context, conversation.notificationId, conversation.sessionId, conversation.groupId))
            .setAutoCancel(true)
            .setWhen(conversation.lastAt)
            .setShowWhen(true)
            .setGroup(GROUP_KEY)
            .setShortcutId(conversation.shortcutId)
            .setOnlyAlertOnce(quiet)
            .addAction(replyAction(context, conversation))
            .addAction(markReadAction(context, conversation))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setLocusId(LocusId(conversation.key))
        return builder.build()
    }

    private fun replyAction(context: Context, conversation: Conversation): Notification.Action {
        val pending = actionPending(context, ACTION_REPLY, conversation.key, conversation.notificationId * 4 + 1)
        val input = RemoteInput.Builder(KEY_REPLY).setLabel("Responder").build()
        return Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_refrator_mark),
            "Responder",
            pending,
        )
            .addRemoteInput(input)
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
            .build()
    }

    private fun markReadAction(context: Context, conversation: Conversation): Notification.Action {
        val pending = actionPending(context, ACTION_MARK_READ, conversation.key, conversation.notificationId * 4 + 2)
        return Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_refrator_mark),
            "Marcar como lida",
            pending,
        )
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .build()
    }

    /** One summary for the app when several conversations have unread messages ("3 conversas"). */
    private fun updateSummary(context: Context, manager: NotificationManager) {
        val unread = conversations.values.filter { it.posted && it.unread > 0 }
        if (unread.size < 2) {
            manager.cancel(SUMMARY_ID)
            return
        }
        val inbox = Notification.InboxStyle()
            .setBigContentTitle("${unread.size} conversas")
            .setSummaryText("Sociedade Prisma")
        unread.take(5).forEach { conversation ->
            val last = conversation.entries.lastOrNull()
            val who = last?.who?.name ?: "Você"
            inbox.addLine("${conversation.title} · $who: ${last?.text.orEmpty().take(60)}")
        }
        val notification = Notification.Builder(context, Kind.PRIVATE.channelId)
            .setSmallIcon(R.drawable.ic_refrator_mark)
            .setContentTitle("${unread.size} conversas")
            .setContentText(unread.joinToString(", ") { it.title }.take(120))
            .setStyle(inbox)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(openPending(context, SUMMARY_ID, null, null))
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(SUMMARY_ID, notification) }
    }

    /** Long-lived dynamic shortcut, so the conversation shows in Android's Conversations section. */
    private fun pushShortcut(context: Context, conversation: Conversation) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val shortcuts = context.getSystemService(ShortcutManager::class.java) ?: return
        val icon = Icon.createWithBitmap(avatar(conversation.title, conversation.mascot))
        val person = Person.Builder().setName(conversation.title).setKey(conversation.key).setIcon(icon).build()
        val info = ShortcutInfo.Builder(context, conversation.shortcutId)
            .setShortLabel(conversation.title.take(40))
            .setLongLabel(conversation.title.take(80))
            .setIcon(icon)
            .setIntent(openIntent(context, conversation.sessionId, conversation.groupId))
            .setCategories(setOf(CATEGORY_CONVERSATION))
            .setLongLived(true)
            .setLocusId(LocusId(conversation.key))
            .setPersons(arrayOf(person))
            .build()
        runCatching { shortcuts.pushDynamicShortcut(info) }
    }

    @Synchronized
    private fun ensureChannels(manager: NotificationManager) {
        Kind.values().forEach { kind ->
            manager.createNotificationChannel(
                NotificationChannel(kind.channelId, kind.label, kind.importance).apply {
                    description = kind.description
                },
            )
        }
        runCatching { manager.deleteNotificationChannel(LEGACY_CHANNEL) }
    }

    private fun canNotify(context: Context, manager: NotificationManager): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return manager.areNotificationsEnabled()
    }

    private fun notificationManager(context: Context): NotificationManager? =
        context.getSystemService(NotificationManager::class.java)

    // ---------------------------------------------------------------------------------------------
    // Intents
    // ---------------------------------------------------------------------------------------------

    private fun openIntent(context: Context, sessionId: String?, groupId: String?): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            sessionId?.let { putExtra(PendingNavigation.EXTRA_SESSION, it) }
            groupId?.let { putExtra(PendingNavigation.EXTRA_GROUP, it) }
        }

    private fun openPending(context: Context, requestCode: Int, sessionId: String?, groupId: String?): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            openIntent(context, sessionId, groupId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Inline reply needs a mutable PendingIntent, so the system can fill in the typed text. */
    private fun actionPending(context: Context, action: String, key: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, PrismaNotificationReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_KEY, key)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Avatars: a filled circle in the mascot's accent colour with the first letter, cached
    // ---------------------------------------------------------------------------------------------

    private fun userPerson(): Person = Person.Builder().setName("Você").setKey("user").setBot(false).build()

    private fun person(who: Who): Person = Person.Builder()
        .setName(who.name)
        .setKey(who.id)
        .setIcon(Icon.createWithBitmap(avatar(who.name, who.mascot)))
        .setImportant(true)
        .build()

    private fun avatar(name: String, mascot: Int): Bitmap {
        val letter = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        val accent = Mascots.of(mascot).accent
        val argb = accent.toArgb()
        val luminance = 0.299f * accent.red + 0.587f * accent.green + 0.114f * accent.blue
        val textColor = if (luminance > 0.6f) AndroidColor.parseColor("#1B1B1F") else AndroidColor.WHITE
        return avatars.getOrPut("$argb|$letter|$textColor") {
            val size = 128
            val half = size / 2f
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = argb
            canvas.drawCircle(half, half, half, paint)
            paint.color = textColor
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textSize = size * 0.5f
            val baseline = half - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(letter, half, baseline, paint)
            bitmap
        }
    }
}

/** Receives the inline reply and "Marcar como lida" taps. Not exported: only our notifications send to it. */
class PrismaNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(PrismaNotifications.EXTRA_KEY) ?: return
        when (intent.action) {
            PrismaNotifications.ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(PrismaNotifications.KEY_REPLY)
                    ?.toString()
                    ?.trim()
                    .orEmpty()
                if (text.isEmpty()) return
                val pending = goAsync()
                PrismaNotifications.reply(context, key, text) { pending.finish() }
            }
            PrismaNotifications.ACTION_MARK_READ -> PrismaNotifications.markRead(context, key)
        }
    }
}
