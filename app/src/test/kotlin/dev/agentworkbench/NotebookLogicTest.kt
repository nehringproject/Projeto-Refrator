package dev.agentworkbench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotebookLogicTest {
    private val now = 1_800_000_000_000L
    private val noSecrets: (String) -> Boolean = { false }
    private var counter = 0
    private val ids: () -> String = { "id${counter++}" }

    private fun draft(
        text: String,
        kind: NoteKind = NoteKind.FACT,
        place: String = NotebookLogic.PLACE_GENERAL,
        due: Long? = null,
        pinned: Boolean = false,
    ) = NoteDraft(kind = kind, text = text, place = place, source = NotebookLogic.PLACE_PRIVATE, dueAtMillis = due, pinned = pinned)

    private fun NotebookState.with(vararg drafts: NoteDraft): NotebookState =
        drafts.fold(this) { state, value -> NotebookLogic.add(state, value, now, noSecrets, ids).first }

    @Test
    fun `adds a note with id, dates and normalized spaces`() {
        val (state, result) = NotebookLogic.add(NotebookState(), draft("  O usuário   gosta de café  "), now, noSecrets, ids)
        val added = assertIs<NoteWrite.Added>(result).note
        assertEquals("O usuário gosta de café", added.text)
        assertEquals(now, added.createdAtMillis)
        assertEquals(listOf(added), state.notes)
    }

    @Test
    fun `rejects blank, too long and secret text`() {
        assertIs<NoteWrite.Rejected>(NotebookLogic.add(NotebookState(), draft("   "), now, noSecrets, ids).second)
        assertIs<NoteWrite.Rejected>(NotebookLogic.add(NotebookState(), draft("x".repeat(NotebookLogic.MAX_TEXT + 1)), now, noSecrets, ids).second)
        val secret = NotebookLogic.add(NotebookState(), draft("senha do wifi: 12345678"), now, { true }, ids)
        assertIs<NoteWrite.Rejected>(secret.second)
        assertTrue(secret.first.notes.isEmpty())
    }

    @Test
    fun `real secret policy blocks api keys`() {
        val result = NotebookLogic.add(NotebookState(), draft("minha chave é sk-abcdefghijklmnopqrstuvwx"), now, SensitiveDataPolicy::containsSecret, ids)
        assertIs<NoteWrite.Rejected>(result.second)
    }

    @Test
    fun `near duplicate in the same kind and place is merged, not duplicated`() {
        val first = NotebookState().with(draft("O usuário prefere respostas curtas e diretas"))
        val (state, result) = NotebookLogic.add(first, draft("o usuario prefere respostas curtas e bem diretas"), now + 1, noSecrets, ids)
        assertIs<NoteWrite.Merged>(result)
        assertEquals(1, state.notes.size)
        assertEquals(now + 1, state.notes.single().updatedAtMillis)
    }

    @Test
    fun `same text in another place or kind is a different note`() {
        val state = NotebookState().with(
            draft("Revisar o plano de lançamento"),
            draft("Revisar o plano de lançamento", place = NotebookLogic.groupPlace("g1")),
            draft("Revisar o plano de lançamento", kind = NoteKind.TASK),
        )
        assertEquals(3, state.notes.size)
    }

    @Test
    fun `similarity ignores case and accents`() {
        assertEquals(1.0, NotebookLogic.similarity("Lançamento da Campanha", "lancamento da campanha"))
        assertTrue(NotebookLogic.similarity("comprar pão", "estudar kotlin") < 0.1)
    }

    @Test
    fun `context puts overdue tasks first and hides finished ones`() {
        val state = NotebookState().with(
            draft("Gosta de música lo-fi", kind = NoteKind.PREFERENCE),
            draft("Enviar o relatório", kind = NoteKind.TASK, due = now - 1_000),
            draft("Ler o artigo depois", kind = NoteKind.TASK, due = now + 86_400_000),
        )
        val done = NotebookLogic.update(state, state.notes.first { it.text.startsWith("Ler") }.id, now) { it.copy(status = NoteStatus.DONE) }!!
        val context = NotebookLogic.forContext(done, setOf(NotebookLogic.PLACE_GENERAL), now)
        assertEquals("Enviar o relatório", context.first().text)
        assertTrue(context.none { it.status == NoteStatus.DONE })
        assertTrue(NotebookLogic.render(context, now = now).contains("ATRASADO"))
    }

    @Test
    fun `context only shows the requested places`() {
        val state = NotebookState().with(
            draft("Coisa do grupo A", place = NotebookLogic.groupPlace("a")),
            draft("Coisa do grupo B", place = NotebookLogic.groupPlace("b")),
            draft("Coisa do privado", place = NotebookLogic.PLACE_PRIVATE),
        )
        val inGroupA = NotebookLogic.forContext(state, setOf(NotebookLogic.PLACE_GENERAL, NotebookLogic.groupPlace("a")), now)
        assertEquals(listOf("Coisa do grupo A"), inGroupA.map { it.text })
    }

    @Test
    fun `context respects the character budget`() {
        val state = (1..40).fold(NotebookState()) { acc, n ->
            NotebookLogic.add(acc, draft("Fato número $n " + "detalhe ".repeat(20)), now, noSecrets, ids).first
        }
        val context = NotebookLogic.forContext(state, setOf(NotebookLogic.PLACE_GENERAL), now, budget = 600)
        assertTrue(context.sumOf { it.text.length + 40 } <= 600)
        assertTrue(context.isNotEmpty())
    }

    @Test
    fun `prune removes old finished tasks but keeps pinned ones`() {
        val state = NotebookState().with(
            draft("Tarefa antiga", kind = NoteKind.TASK),
            draft("Tarefa fixada", kind = NoteKind.TASK, pinned = true),
        )
        val finished = state.copy(notes = state.notes.map { it.copy(status = NoteStatus.DONE, updatedAtMillis = now) })
        val later = now + NotebookLogic.DONE_RETENTION_MILLIS + 1
        assertEquals(listOf("Tarefa fixada"), NotebookLogic.prune(finished, later).notes.map { it.text })
    }

    @Test
    fun `prune caps the notebook dropping the least important first`() {
        val many = (1..NotebookLogic.MAX_NOTES + 5).fold(NotebookState()) { acc, n ->
            val kind = if (n <= 5) NoteKind.CONTEXT else NoteKind.TASK
            NotebookLogic.add(acc, draft("anotação única $n ${"x$n".repeat(3)}", kind = kind), now + n, noSecrets, ids).first
        }
        assertEquals(NotebookLogic.MAX_NOTES, many.notes.size)
        assertTrue(many.notes.none { it.kind == NoteKind.CONTEXT })
    }

    @Test
    fun `encode and decode round trip, including the private summary`() {
        val state = NotebookState().with(draft("Prazo da entrega", kind = NoteKind.TASK, due = now + 5, pinned = true))
            .copy(privateSummary = PrivateSummary("- Gosta de café", coveredMessages = 12, updatedAtMillis = now))
        val decoded = NotebookLogic.decode(NotebookLogic.encode(state).toString())
        assertEquals(state, decoded)
    }

    @Test
    fun `decodes the old v1 array format`() {
        val v1 = """[{"id":"abc12345","text":"Anotação antiga","place":"geral","done":true,"created_at":5}]"""
        val note = NotebookLogic.decode(v1).notes.single()
        assertEquals("Anotação antiga", note.text)
        assertEquals(NoteStatus.DONE, note.status)
        assertEquals(NoteKind.CONTEXT, note.kind)
    }

    @Test
    fun `broken entries are skipped instead of losing the notebook`() {
        val raw = """{"version":2,"notes":[{"id":"ok1","kind":"FACT","text":"Bom"},{"kind":"FACT"}],"private_summary":null}"""
        val state = NotebookLogic.decode(raw)
        assertEquals(listOf("Bom"), state.notes.map { it.text })
        assertNull(state.privateSummary)
    }

    @Test
    fun `update and delete report missing ids`() {
        val state = NotebookState().with(draft("Algo"))
        assertNull(NotebookLogic.update(state, "nope", now) { it })
        assertNull(NotebookLogic.delete(state, "nope"))
        assertNotNull(NotebookLogic.delete(state, state.notes.single().id))
    }

    @Test
    fun `private summary refreshes every eight messages and restarts when the chat shrinks`() {
        assertNull(PrivateMemoryKeeper.summaryStart(covered = 0, finished = 7))
        assertEquals(0, PrivateMemoryKeeper.summaryStart(covered = 0, finished = 8))
        assertNull(PrivateMemoryKeeper.summaryStart(covered = 10, finished = 15))
        assertEquals(10, PrivateMemoryKeeper.summaryStart(covered = 10, finished = 18))
        assertEquals(0, PrivateMemoryKeeper.summaryStart(covered = 30, finished = 9))
    }
}
