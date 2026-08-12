package dev.agentworkbench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject

class LocalGgufProtocolTest {
    @Test
    fun `plain short answer does not advertise tools`() {
        assertFalse(LocalGgufPromptPolicy.hasToolIntent("Diga apenas: teste rápido."))
    }

    @Test
    fun `explicit runtime request advertises tools`() {
        assertTrue(LocalGgufPromptPolicy.hasToolIntent("Execute este comando no terminal"))
    }

    @Test
    fun `constrained prompt preserves explicit instruction and drops unrelated skill`() {
        val source = """
            boilerplate muito longo
            <instruction scope="global">Sempre responda em português.</instruction>
            <skill id="git">Use git status antes de commits.</skill>
        """.trimIndent()
        val compact = LocalGgufPromptPolicy.compactSystem(source, "Diga apenas teste rápido")
        assertTrue(compact.contains("Sempre responda em português"))
        assertFalse(compact.contains("git status"))
        assertFalse(compact.contains("boilerplate muito longo"))
    }

    @Test
    fun `small model receives a bounded completion budget`() {
        assertEquals(96, LocalGgufPromptPolicy.predictionBudget("resposta curta", 258L * 1_048_576L, 2_048))
    }

    @Test
    fun `micro models are chat only`() {
        assertTrue(LocalGgufPromptPolicy.isMicroModel("smollm2-360m-instruct.gguf", 250L * 1_048_576L))
        assertFalse(LocalGgufPromptPolicy.isMicroModel("qwen3-0.6b-q4_k_m.gguf", 462L * 1_048_576L))
    }

    @Test
    fun `constrained qwen disables open ended thinking`() {
        assertEquals(
            "Qual é a hora?\n/no_think",
            LocalGgufPromptPolicy.prepareUserPrompt("Qual é a hora?", "qwen3-0.6b.gguf", constrained = true),
        )
        assertEquals(
            "Qual é a hora?",
            LocalGgufPromptPolicy.prepareUserPrompt("Qual é a hora?", "qwen2.5.gguf", constrained = true),
        )
    }
    @Test
    fun parsesNestedToolArgumentsOnlyForAdvertisedTools() {
        val response = """
            <tool_call>{"name":"workspace_write","arguments":{"path":"a.txt","metadata":{"mode":"safe"}}}</tool_call>
        """.trimIndent()

        val parsed = LocalGgufProtocol.parseToolCall(response, setOf("workspace_write"))

        assertEquals("workspace_write", parsed?.first)
        assertEquals("safe", JSONObject(parsed!!.second).getJSONObject("metadata").getString("mode"))
        assertNull(LocalGgufProtocol.parseToolCall(response, setOf("workspace_read")))
    }

    @Test
    fun holdsOnlyAValidToolCallPrefixBeforeStreamingText() {
        assertTrue(LocalGgufProtocol.shouldHoldPrefix("<tool"))
        assertTrue(LocalGgufProtocol.shouldHoldPrefix("<tool_call>{"))
        assertFalse(LocalGgufProtocol.shouldHoldPrefix("Resposta comum"))
    }

    @Test
    fun `holds tool call which follows a reasoning block`() {
        assertTrue(LocalGgufProtocol.shouldHoldPrefix("<think>analisando"))
        assertTrue(
            LocalGgufProtocol.shouldHoldPrefix(
                "<think>analisando</think>\n\n<tool_call>{\"name\":\"browser_search\"}",
            ),
        )
        assertFalse(LocalGgufProtocol.shouldHoldPrefix("<think>analisando</think>Resposta comum"))
    }

    @Test
    fun streamsThinkingSeparatelyEvenWhenMarkersAreSplit() {
        val parser = LocalReasoningStreamParser()
        val chunks = buildList {
            addAll(parser.feed("<thi"))
            addAll(parser.feed("nk>analisando</thi"))
            addAll(parser.feed("nk>resposta"))
            addAll(parser.finish())
        }

        assertEquals(
            listOf(
                LocalReasoningChunk(true, "analisando"),
                LocalReasoningChunk(false, "resposta"),
            ),
            chunks,
        )
    }
}
