package dev.agentworkbench.provider.http

import dev.agentworkbench.core.ProviderEvent
import dev.agentworkbench.core.ServerSentEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderEventMapperTest {
    @Test
    fun `chat reasoning stays separate from visible answer text`() {
        val mapper = ProviderEventMapper(HttpProviderProtocol.OPENAI_COMPATIBLE_CHAT)

        val events = mapper.map(
            ServerSentEvent(
                event = null,
                data = """
                    {
                      "model": "deepseek-ai/deepseek-v4-pro",
                      "choices": [{
                        "delta": {
                          "reasoning_content": "internal reasoning",
                          "content": "visible answer"
                        },
                        "finish_reason": null
                      }]
                    }
                """.trimIndent(),
            ),
        )

        assertEquals(
            listOf(
                ProviderEvent.ModelResolved("deepseek-ai/deepseek-v4-pro"),
                ProviderEvent.ReasoningDelta("internal reasoning"),
                ProviderEvent.TextDelta("visible answer"),
            ),
            events,
        )
    }

    @Test
    fun `chat ignores explicit json null fields`() {
        val mapper = ProviderEventMapper(HttpProviderProtocol.OPENAI_COMPATIBLE_CHAT)

        val events = mapper.map(
            ServerSentEvent(
                event = null,
                data = """{"model":null,"choices":[{"delta":{"reasoning":null,"reasoning_content":null,"content":null},"finish_reason":null}]}""",
            ),
        )

        assertTrue(events.isEmpty())
    }

    @Test
    fun `chat completion remains active until done so trailing usage is retained`() {
        val mapper = ProviderEventMapper(HttpProviderProtocol.OPENAI_COMPATIBLE_CHAT)

        val finishEvents = mapper.map(
            ServerSentEvent(
                event = null,
                data = """
                    {
                      "choices": [{"delta": {}, "finish_reason": "stop"}]
                    }
                """.trimIndent(),
            ),
        )
        val usageEvents = mapper.map(
            ServerSentEvent(
                event = null,
                data = """
                    {
                      "choices": [],
                      "usage": {
                        "prompt_tokens": 12,
                        "completion_tokens": 7
                      }
                    }
                """.trimIndent(),
            ),
        )

        assertTrue(finishEvents.isEmpty())
        assertFalse(mapper.terminalEmitted)
        assertEquals(listOf(ProviderEvent.Usage(12, 7, null)), usageEvents)

        val doneEvents = mapper.map(ServerSentEvent(event = null, data = "[DONE]"))

        assertEquals(listOf(ProviderEvent.Completed("stop")), doneEvents)
        assertTrue(mapper.terminalEmitted)
    }

    @Test
    fun `chat tool call streams id name arguments and completion`() {
        val mapper = ProviderEventMapper(HttpProviderProtocol.OPENAI_COMPATIBLE_CHAT)

        val first = mapper.map(
            ServerSentEvent(
                event = null,
                data = """
                    {
                      "choices": [{
                        "delta": {
                          "tool_calls": [{
                            "index": 0,
                            "id": "call-1",
                            "function": {
                              "name": "android_device_info",
                              "arguments": "{"
                            }
                          }]
                        },
                        "finish_reason": null
                      }]
                    }
                """.trimIndent(),
            ),
        )
        val second = mapper.map(
            ServerSentEvent(
                event = null,
                data = """
                    {
                      "choices": [{
                        "delta": {
                          "tool_calls": [{
                            "index": 0,
                            "function": {"arguments": "}"}
                          }]
                        },
                        "finish_reason": "tool_calls"
                      }]
                    }
                """.trimIndent(),
            ),
        )

        assertEquals(
            listOf(
                ProviderEvent.ToolCallStarted("call-1", "android_device_info"),
                ProviderEvent.ToolArgumentsDelta("call-1", "{"),
            ),
            first,
        )
        assertEquals(
            listOf(
                ProviderEvent.ToolArgumentsDelta("call-1", "}"),
                ProviderEvent.ToolCallCompleted("call-1"),
            ),
            second,
        )
        assertEquals(
            listOf(ProviderEvent.Completed("tool_calls")),
            mapper.map(ServerSentEvent(event = null, data = "[DONE]")),
        )
    }

    @Test
    fun `chat tool fragments are joined by provider index before dispatch`() {
        val mapper = ProviderEventMapper(HttpProviderProtocol.OPENAI_COMPATIBLE_CHAT)

        val nameFragment = mapper.map(
            ServerSentEvent(null, """{"choices":[{"delta":{"tool_calls":[{"index":2,"function":{"name":"web_","arguments":"{\"q\":"}}]},"finish_reason":null}]}"""),
        )
        val resolved = mapper.map(
            ServerSentEvent(null, """{"choices":[{"delta":{"tool_calls":[{"index":2,"id":"call-2","function":{"name":"search","arguments":"\"teste\"}"}}]},"finish_reason":"tool_calls"}]}"""),
        )

        assertTrue(nameFragment.isEmpty())
        assertEquals(
            listOf(
                ProviderEvent.ToolCallStarted("call-2", "web_search"),
                ProviderEvent.ToolArgumentsDelta("call-2", "{\"q\":"),
                ProviderEvent.ToolArgumentsDelta("call-2", "\"teste\"}"),
                ProviderEvent.ToolCallCompleted("call-2"),
            ),
            resolved,
        )
    }
}
