package dev.agentworkbench

import android.content.Context
import dev.agentworkbench.core.ChatMessage
import dev.agentworkbench.core.ChatMessageStatus
import dev.agentworkbench.core.ChatRole
import java.io.File
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/*
 * Memória da Sociedade Prisma
 * ===========================
 *
 * Premissas do caderno (valem para o código e para o que os Prismas leem no prompt):
 *
 *  1. Dono. Cada caderno pertence a um Prisma. Só ele (pelas ferramentas) e o usuário (pela tela
 *     "Memória") leem e editam. Outros Prismas nunca leem o caderno de ninguém.
 *  2. Conteúdo. O caderno guarda conhecimento durável, não conversa: compromissos, fatos sobre o
 *     usuário, preferências, aprendizados do trabalho e contexto de projetos.
 *  3. Segurança. Segredos (senhas, chaves, tokens, códigos de verificação, cartões) são recusados
 *     antes de gravar, pela mesma política do resto do app ([SensitiveDataPolicy]).
 *  4. Estrutura. Toda anotação tem tipo, lugar, origem e datas; compromissos têm status e, se
 *     quiser, prazo.
 *  5. Lugar. "geral" vale em todo lugar; "privado" veio da conversa privada; "grupo:<id>" vale
 *     naquele grupo.
 *  6. Discrição. O Prisma lembra do privado em qualquer lugar (resumo + anotações do privado),
 *     mas em grupos isso é confidencial: usa para ajudar e não expõe detalhes sem o usuário pedir.
 *  7. Sem duplicatas. Anotar algo quase igual a uma anotação existente do mesmo tipo e lugar
 *     atualiza a existente em vez de criar outra.
 *  8. Orçamento. No prompt entra só o mais importante, dentro de um limite de caracteres:
 *     compromissos atrasados e abertos, fixadas, preferências e fatos, depois o resto. O resto
 *     fica disponível por notebook_read.
 *  9. Manutenção. Compromissos feitos saem do prompt e são apagados depois de 30 dias; o caderno
 *     tem no máximo 300 anotações (as menos importantes saem primeiro, fixadas nunca).
 * 10. Resumos automáticos (resumo do privado, ata do grupo) são escritos pelo app a partir das
 *     conversas e ficam separados das anotações: o Prisma não os edita; o usuário vê e apaga.
 *
 * O núcleo ([NotebookLogic]) é puro (sem Android) para ser testado na JVM; [PrismaNotebook] só
 * cuida de arquivo e concorrência.
 */

enum class NoteKind(val label: String, val prompt: String) {
    TASK("Compromisso", "compromisso"),
    FACT("Fato", "fato"),
    PREFERENCE("Preferência", "preferência"),
    LEARNING("Aprendizado", "aprendizado"),
    CONTEXT("Contexto", "contexto"),
}

enum class NoteStatus { OPEN, DONE }

