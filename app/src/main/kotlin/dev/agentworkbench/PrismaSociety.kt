package dev.agentworkbench

import android.content.Context
import java.io.File
import java.util.Calendar
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/** How freely a prisma may reach out on its own; the user decides, per prisma. */
enum class PrismaAutonomy(val label: String, val hint: String) {
    NEVER("Nunca", "Só fala quando você chama ou numa rotina que você criou."),
    ROUTINES("Só nas rotinas", "Te chama apenas nos horários que você agendou."),
    FREE("Livre", "Pode puxar assunto sozinho quando tiver algo útil, dentro dos limites."),
}

/** The general administrator: one fixed prisma that sees, organises and moderates all the others. */
object PrismaAdmin {
    const val ID = "prisma-maestro"

    /** The many-eyed blob: it keeps an eye on everyone. */
    const val MASCOT = 28

    fun default(): Bot = Bot(
        id = ID,
        name = "Maestro",
        label = "admin",
        job = "Administrador geral dos Prismas do usuário. Conhece todos os Prismas, suas rotinas, grupos e " +
            "conexões; cria e ajusta Prismas e rotinas quando o usuário pedir; modera os grupos para que " +
            "a conversa siga a pauta, sem repetição nem bagunça; resume rodadas, mostra o placar de posições " +
            "e registra as decisões; avisa o usuário do que for importante.",
        rules = "Nunca execute ações irreversíveis (enviar mensagens, compras, apagar) sem aprovação explícita. " +
            "Respeite a autonomia e o horário de silêncio de cada Prisma. Seja breve e organizado.",
        mascot = MASCOT,
        autonomy = PrismaAutonomy.ROUTINES,
    )

    fun isAdmin(bot: Bot?): Boolean = bot?.id == ID
}

// ---------------------------------------------------------------------------------------------
// Groups: a WhatsApp-style room where the user and several prismas talk, moderated by Maestro.
// ---------------------------------------------------------------------------------------------

/** What a group is for; shapes the moderator's prompts and the speaking rules. */
enum class GroupMode(val label: String, val hint: String) {
    CHAT("Conversa", "Papo livre: cada Prisma responde quando tem algo a acrescentar."),
    DEBATE("Debate", "Cada Prisma toma posição, rebate os outros e o Maestro resume o placar."),
    BRAINSTORM("Brainstorm", "Ideias rápidas e diferentes; ninguém critica até a votação."),
    REVIEW("Revisão", "Um Prisma propõe, os outros criticam e sugerem melhorias."),
    TASK("Tarefa", "O Maestro divide o trabalho e cada Prisma entrega a sua parte."),
}

/** The position a prisma takes in a message, shown as a coloured chip. */
enum class Stance(val label: String) {
    FAVOR("A favor"),
    AGAINST("Contra"),
    PROPOSAL("Proposta"),
    NEUTRAL("Neutro"),
}

enum class GroupMessageKind {
    /** A normal message from the user or a prisma. */
    TEXT,

    /** A suggestion from the user, highlighted so every prisma takes it into account. */
    SUGGESTION,

    /** A notice ("comunicado") from the user or Maestro; the latest one is pinned on top. */
    ANNOUNCEMENT,

    /** Maestro's end-of-round summary with the stance scoreboard. */
    SUMMARY,

    /** A decision taken by the group; also kept in [PrismaGroup.decisions]. */
    DECISION,

    /** Small centred line: "Maestro abriu a rodada 2", "Nina entrou no grupo"… */
    SYSTEM,

    /** "Pensar juntos" (like Muse's Contemplating): everyone thinks alone in parallel, Maestro merges one answer. */
    CONTEMPLATE,
}

/** Author ids that are not prismas. */
object GroupAuthors {
    const val USER = "user"
    const val SYSTEM = "system"
}

