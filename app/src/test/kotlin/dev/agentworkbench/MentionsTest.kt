package dev.agentworkbench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MentionsTest {
    private val maestro = PrismaAdmin.default()
    private val nina = Bot(id = "nina", name = "Nina", job = "pesquisa")
    private val code = Bot(id = "code", name = "Code Assist", job = "código")
    private val ana = Bot(id = "ana", name = "Ana Clara", job = "design")
    private val ana2 = Bot(id = "ana-b", name = "Ana Beatriz", job = "vendas")
    private val acento = Bot(id = "joao", name = "João", job = "finanças")
    private val all = listOf(nina, code, maestro, ana, ana2, acento)

    @Test
    fun `handles are short, stable and unique`() {
        val handles = Mentions.handles(all)
        assertEquals("maestro", handles[maestro.id])
        assertEquals("nina", handles["nina"])
        assertEquals("code", handles["code"])
        assertEquals("ana", handles["ana"])
        assertEquals("ana2", handles["ana-b"])
        assertEquals("joao", handles["joao"])
    }

    @Test
    fun `tags by handle or full name, ignoring case and accents`() {
        assertEquals(setOf("nina"), Mentions.mentioned("@NINA pode olhar isso?", all))
        assertEquals(setOf("code"), Mentions.mentioned("valeu @Code Assist!", all))
        assertEquals(setOf("joao"), Mentions.mentioned("@joão e o orçamento?", all))
        assertEquals(setOf(maestro.id, "nina"), Mentions.mentioned("@maestro organiza e @nina pesquisa", all))
    }

    @Test
    fun `a handle does not match inside a longer word`() {
        assertTrue(Mentions.mentioned("@ninaldo chegou", all).isEmpty())
        assertEquals(setOf("ana-b"), Mentions.mentioned("@ana2, revisa?", all))
    }

    @Test
    fun `user tags are recognised in every spelling`() {
        listOf("@você", "@voce", "@Usuário", "@usuario", "@chefe", "@dono").forEach {
            assertTrue(Mentions.mentionsUser("$it, precisamos de você"), it)
        }
        assertFalse(Mentions.mentionsUser("vocês decidem"))
        assertFalse(Mentions.mentionsUser("@vocesabe"))
    }

    @Test
    fun `legend lists everyone with role and the user last`() {
        val legend = Mentions.legend(listOf(maestro, nina), Mentions.handles(listOf(maestro, nina)), mapOf("nina" to "pesquisa"))
        assertEquals("@maestro = Maestro (administrador) · @nina = Nina (pesquisa) · @você = o usuário", legend)
    }
}