data class Note(
    val id: String,
    val kind: NoteKind,
    val text: String,
    /** [NotebookLogic.PLACE_GENERAL], [NotebookLogic.PLACE_PRIVATE] or [NotebookLogic.groupPlace]. */
    val place: String,
    /** Where it was written: "privado", "grupo:<id>", "rotina" or "usuário" (edited on screen). */
    val source: String,
    val status: NoteStatus = NoteStatus.OPEN,
    val dueAtMillis: Long? = null,
    val pinned: Boolean = false,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

/** The automatic, app-written summary of a prisma's private chat with the user. */
data class PrivateSummary(
    val text: String,
    /** How many finished messages of the private chat it covers. */
    val coveredMessages: Int,
    val updatedAtMillis: Long,
)

data class NotebookState(
    val notes: List<Note> = emptyList(),
    val privateSummary: PrivateSummary? = null,
)

/** A new note before it gets an id and dates. */
data class NoteDraft(
    val kind: NoteKind,
    val text: String,
    val place: String,
    val source: String,
    val dueAtMillis: Long? = null,
    val pinned: Boolean = false,
)

sealed interface NoteWrite {
    data class Added(val note: Note) : NoteWrite
    /** A near-duplicate existed: it was refreshed instead of adding another. */
    data class Merged(val note: Note) : NoteWrite
    data class Rejected(val reason: String) : NoteWrite
}

object NotebookLogic {
    const val SCHEMA_VERSION = 2
    const val PLACE_GENERAL = "geral"
    const val PLACE_PRIVATE = "privado"
    const val MAX_TEXT = 500
    const val MAX_NOTES = 300
    const val DONE_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000
    const val DUPLICATE_SIMILARITY = 0.8
    const val CONTEXT_CHARS = 2_500

    fun groupPlace(groupId: String) = "grupo:$groupId"

    fun groupIdOf(place: String): String? = place.removePrefix("grupo:").takeIf { place.startsWith("grupo:") && it.isNotBlank() }

    /** Null when the text may be stored, else the reason it may not. */
    fun validate(text: String, containsSecret: (String) -> Boolean): String? = when {
        text.isBlank() -> "A anotação está vazia."
        text.length > MAX_TEXT -> "Anotação longa demais (máximo $MAX_TEXT caracteres); resuma."
        containsSecret(text) -> "Isso parece um segredo (senha, chave, token, código ou cartão) e não vai para o caderno."
        else -> null
    }

    /** Word-set similarity (0..1), case- and accent-insensitive, ignoring very short words. */
    fun similarity(a: String, b: String): Double {
        val left = words(a)
        val right = words(b)
        if (left.isEmpty() || right.isEmpty()) return if (normalize(a) == normalize(b)) 1.0 else 0.0
        val common = left.intersect(right).size.toDouble()
        return common / (left.size + right.size - common)
    }

    fun add(
        state: NotebookState,
        draft: NoteDraft,
        now: Long,
        containsSecret: (String) -> Boolean,
        newId: () -> String = { UUID.randomUUID().toString().take(8) },
    ): Pair<NotebookState, NoteWrite> {
        val text = draft.text.trim().replace(Regex("\\s+"), " ")
        validate(text, containsSecret)?.let { return state to NoteWrite.Rejected(it) }
        val twin = state.notes.firstOrNull {
            it.kind == draft.kind && it.place == draft.place && it.status == NoteStatus.OPEN &&
                similarity(it.text, text) >= DUPLICATE_SIMILARITY
        }
        if (twin != null) {
            val merged = twin.copy(
                text = if (text.length >= twin.text.length) text else twin.text,
                dueAtMillis = draft.dueAtMillis ?: twin.dueAtMillis,
                pinned = twin.pinned || draft.pinned,
                updatedAtMillis = now,
            )
            return prune(state.copy(notes = state.notes.map { if (it.id == twin.id) merged else it }), now) to NoteWrite.Merged(merged)
        }
        val note = Note(
            id = uniqueId(state, newId),
            kind = draft.kind,
            text = text,
            place = draft.place,
            source = draft.source,
            dueAtMillis = draft.dueAtMillis,
            pinned = draft.pinned,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        return prune(state.copy(notes = state.notes + note), now) to NoteWrite.Added(note)
    }

    fun update(state: NotebookState, id: String, now: Long, change: (Note) -> Note): NotebookState? {
        if (state.notes.none { it.id == id }) return null
        return state.copy(notes = state.notes.map { if (it.id == id) change(it).copy(id = it.id, updatedAtMillis = now) else it })
    }

    fun delete(state: NotebookState, id: String): NotebookState? =
        if (state.notes.none { it.id == id }) null else state.copy(notes = state.notes.filterNot { it.id == id })

    /** Drops old finished tasks and keeps the notebook within [MAX_NOTES] (pinned notes stay). */
    fun prune(state: NotebookState, now: Long): NotebookState {
        val fresh = state.notes.filterNot { it.status == NoteStatus.DONE && !it.pinned && now - it.updatedAtMillis > DONE_RETENTION_MILLIS }
        if (fresh.size <= MAX_NOTES) return state.copy(notes = fresh)
        val removable = fresh.filterNot { it.pinned }
            .sortedWith(compareBy<Note> { if (it.status == NoteStatus.DONE) 0 else 1 }.thenBy { importance(it) }.thenBy { it.updatedAtMillis })
        val drop = removable.take(fresh.size - MAX_NOTES).map { it.id }.toSet()
        return state.copy(notes = fresh.filterNot { it.id in drop })
    }

    /**
     * The notes worth putting in a prompt for [places], most important first, within [budget]
     * characters: overdue and open tasks (soonest due first), pinned, preferences, facts,
     * learnings, context. Finished tasks never enter the prompt.
     */
    fun forContext(state: NotebookState, places: Set<String>, now: Long, budget: Int = CONTEXT_CHARS): List<Note> {
        val candidates = state.notes.filter { it.place in places && it.status == NoteStatus.OPEN }
            .sortedWith(
                compareByDescending<Note> { it.kind == NoteKind.TASK && it.dueAtMillis != null && it.dueAtMillis < now }
                    .thenByDescending { it.pinned }
                    .thenByDescending { importance(it) }
                    .thenBy { it.dueAtMillis ?: Long.MAX_VALUE }
                    .thenByDescending { it.updatedAtMillis },
            )
        val chosen = mutableListOf<Note>()
        var used = 0
        for (note in candidates) {
            val cost = note.text.length + 40
            if (used + cost > budget) continue
            chosen += note
            used += cost
        }
        return chosen
    }

    /** Prompt lines: "- [id] compromisso (prazo 18/10) {grupo Lançamento} texto". */
    fun render(notes: List<Note>, groupNames: Map<String, String> = emptyMap(), now: Long = System.currentTimeMillis()): String =
        notes.joinToString("\n") { note ->
            buildString {
                append("- [").append(note.id).append("] ").append(note.kind.prompt)
                if (note.status == NoteStatus.DONE) append(" (feito)")
                note.dueAtMillis?.let { due ->
                    append(if (note.status == NoteStatus.OPEN && due < now) " (ATRASADO, prazo " else " (prazo ")
                    append(java.text.SimpleDateFormat("dd/MM HH:mm", Locale.ROOT).format(java.util.Date(due))).append(')')
                }
                if (note.pinned) append(" 📌")
                groupIdOf(note.place)?.let { append(" {grupo ").append(groupNames[it] ?: "?").append('}') }
                if (note.place == PLACE_PRIVATE) append(" {privado}")
                append(' ').append(note.text)
            }
        }

    fun encode(state: NotebookState): JSONObject = JSONObject()
        .put("version", SCHEMA_VERSION)
        .put("notes", JSONArray().apply {
            state.notes.forEach { note ->
                put(
                    JSONObject()
                        .put("id", note.id)
                        .put("kind", note.kind.name)
                        .put("text", note.text)
                        .put("place", note.place)
                        .put("source", note.source)
                        .put("status", note.status.name)
                        .put("due_at", note.dueAtMillis ?: JSONObject.NULL)
                        .put("pinned", note.pinned)
                        .put("created_at", note.createdAtMillis)
                        .put("updated_at", note.updatedAtMillis),
                )
            }
        })
        .put("private_summary", state.privateSummary?.let {
            JSONObject().put("text", it.text).put("covered", it.coveredMessages).put("updated_at", it.updatedAtMillis)
        } ?: JSONObject.NULL)

    /** Tolerant decode; also reads the v1 format (a bare array of {id,text,place,done}). */
    fun decode(raw: String): NotebookState {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return NotebookState()
        if (trimmed.startsWith("[")) {
            val array = JSONArray(trimmed)
            return NotebookState(
                notes = (0 until array.length()).mapNotNull { index ->
                    val json = array.optJSONObject(index) ?: return@mapNotNull null
                    val created = json.optLong("created_at", 0L)
                    Note(
                        id = json.optString("id").ifBlank { UUID.randomUUID().toString().take(8) },
                        kind = NoteKind.CONTEXT,
                        text = json.optString("text"),
                        place = json.optString("place", PLACE_GENERAL),
                        source = json.optString("place", PLACE_GENERAL),
                        status = if (json.optBoolean("done")) NoteStatus.DONE else NoteStatus.OPEN,
                        createdAtMillis = created,
                        updatedAtMillis = created,
                    )
                }.filter { it.text.isNotBlank() },
            )
        }
        val root = JSONObject(trimmed)
        val notes = root.optJSONArray("notes")
        return NotebookState(
            notes = (0 until (notes?.length() ?: 0)).mapNotNull { index ->
                val json = notes?.optJSONObject(index) ?: return@mapNotNull null
                runCatching {
                    Note(
                        id = json.getString("id"),
                        kind = runCatching { NoteKind.valueOf(json.optString("kind")) }.getOrDefault(NoteKind.CONTEXT),
                        text = json.getString("text"),
                        place = json.optString("place", PLACE_GENERAL),
                        source = json.optString("source", PLACE_GENERAL),
                        status = runCatching { NoteStatus.valueOf(json.optString("status")) }.getOrDefault(NoteStatus.OPEN),
                        dueAtMillis = if (json.isNull("due_at")) null else json.optLong("due_at"),
                        pinned = json.optBoolean("pinned"),
                        createdAtMillis = json.optLong("created_at"),
                        updatedAtMillis = json.optLong("updated_at"),
                    )
                }.getOrNull()
            },
            privateSummary = root.optJSONObject("private_summary")?.let {
                PrivateSummary(it.optString("text"), it.optInt("covered"), it.optLong("updated_at"))
            }?.takeIf { it.text.isNotBlank() },
        )
    }

    private fun importance(note: Note): Int = when (note.kind) {
        NoteKind.TASK -> 5
        NoteKind.PREFERENCE -> 4
        NoteKind.FACT -> 3
        NoteKind.LEARNING -> 2
        NoteKind.CONTEXT -> 1
    }

    private fun uniqueId(state: NotebookState, newId: () -> String): String {
        val taken = state.notes.map { it.id }.toSet()
        repeat(20) { newId().let { if (it !in taken) return it } }
        return UUID.randomUUID().toString()
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    private fun words(value: String): Set<String> =
        normalize(value).split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }.toSet()
}

/** File + concurrency around [NotebookLogic]: one JSON file per prisma, written atomically. */
class PrismaNotebook(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "notebooks").apply { mkdirs() }

    fun state(botId: String): NotebookState = synchronized(LOCK) { read(botId) }

    fun add(botId: String, draft: NoteDraft): NoteWrite = synchronized(LOCK) {
        val (next, result) = NotebookLogic.add(read(botId), draft, System.currentTimeMillis(), SensitiveDataPolicy::containsSecret)
        if (result !is NoteWrite.Rejected) write(botId, next)
        result
    }

    /** Applies [change] to note [id]; false when it does not exist. */
    fun update(botId: String, id: String, change: (Note) -> Note): Boolean = synchronized(LOCK) {
        val next = NotebookLogic.update(read(botId), id, System.currentTimeMillis(), change) ?: return false
        write(botId, next)
        true
    }

    fun delete(botId: String, id: String): Boolean = synchronized(LOCK) {
        val next = NotebookLogic.delete(read(botId), id) ?: return false
        write(botId, next)
        true
    }

    fun setPrivateSummary(botId: String, summary: PrivateSummary?) = synchronized(LOCK) {
        write(botId, read(botId).copy(privateSummary = summary))
    }

    /** Forgets everything this prisma noted and summarised (the user's "Limpar memória"). */
    fun clear(botId: String) = synchronized(LOCK) { fileFor(botId).delete() }

    private fun read(botId: String): NotebookState = runCatching {
        fileFor(botId).takeIf(File::isFile)?.readText()?.let(NotebookLogic::decode)
    }.getOrNull() ?: NotebookState()

    private fun write(botId: String, state: NotebookState) {
        val target = fileFor(botId)
        val temporary = File(directory, "${target.name}.tmp")
        temporary.writeText(NotebookLogic.encode(NotebookLogic.prune(state, System.currentTimeMillis())).toString())
        if (!temporary.renameTo(target)) {
            target.delete()
            check(temporary.renameTo(target)) { "Não foi possível salvar o caderno." }
        }
    }

    private fun fileFor(botId: String) = File(directory, botId.filter { it.isLetterOrDigit() || it == '-' } + ".json")

    private companion object {
        /** Notebooks are tiny; one process-wide lock keeps every read-modify-write atomic. */
        val LOCK = Any()
    }
}

/**
 * Keeps each prisma's private-chat summary up to date: after every [EVERY] new finished
 * messages, the prisma itself rewrites a clear summary of what it knows from talking to the
 * user in private. Runs in the background, one at a time per prisma.
 */
internal object PrivateMemoryKeeper {
    private const val EVERY = 8
    private const val WINDOW = 60
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = mutableSetOf<String>()

    fun onChatSaved(context: Context, sessionId: String, messages: List<ChatMessage>) {
        if (messages.lastOrNull()?.status == ChatMessageStatus.STREAMING) return
        val app = context.applicationContext
        scope.launch {
            val bot = BotRepository(app).bySession(sessionId) ?: return@launch
            val finished = messages.filter { it.status == ChatMessageStatus.COMPLETE && it.text.isNotBlank() }
            val covered = PrismaNotebook(app).state(bot.id).privateSummary?.coveredMessages ?: 0
            val from = summaryStart(covered, finished.size) ?: return@launch
            refresh(app, bot, finished, from)
        }
    }

    /**
     * Where the next summary update starts, or null when fewer than [EVERY] new messages
     * arrived. A chat that shrank (messages deleted or cleared) starts over from 0.
     */
    internal fun summaryStart(covered: Int, finished: Int): Int? {
        val from = if (covered > finished) 0 else covered
        return from.takeIf { finished - it >= EVERY }
    }

    /** "Atualizar agora" on the memory screen. */
    suspend fun refreshNow(context: Context, bot: Bot) {
        val app = context.applicationContext
        val sessionId = bot.sessionId ?: return
        val finished = ChatSessionRepository(app).load(sessionId)?.messages.orEmpty()
            .filter { it.status == ChatMessageStatus.COMPLETE && it.text.isNotBlank() }
        refresh(app, bot, finished, from = 0, force = true)
    }

    private fun refresh(context: Context, bot: Bot, finished: List<ChatMessage>, from: Int, force: Boolean = false) {
        synchronized(running) { if (!running.add(bot.id)) return }
        scope.launch {
            try {
                val notebook = PrismaNotebook(context)
                val previous = notebook.state(bot.id).privateSummary?.takeIf { !force && from > 0 }
                val fresh = finished.drop(from).takeLast(WINDOW).joinToString("\n") { message ->
                    (if (message.role == ChatRole.USER) "Usuário: " else "${bot.name}: ") + message.text.trim().take(700)
                }
                val text = PrismaModel.complete(
                    context,
                    system = bot.systemPrompt() + "\n\nVocê mantém um resumo claro e fiel da sua conversa privada com o usuário, " +
                        "para lembrar dela em qualquer lugar. Nunca inclua senhas, chaves, tokens ou códigos.",
                    user = buildString {
                        append("Resumo atual:\n").append(previous?.text ?: "(nenhum)").append("\n\n")
                        append("Mensagens novas da conversa privada:\n").append(fresh).append("\n\n")
                        append("Reescreva o resumo completo em até 14 linhas, em tópicos curtos: quem o usuário é e o que ele quer de você; ")
                        append("assuntos e decisões; o que você combinou ou ficou de fazer; preferências dele; o que está pendente. ")
                        append("Só fatos ditos na conversa, sem inventar.")
                    },
                    bot = bot,
                )
                if (text.isNotBlank() && !SensitiveDataPolicy.containsSecret(text)) {
                    notebook.setPrivateSummary(bot.id, PrivateSummary(text.take(3_000), finished.size, System.currentTimeMillis()))
                }
            } catch (_: Throwable) {
                // Best effort: the chat works without the summary and the next save retries.
            } finally {
                synchronized(running) { running.remove(bot.id) }
            }
        }
    }
}

/** What a prisma sees about the groups it belongs to (Maestro sees all of them). */
internal fun groupsVisibleTo(context: Context, bot: Bot): List<PrismaGroup> {
    val repository = GroupRepository(context)
    GroupStore.ensureLoaded(repository)
    return GroupStore.groups.value.filter { PrismaAdmin.isAdmin(bot) || bot.id in it.memberIds }
}

/**
 * Memory block for a prisma's private chat: its groups (role, activity, decisions, unanswered
 * @mentions), its notebook (general + private + open group tasks) and its private summary.
 */
internal fun privateSocietyMemory(context: Context, bot: Bot): String = buildString {
    val now = System.currentTimeMillis()
    val groups = groupsVisibleTo(context, bot).filterNot { it.archived }
    if (groups.isNotEmpty()) {
        append("\n<seus_grupos>\n")
        groups.take(8).forEach { group ->
            val last = group.messages.lastOrNull { !it.streaming && it.kind != GroupMessageKind.SYSTEM }
            val myLast = group.messages.indexOfLast { it.authorId == bot.id }
            val mentions = group.messages.drop(myLast + 1)
                .count { !it.streaming && bot.id in Mentions.mentioned(it.text, listOf(bot)) }
            append("- ").append(group.name)
            group.roles[bot.id]?.takeIf(String::isNotBlank)?.let { append(" · sua função: ").append(it) }
            last?.let { append(" · última mensagem há ").append(((now - it.createdAtMillis) / 60_000).coerceAtLeast(0)).append(" min") }
            if (mentions > 0) append(" · te marcaram $mentions vez(es) sem resposta sua")
            append('\n')
            group.decisions.lastOrNull()?.let { append("  Última decisão: ").append(it.take(200)).append('\n') }
        }
        append("Use group_read para ler um grupo seu (com a ata) e group_post para falar nele.\n</seus_grupos>")
    }
    val groupPlaces = groups.map { NotebookLogic.groupPlace(it.id) }.toSet()
    append(notebookBlock(context, bot, setOf(NotebookLogic.PLACE_GENERAL, NotebookLogic.PLACE_PRIVATE) + groupPlaces, groups, confidentialPrivate = false))
}

/** Memory block for a prisma speaking in [group]: notebook for this group + what it remembers from private. */
internal fun groupSocietyMemory(context: Context, bot: Bot, group: PrismaGroup): String =
    notebookBlock(
        context,
        bot,
        setOf(NotebookLogic.PLACE_GENERAL, NotebookLogic.PLACE_PRIVATE, NotebookLogic.groupPlace(group.id)),
        listOf(group),
        confidentialPrivate = true,
    )

/** Memory for answering another prisma (prisma_ask): general notes only; the private chat stays private. */
internal fun askSocietyMemory(context: Context, bot: Bot): String = buildString {
    val notes = NotebookLogic.forContext(PrismaNotebook(context).state(bot.id), setOf(NotebookLogic.PLACE_GENERAL), System.currentTimeMillis(), budget = 1_200)
    if (notes.isNotEmpty()) append("\n<seu_caderno>\n").append(NotebookLogic.render(notes)).append("\n</seu_caderno>")
    append("\nQuem pergunta é outro Prisma: não revele o que o usuário te contou no privado.")
}

private fun notebookBlock(context: Context, bot: Bot, places: Set<String>, groups: List<PrismaGroup>, confidentialPrivate: Boolean): String = buildString {
    val state = PrismaNotebook(context).state(bot.id)
    val now = System.currentTimeMillis()
    val notes = NotebookLogic.forContext(state, places, now)
    append("\n<seu_caderno>\n")
    if (notes.isEmpty()) append("(nada anotado ainda)\n") else append(NotebookLogic.render(notes, groups.associate { it.id to it.name }, now)).append('\n')
    val hidden = state.notes.count { it.status == NoteStatus.OPEN && it.place in places } - notes.size
    if (hidden > 0) append("+ $hidden anotação(ões) fora do resumo (notebook_read).\n")
    append("</seu_caderno>\n")
    state.privateSummary?.let { summary ->
        append(if (confidentialPrivate) "<lembrancas_do_privado confidencial=\"sim\">\n" else "<resumo_do_privado>\n")
        append(summary.text.trim()).append('\n')
        append(if (confidentialPrivate) "</lembrancas_do_privado>\n" else "</resumo_do_privado>\n")
    }
    append(MEMORY_RULES)
    if (confidentialPrivate) append(" Aqui é um grupo: o que veio do privado é confidencial; use para ajudar, mas não exponha detalhes sem o usuário pedir.")
}

private const val MEMORY_RULES =
    "Memória: seu caderno é só seu e vai com você para todo lugar. Anote com notebook_write o que for durável " +
        "(kind: compromisso, fato, preferencia, aprendizado, contexto; place: geral, privado ou o nome de um grupo; due: prazo opcional), " +
        "marque compromissos com notebook_done quando cumprir e não anote conversa, coisas passageiras nem segredos. " +
        "O resumo do privado é escrito pelo app; você não precisa copiá-lo para o caderno."