data class GroupMessage(
    val id: String = UUID.randomUUID().toString(),
    /** [GroupAuthors.USER], [GroupAuthors.SYSTEM], [PrismaAdmin.ID] or a member bot id. */
    val authorId: String,
    val kind: GroupMessageKind = GroupMessageKind.TEXT,
    val text: String,
    val stance: Stance? = null,
    /** 0..100, only with a [stance]. */
    val confidence: Int? = null,
    /** The message this one replies to (quoted above the bubble). */
    val replyToId: String? = null,
    /** Still being written by the model. */
    val streaming: Boolean = false,
    val error: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    /** Tools the prisma used for this message ("Leu a agenda", "Pesquisou na web"…). */
    val activity: List<String> = emptyList(),
    /** What the model thought before writing (shown folded, like "Pensou por Xs" in the chat). */
    val reasoning: String = "",
    /** How long it thought, in ms (0 = unknown). */
    val thinkingMillis: Long = 0,
)

/** Status of a task on the group's board. */
enum class TaskStatus(val label: String) {
    TODO("A fazer"),
    DOING("Fazendo"),
    BLOCKED("Travada"),
    REVIEW("Em revisão"),
    DONE("Feita"),
}

/** One row of the group's task board: who does what, and how it is going. */
data class GroupTask(
    val id: String = UUID.randomUUID().toString().take(6),
    val title: String,
    /** Bot id of who is responsible (or [GroupAuthors.USER]); null = nobody yet. */
    val ownerId: String? = null,
    val status: TaskStatus = TaskStatus.TODO,
    /** Last progress note, blocker or result ("servidor no ar em :8765"). */
    val note: String = "",
    /** 0..100 when the owner reports it. */
    val progress: Int? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
    /** What "done" means for this task (acceptance criteria). */
    val criteria: String = "",
    /** A background worker (the owner) is assigned to keep working on it by itself. */
    val autonomous: Boolean = false,
    /** Live log of the worker: tool steps and reports, newest last (capped). */
    val log: List<String> = emptyList(),
    /** When the worker asked to be woken up again (0 = not sleeping). */
    val wakeAtMillis: Long = 0,
    /** Work sessions run so far (each one is a bounded run of tool rounds). */
    val sessions: Int = 0,
    /** Tokens spent by the worker on this task. */
    val tokens: Long = 0,
)

/** A prisma read the group and chose not to answer; why (kept short), for the user to see. */
data class QuietNote(
    val botId: String,
    val reason: String,
    val atMillis: Long = System.currentTimeMillis(),
)

data class PrismaGroup(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** The agenda: what the group is discussing or doing. */
    val topic: String,
    /** Member prisma ids, in speaking order. Maestro moderates and is not listed here. */
    val memberIds: List<String>,
    /** The job the user delegated to each member in this team (bot id -> function). */
    val roles: Map<String, String> = emptyMap(),
    val mode: GroupMode = GroupMode.CHAT,
    /** Rounds after which Maestro must close with a summary and a decision proposal. */
    val maxRounds: Int = 4,
    /** Rounds completed so far. */
    val round: Int = 0,
    /** Prismas answer by themselves after each message of the user. */
    val autoReply: Boolean = true,
    /** At most this many prismas speak per round (keeps cost and noise down). */
    val speakersPerRound: Int = 3,
    /** Tools run without asking, except sensitive ones (messages, calls, purchases, deleting, the main screen). */
    val autoApprove: Boolean = true,
    val messages: List<GroupMessage> = emptyList(),
    val decisions: List<String> = emptyList(),
    /** The pinned notice shown under the header. */
    val pinnedNotice: String? = null,
    val archived: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
    /** Tokens the prismas spent in this group, in total and since the user last wrote. */
    val tokensTotal: Long = 0,
    val tokensSinceUser: Long = 0,
    /** When the user last wrote here (0 = never). */
    val lastUserAtMillis: Long = 0,
    /** The group's running minutes: a summary of the messages older than the recent window. */
    val memory: String = "",
    /** How many finished messages the minutes already cover. */
    val summarizedCount: Int = 0,
    /** The team's task board. */
    val tasks: List<GroupTask> = emptyList(),
    /** Recent "read and stayed quiet" decisions (newest last, capped). */
    val quietLog: List<QuietNote> = emptyList(),
) {
    val lastMessage: GroupMessage? get() = messages.lastOrNull { it.kind != GroupMessageKind.SYSTEM } ?: messages.lastOrNull()
}

