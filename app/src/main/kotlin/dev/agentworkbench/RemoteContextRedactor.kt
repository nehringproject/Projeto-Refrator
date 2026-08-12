package dev.agentworkbench

import dev.agentworkbench.core.MessagePart
import dev.agentworkbench.core.ModelProvider
import dev.agentworkbench.core.ProviderEvent
import dev.agentworkbench.core.ProviderLocality
import dev.agentworkbench.core.ProviderMessage
import dev.agentworkbench.core.ProviderRequest
import kotlinx.coroutines.flow.Flow

/** Last-mile privacy boundary applied immediately before any remote ModelProvider call. */
class RedactingModelProvider(
    private val delegate: ModelProvider,
) : ModelProvider {
    init {
        require(delegate.descriptor.locality == ProviderLocality.REMOTE)
    }

    override val descriptor = delegate.descriptor

    override fun stream(request: ProviderRequest): Flow<ProviderEvent> =
        delegate.stream(RemoteContextRedactor.redact(request))

    override suspend fun cancel(sessionId: String): Boolean = delegate.cancel(sessionId)
}

object RemoteContextRedactor {
    private const val REDACTED = "[REDACTED_LOCAL]"

    private val prefixedSecret = Regex(
        "(?i)\\b(?:gsk_|nvapi-|sk-|kn-)[A-Za-z0-9_-]{12,}",
    )
    private val bearerSecret = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]{12,}=*")
    private val jwtSecret = Regex("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b")
    private val privateKey = Regex(
        "-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----[\\s\\S]*?-----END (?:RSA |EC |OPENSSH )?PRIVATE KEY-----",
        RegexOption.IGNORE_CASE,
    )
    private val labelledSecret = Regex(
        "(?i)\\b(password|senha|passwd|passcode|api[_ -]?key|token|secret|otp|2fa|cvv|cvc|pin)" +
            "(\\s*[:=]\\s*)([^\\s,;]{3,})",
    )
    private val contextualOtp = Regex(
        "(?i)\\b(c[oó]digo(?: de verifica[cç][aã]o)?|verification code|otp|2fa)" +
            "(\\D{0,12})(\\d{4,8})\\b",
    )
    private val cardCandidate = Regex("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)")

    fun redact(request: ProviderRequest): ProviderRequest = request.copy(
        messages = request.messages.map(::redact),
    )

    fun redact(message: ProviderMessage): ProviderMessage = message.copy(
        parts = message.parts.map { part ->
            when (part) {
                is MessagePart.Text -> MessagePart.Text(redactText(part.value))
                is MessagePart.ToolResult -> part.copy(payload = redactText(part.payload))
                // A imagem só entra aqui após uma escolha explícita no seletor de anexos.
                // O provider HTTP converte a referência local em data URL sem expor o caminho.
                is MessagePart.ImageReference -> part
            }
        },
        toolCalls = message.toolCalls.map { call ->
            call.copy(argumentsJson = redactText(call.argumentsJson))
        },
    )

    fun redactText(value: String): String {
        var result = prefixedSecret.replace(value, REDACTED)
        result = bearerSecret.replace(result, "Bearer $REDACTED")
        result = jwtSecret.replace(result, REDACTED)
        result = privateKey.replace(result, REDACTED)
        result = labelledSecret.replace(result) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}$REDACTED"
        }
        result = contextualOtp.replace(result) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}$REDACTED"
        }
        result = cardCandidate.replace(result) { match ->
            val digits = match.value.filter(Char::isDigit)
            if (digits.length in 13..19 && luhnValid(digits)) REDACTED else match.value
        }
        return result
    }

    private fun luhnValid(value: String): Boolean {
        var sum = 0
        var double = false
        for (char in value.reversed()) {
            var digit = char.digitToInt()
            if (double) {
                digit *= 2
                if (digit > 9) digit -= 9
            }
            sum += digit
            double = !double
        }
        return sum % 10 == 0
    }
}
