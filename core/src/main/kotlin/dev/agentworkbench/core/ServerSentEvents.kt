package dev.agentworkbench.core

data class ServerSentEvent(
    val event: String?,
    val data: String,
)

class ServerSentEventTooLarge(
    maxEventBytes: Int,
) : IllegalArgumentException("Server-sent event exceeds $maxEventBytes bytes")

/**
 * Incremental SSE decoder. Feed it lines without their line terminator.
 * A blank line emits one event; comments and unknown fields are ignored.
 */
class ServerSentEventDecoder(
    private val maxEventBytes: Int = DEFAULT_MAX_EVENT_BYTES,
) {
    private var eventName: String? = null
    private val dataLines = mutableListOf<String>()
    private var eventBytes = 0

    init {
        require(maxEventBytes > 0) { "maxEventBytes must be positive" }
    }

    fun acceptLine(line: String): ServerSentEvent? {
        if (line.isEmpty()) return flush()
        if (line.startsWith(":")) return null

        eventBytes += line.toByteArray(Charsets.UTF_8).size + 1
        if (eventBytes > maxEventBytes) {
            reset()
            throw ServerSentEventTooLarge(maxEventBytes)
        }

        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)

        when (field) {
            "event" -> eventName = value
            "data" -> dataLines += value
        }
        return null
    }

    fun finish(): ServerSentEvent? = flush()

    private fun flush(): ServerSentEvent? {
        if (dataLines.isEmpty()) {
            reset()
            return null
        }
        val result = ServerSentEvent(
            event = eventName,
            data = dataLines.joinToString("\n"),
        )
        reset()
        return result
    }

    private fun reset() {
        eventName = null
        dataLines.clear()
        eventBytes = 0
    }

    private companion object {
        const val DEFAULT_MAX_EVENT_BYTES = 1_048_576
    }
}