/** Groups live as one JSON file each under files/groups (atomic writes), plus a live in-memory copy. */
class GroupRepository(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "groups").apply { mkdirs() }

    @Synchronized
    fun list(): List<PrismaGroup> = (directory.listFiles { file -> file.extension == "json" }.orEmpty())
        .mapNotNull { file -> runCatching { decode(JSONObject(file.readText())) }.getOrNull() }
        .sortedByDescending { it.updatedAtMillis }

    @Synchronized
    fun load(id: String): PrismaGroup? = fileFor(id).takeIf(File::isFile)
        ?.let { runCatching { decode(JSONObject(it.readText())) }.getOrNull() }

    @Synchronized
    fun save(group: PrismaGroup) {
        val target = fileFor(group.id)
        val temporary = File(directory, "${group.id}.tmp")
        temporary.writeText(encode(group).toString())
        check(temporary.renameTo(target) || run { target.delete(); temporary.renameTo(target) }) {
            "Não foi possível salvar o grupo."
        }
    }

    @Synchronized
    fun delete(id: String) {
        fileFor(id).delete()
    }

    private fun fileFor(id: String) = File(directory, "${id.filter { it.isLetterOrDigit() || it == '-' }}.json")

    private fun encode(group: PrismaGroup) = JSONObject()
        .put("id", group.id)
        .put("name", group.name)
        .put("topic", group.topic)
        .put("members", JSONArray(group.memberIds))
        .put("roles", JSONObject(group.roles as Map<*, *>))
        .put("mode", group.mode.name)
        .put("max_rounds", group.maxRounds)
        .put("round", group.round)
        .put("auto_reply", group.autoReply)
        .put("speakers_per_round", group.speakersPerRound)
        .put("auto_approve", group.autoApprove)
        .put("decisions", JSONArray(group.decisions))
        .put("pinned_notice", group.pinnedNotice ?: JSONObject.NULL)
        .put("archived", group.archived)
        .put("created_at", group.createdAtMillis)
        .put("updated_at", group.updatedAtMillis)
        .put("tokens_total", group.tokensTotal)
        .put("tokens_since_user", group.tokensSinceUser)
        .put("last_user_at", group.lastUserAtMillis)
        .put("memory", group.memory)
        .put("summarized_count", group.summarizedCount)
        .put("tasks", JSONArray().apply {
            group.tasks.forEach { task ->
                put(
                    JSONObject().put("id", task.id).put("title", task.title).put("owner", task.ownerId ?: JSONObject.NULL)
                        .put("status", task.status.name).put("note", task.note).put("progress", task.progress ?: JSONObject.NULL)
                        .put("created_at", task.createdAtMillis).put("updated_at", task.updatedAtMillis)
                        .put("criteria", task.criteria).put("autonomous", task.autonomous).put("log", JSONArray(task.log))
                        .put("wake_at", task.wakeAtMillis).put("sessions", task.sessions).put("tokens", task.tokens),
                )
            }
        })
        .put("quiet_log", JSONArray().apply {
            group.quietLog.forEach { put(JSONObject().put("bot", it.botId).put("reason", it.reason).put("at", it.atMillis)) }
        })
        .put(
            "messages",
            JSONArray().apply {
                group.messages.forEach { message ->
                    put(
                        JSONObject()
                            .put("id", message.id)
                            .put("author", message.authorId)
                            .put("kind", message.kind.name)
                            .put("text", message.text)
                            .put("stance", message.stance?.name ?: JSONObject.NULL)
                            .put("confidence", message.confidence ?: JSONObject.NULL)
                            .put("reply_to", message.replyToId ?: JSONObject.NULL)
                            .put("error", message.error ?: JSONObject.NULL)
                            .put("activity", JSONArray(message.activity))
                            .put("reasoning", message.reasoning)
                            .put("thinking_ms", message.thinkingMillis)
                            .put("created_at", message.createdAtMillis),
                    )
                }
            },
        )

    private fun decode(json: JSONObject): PrismaGroup {
        val messages = json.optJSONArray("messages")
        return PrismaGroup(
            id = json.getString("id"),
            name = json.optString("name").ifBlank { "Grupo" },
            topic = json.optString("topic"),
            memberIds = json.optJSONArray("members").strings(),
            roles = json.optJSONObject("roles")?.let { roles -> roles.keys().asSequence().associateWith { roles.optString(it) } }.orEmpty(),
            mode = enumOr(json.optString("mode"), GroupMode.CHAT),
            maxRounds = json.optInt("max_rounds", 4),
            round = json.optInt("round", 0),
            autoReply = json.optBoolean("auto_reply", true),
            speakersPerRound = json.optInt("speakers_per_round", 3),
            autoApprove = json.optBoolean("auto_approve", true),
            decisions = json.optJSONArray("decisions").strings(),
            pinnedNotice = json.nullableString("pinned_notice"),
            archived = json.optBoolean("archived", false),
            createdAtMillis = json.optLong("created_at", System.currentTimeMillis()),
            updatedAtMillis = json.optLong("updated_at", System.currentTimeMillis()),
            tokensTotal = json.optLong("tokens_total", 0),
            tokensSinceUser = json.optLong("tokens_since_user", 0),
            lastUserAtMillis = json.optLong("last_user_at", 0),
            memory = json.optString("memory"),
            summarizedCount = json.optInt("summarized_count", 0),
            tasks = json.optJSONArray("tasks")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val task = array.optJSONObject(index) ?: return@mapNotNull null
                    GroupTask(
                        id = task.optString("id").ifBlank { UUID.randomUUID().toString().take(6) },
                        title = task.optString("title").ifBlank { return@mapNotNull null },
                        ownerId = task.nullableString("owner"),
                        status = enumOr(task.optString("status"), TaskStatus.TODO),
                        note = task.optString("note"),
                        progress = if (task.isNull("progress")) null else task.optInt("progress").coerceIn(0, 100),
                        createdAtMillis = task.optLong("created_at"),
                        updatedAtMillis = task.optLong("updated_at"),
                        criteria = task.optString("criteria"),
                        autonomous = task.optBoolean("autonomous"),
                        log = task.optJSONArray("log").strings(),
                        wakeAtMillis = task.optLong("wake_at"),
                        sessions = task.optInt("sessions"),
                        tokens = task.optLong("tokens"),
                    )
                }
            }.orEmpty(),
            quietLog = json.optJSONArray("quiet_log")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    array.optJSONObject(index)?.let { QuietNote(it.optString("bot"), it.optString("reason"), it.optLong("at")) }
                }
            }.orEmpty(),
            messages = (0 until (messages?.length() ?: 0)).mapNotNull { index ->
                val item = messages?.optJSONObject(index) ?: return@mapNotNull null
                GroupMessage(
                    id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                    authorId = item.optString("author", GroupAuthors.SYSTEM),
                    kind = enumOr(item.optString("kind"), GroupMessageKind.TEXT),
                    text = item.optString("text"),
                    stance = item.nullableString("stance")?.let { enumOr<Stance>(it, Stance.NEUTRAL) },
                    confidence = if (item.isNull("confidence")) null else item.optInt("confidence"),
                    replyToId = item.nullableString("reply_to"),
                    error = item.nullableString("error"),
                    activity = item.optJSONArray("activity").strings(),
                    reasoning = item.optString("reasoning"),
                    thinkingMillis = item.optLong("thinking_ms"),
                    createdAtMillis = item.optLong("created_at", 0L),
                )
            },
        )
    }
}

