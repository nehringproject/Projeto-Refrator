package dev.agentworkbench.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ServerSentEventDecoderTest {
    @Test
    fun `decodes named multiline event and ignores comments`() {
        val decoder = ServerSentEventDecoder()

        assertNull(decoder.acceptLine(": keepalive"))
        assertNull(decoder.acceptLine("event: response.output_text.delta"))
        assertNull(decoder.acceptLine("data: {\"delta\":\"hello\"}"))
        assertNull(decoder.acceptLine("data: second line"))
        val event = decoder.acceptLine("")

        assertEquals("response.output_text.delta", event?.event)
        assertEquals("{\"delta\":\"hello\"}\nsecond line", event?.data)
    }

    @Test
    fun `finish emits unterminated final event`() {
        val decoder = ServerSentEventDecoder()
        decoder.acceptLine("data: [DONE]")

        assertEquals("[DONE]", decoder.finish()?.data)
    }

    @Test
    fun `event size is bounded and decoder recovers`() {
        val decoder = ServerSentEventDecoder(maxEventBytes = 8)

        assertFailsWith<ServerSentEventTooLarge> {
            decoder.acceptLine("data: too-large")
        }
        decoder.acceptLine("data:x")
        assertEquals("x", decoder.acceptLine("")?.data)
    }
}