/** Process-wide live copy of the groups, so screens update while Maestro and the prismas talk. */
object GroupStore {
    private val mutable = MutableStateFlow<List<PrismaGroup>>(emptyList())
    val groups: StateFlow<List<PrismaGroup>> = mutable.asStateFlow()
    private var loaded = false

    fun ensureLoaded(repository: GroupRepository) {
        if (loaded) return
        loaded = true
        mutable.value = repository.list()
    }

    fun get(id: String): PrismaGroup? = mutable.value.firstOrNull { it.id == id }

    /** Applies [change] to group [id] in memory (and persists it when [persist] is true). */
    fun update(repository: GroupRepository, id: String, persist: Boolean = true, change: (PrismaGroup) -> PrismaGroup): PrismaGroup? {
        var result: PrismaGroup? = null
        mutable.update { list ->
            list.map { group ->
                if (group.id == id) change(group).also { result = it } else group
            }.sortedByDescending { it.updatedAtMillis }
        }
        if (persist) result?.let { saved -> runCatching { repository.save(saved.forDisk()) } }
        return result
    }

    /**
     * What goes to disk: plain "digitando…" bubbles are dropped, but a bubble that already used
     * tools is kept as an interrupted message, so work is never lost if the app dies mid-task
     * (when it finishes, the real message replaces it under the same id).
     */
    private fun PrismaGroup.forDisk(): PrismaGroup = copy(
        messages = messages.mapNotNull { message ->
            when {
                !message.streaming -> message
                message.activity.isEmpty() && message.text.isBlank() -> null
                else -> message.copy(
                    streaming = false,
                    text = message.text.ifBlank { "(trabalho em andamento)" },
                    error = "Interrompido: o app fechou antes de terminar. As ferramentas listadas já tinham sido executadas.",
                )
            }
        },
    )

    fun put(repository: GroupRepository, group: PrismaGroup) {
        mutable.update { list -> (list.filterNot { it.id == group.id } + group).sortedByDescending { it.updatedAtMillis } }
        repository.save(group)
    }

    fun remove(repository: GroupRepository, id: String) {
        mutable.update { list -> list.filterNot { it.id == id } }
        repository.delete(id)
    }
}

// ---------------------------------------------------------------------------------------------
// Routines: the prisma's agenda. The user schedules them; the prisma messages first at that time.
// ---------------------------------------------------------------------------------------------

data class PrismaRoutine(
    val id: String = UUID.randomUUID().toString(),
    /** The prisma that runs it (may be [PrismaAdmin.ID]). */
    val botId: String,
    val title: String,
    /** What the prisma must do, e.g. "Me mande um resumo do dia com agenda e clima". */
    val instruction: String,
    /** Days of the week as [Calendar.DAY_OF_WEEK] values (1 = domingo … 7 = sábado). Empty = every day. */
    val days: Set<Int> = emptySet(),
    val hour: Int = 8,
    val minute: Int = 0,
    val enabled: Boolean = true,
    /** When set, the routine runs a round in this group instead of the prisma's own chat. */
    val groupId: String? = null,
    val lastRunAtMillis: Long = 0L,
    val lastResult: String? = null,
) {
    /** The next time this routine fires strictly after [nowMillis], or null if disabled. */
    fun nextRunAfter(nowMillis: Long): Long? {
        if (!enabled) return null
        val calendar = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
        }
        repeat(8) {
            if (calendar.timeInMillis > nowMillis && (days.isEmpty() || calendar.get(Calendar.DAY_OF_WEEK) in days)) {
                return calendar.timeInMillis
            }
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        return null
    }

    fun scheduleLabel(): String {
        val time = "%02d:%02d".format(hour, minute)
        val names = listOf("dom", "seg", "ter", "qua", "qui", "sex", "sáb")
        val dayText = when {
            days.isEmpty() || days.size == 7 -> "todo dia"
            days == setOf(2, 3, 4, 5, 6) -> "seg a sex"
            days == setOf(1, 7) -> "fim de semana"
            else -> days.sorted().joinToString(", ") { names[it - 1] }
        }
        return "$dayText · $time"
    }
}

class RoutineRepository(context: Context) {
    private val file = File(context.applicationContext.filesDir, "routines.json")

    @Synchronized
    fun list(): List<PrismaRoutine> = runCatching {
        if (!file.isFile) return emptyList()
        val array = JSONArray(file.readText())
        (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let { runCatching { decode(it) }.getOrNull() } }
    }.getOrDefault(emptyList())

    fun byId(id: String): PrismaRoutine? = list().firstOrNull { it.id == id }

    @Synchronized
    fun save(routine: PrismaRoutine) = write(list().filterNot { it.id == routine.id } + routine)

    @Synchronized
    fun delete(id: String) = write(list().filterNot { it.id == id })

    private fun write(routines: List<PrismaRoutine>) {
        val json = JSONArray().apply { routines.forEach { put(encode(it)) } }.toString()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(json)
        check(temporary.renameTo(file) || run { file.delete(); temporary.renameTo(file) }) { "Não foi possível salvar as rotinas." }
    }

    private fun encode(routine: PrismaRoutine) = JSONObject()
        .put("id", routine.id)
        .put("bot_id", routine.botId)
        .put("title", routine.title)
        .put("instruction", routine.instruction)
        .put("days", JSONArray(routine.days.sorted()))
        .put("hour", routine.hour)
        .put("minute", routine.minute)
        .put("enabled", routine.enabled)
        .put("group_id", routine.groupId ?: JSONObject.NULL)
        .put("last_run_at", routine.lastRunAtMillis)
        .put("last_result", routine.lastResult ?: JSONObject.NULL)

    private fun decode(json: JSONObject): PrismaRoutine {
        val days = json.optJSONArray("days")
        return PrismaRoutine(
            id = json.getString("id"),
            botId = json.getString("bot_id"),
            title = json.optString("title").ifBlank { "Rotina" },
            instruction = json.optString("instruction"),
            days = (0 until (days?.length() ?: 0)).map { days!!.optInt(it) }.filter { it in 1..7 }.toSet(),
            hour = json.optInt("hour", 8).coerceIn(0, 23),
            minute = json.optInt("minute", 0).coerceIn(0, 59),
            enabled = json.optBoolean("enabled", true),
            groupId = json.nullableString("group_id"),
            lastRunAtMillis = json.optLong("last_run_at", 0L),
            lastResult = json.nullableString("last_result"),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// @-mentions: every participant gets a short, stable handle so prismas can tag easily.
// ---------------------------------------------------------------------------------------------

/**
 * Tagging in groups. Each participant has one short handle: Maestro is always `@maestro`, the
 * user is `@você` (also `@voce`, `@usuário`…), and each prisma gets the first word of its name
 * (`@nina`, `@code`), with a number when two would clash (`@ana2`). A tag also works with the
 * full name (`@Code Assist`). Matching ignores case and accents, and `@nina` never matches
 * inside `@ninaldo`.
 */
object Mentions {
    const val USER_HANDLE = "você"
    private val USER_ALIASES = listOf("você", "usuário", "chefe", "dono")

    fun normalize(value: String): String =
        java.text.Normalizer.normalize(value.lowercase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")

    /** bot id → handle (without "@"), unique among [bots] and never clashing with the user's. */
    fun handles(bots: List<Bot>): Map<String, String> {
        val used = USER_ALIASES.map(::normalize).toMutableSet()
        val result = linkedMapOf<String, String>()
        bots.sortedBy { if (it.id == PrismaAdmin.ID) 0 else 1 }.forEach { bot ->
            val base = if (bot.id == PrismaAdmin.ID) {
                "maestro"
            } else {
                normalize(bot.name).split(Regex("[^a-z0-9]+")).firstOrNull { it.isNotBlank() } ?: "prisma"
            }
            var handle = base
            var n = 2
            while (handle in used) handle = "$base${n++}"
            used += handle
            result[bot.id] = handle
        }
        return result
    }

    /** Ids of the [bots] tagged in [text], by handle or by full name. */
    fun mentioned(text: String, bots: List<Bot>, handles: Map<String, String> = handles(bots)): Set<String> {
        val normalized = normalize(text)
        return bots.filter { bot ->
            handles[bot.id]?.let { containsTag(normalized, it) } == true || containsTag(normalized, normalize(bot.name))
        }.map { it.id }.toSet()
    }

    fun mentionsUser(text: String): Boolean {
        val normalized = normalize(text)
        return USER_ALIASES.any { containsTag(normalized, normalize(it)) }
    }

    /** "@maestro = Maestro (administrador) · @nina = Nina (pesquisa) · @você = o usuário". */
    fun legend(bots: List<Bot>, handles: Map<String, String>, roles: Map<String, String> = emptyMap()): String =
        (bots.mapNotNull { bot ->
            val handle = handles[bot.id] ?: return@mapNotNull null
            val role = if (bot.id == PrismaAdmin.ID) "administrador" else roles[bot.id]?.takeIf(String::isNotBlank)
            "@$handle = ${bot.name}" + (role?.let { " ($it)" } ?: "")
        } + "@$USER_HANDLE = o usuário").joinToString(" · ")

    /** True when "@tag" appears and is not just the start of a longer word. */
    private fun containsTag(normalizedText: String, tag: String): Boolean {
        if (tag.isBlank()) return false
        val token = "@$tag"
        var from = normalizedText.indexOf(token)
        while (from >= 0) {
            val end = from + token.length
            if (end >= normalizedText.length || !normalizedText[end].isLetterOrDigit()) return true
            from = normalizedText.indexOf(token, from + 1)
        }
        return false
    }
}

// --- small JSON helpers shared by the repositories above ---

internal fun JSONArray?.strings(): List<String> =
    (0 until (this?.length() ?: 0)).mapNotNull { index -> this?.optString(index)?.takeIf(String::isNotBlank) }

internal fun JSONObject.nullableString(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() && it != "null" }

internal inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
    runCatching { enumValueOf<T>(name.orEmpty()) }.getOrDefault(fallback)


// ---------------------------------------------------------------------------------------------
// Private messages between prismas (backstage), so they can sort things out without the group.
// ---------------------------------------------------------------------------------------------

data class PrismaDm(
    val id: String = UUID.randomUUID().toString().take(8),
    val fromId: String,
    val toId: String,
    val text: String,
    /** The group it is about, when sent from a group. */
    val groupId: String? = null,
    val read: Boolean = false,
    val atMillis: Long = System.currentTimeMillis(),
)

/** All prisma-to-prisma messages, in one small JSON file plus a live copy for screens and listeners. */
object PrismaDms {
    private const val MAX = 400
    private val mutable = MutableStateFlow<List<PrismaDm>>(emptyList())
    val all: StateFlow<List<PrismaDm>> = mutable.asStateFlow()
    private var loaded = false

    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        mutable.value = runCatching {
            val array = JSONArray(file(context).takeIf(File::isFile)?.readText() ?: "[]")
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let {
                    PrismaDm(it.optString("id"), it.optString("from"), it.optString("to"), it.optString("text"),
                        it.nullableString("group"), it.optBoolean("read"), it.optLong("at"))
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun send(context: Context, dm: PrismaDm) {
        ensureLoaded(context)
        mutable.value = (mutable.value + dm).takeLast(MAX)
        save(context)
    }

    /** Unread messages for [botId]; marks them read. */
    @Synchronized
    fun takeUnread(context: Context, botId: String): List<PrismaDm> {
        ensureLoaded(context)
        val unread = mutable.value.filter { it.toId == botId && !it.read }
        if (unread.isEmpty()) return emptyList()
        val ids = unread.map { it.id }.toSet()
        mutable.value = mutable.value.map { if (it.id in ids) it.copy(read = true) else it }
        save(context)
        return unread
    }

    fun unreadCount(botId: String): Int = mutable.value.count { it.toId == botId && !it.read }

    /** Recent backstage conversation among [memberIds] (for the board screen). */
    fun among(memberIds: Set<String>, limit: Int = 40): List<PrismaDm> =
        mutable.value.filter { it.fromId in memberIds && it.toId in memberIds }.takeLast(limit)

    private fun save(context: Context) {
        val array = JSONArray()
        mutable.value.forEach {
            array.put(JSONObject().put("id", it.id).put("from", it.fromId).put("to", it.toId).put("text", it.text)
                .put("group", it.groupId ?: JSONObject.NULL).put("read", it.read).put("at", it.atMillis))
        }
        runCatching {
            val target = file(context)
            val temporary = File(target.parentFile, target.name + ".tmp")
            temporary.writeText(array.toString())
            if (!temporary.renameTo(target)) { target.delete(); temporary.renameTo(target) }
        }
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, "prisma-dms.json")
}

/** The team notebook of a group: same rules as a prisma's notebook, shared by its members. */
fun teamNotebookId(groupId: String) = "team-$groupId"
