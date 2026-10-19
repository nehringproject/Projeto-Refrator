package dev.agentworkbench

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.Instant
import kotlinx.coroutines.launch

/** Tokens spent in one conversation, summed over every model call (kept across app restarts). */
internal data class SessionUsage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cachedTokens: Long = 0,
    val calls: Int = 0,
) {
    operator fun plus(other: SessionUsage) = SessionUsage(
        inputTokens + other.inputTokens,
        outputTokens + other.outputTokens,
        cachedTokens + other.cachedTokens,
        calls + other.calls,
    )
}

internal object SessionUsageStore {
    private const val PREFERENCES = "conversation-usage"

    fun load(context: Context, sessionId: String): SessionUsage {
        val raw = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(sessionId, null)
            ?: return SessionUsage()
        val parts = raw.split(',').mapNotNull(String::toLongOrNull)
        if (parts.size != 4) return SessionUsage()
        return SessionUsage(parts[0], parts[1], parts[2], parts[3].toInt())
    }

    fun save(context: Context, sessionId: String, usage: SessionUsage) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(sessionId, "${usage.inputTokens},${usage.outputTokens},${usage.cachedTokens},${usage.calls}")
            .apply()
    }
}

/**
 * Turns provider usage-limit headers into readable windows. Understands the Codex/ChatGPT-plan
 * headers (x-codex-primary-* / x-codex-secondary-*: used percent, window length, reset), the
 * OpenAI API (x-ratelimit-*-tokens/requests) and Anthropic (anthropic-ratelimit-*).
 */
internal fun parseRateLimits(headers: Map<String, String>, nowMillis: Long = System.currentTimeMillis()): List<RateLimitWindow> {
    val windows = mutableListOf<RateLimitWindow>()
    listOf("primary", "secondary").forEach { slot ->
        val used = headers["x-codex-$slot-used-percent"]?.toFloatOrNull() ?: return@forEach
        val minutes = headers["x-codex-$slot-window-minutes"]?.toLongOrNull()
        val resetSeconds = headers["x-codex-$slot-reset-after-seconds"]?.toLongOrNull()
        val resetAt = headers["x-codex-$slot-reset-at"]?.toLongOrNull()?.let { if (it < 10_000_000_000L) it * 1_000 else it }
        windows += RateLimitWindow(
            label = windowLabel(minutes, fallback = if (slot == "primary") "Janela curta" else "Janela longa"),
            usedPercent = used.coerceIn(0f, 100f),
            resetsAtMillis = resetAt ?: resetSeconds?.let { nowMillis + it * 1_000 },
        )
    }
    fun remainingWindow(label: String, limitKey: String, remainingKey: String, resetKey: String) {
        val limit = headers[limitKey]?.toDoubleOrNull() ?: return
        val remaining = headers[remainingKey]?.toDoubleOrNull() ?: return
        if (limit <= 0) return
        windows += RateLimitWindow(
            label = label,
            usedPercent = (((limit - remaining) / limit) * 100).toFloat().coerceIn(0f, 100f),
            resetsAtMillis = headers[resetKey]?.let { parseReset(it, nowMillis) },
        )
    }
    remainingWindow("Tokens por minuto", "x-ratelimit-limit-tokens", "x-ratelimit-remaining-tokens", "x-ratelimit-reset-tokens")
    remainingWindow("Pedidos por minuto", "x-ratelimit-limit-requests", "x-ratelimit-remaining-requests", "x-ratelimit-reset-requests")
    remainingWindow("Tokens (Anthropic)", "anthropic-ratelimit-tokens-limit", "anthropic-ratelimit-tokens-remaining", "anthropic-ratelimit-tokens-reset")
    remainingWindow("Pedidos (Anthropic)", "anthropic-ratelimit-requests-limit", "anthropic-ratelimit-requests-remaining", "anthropic-ratelimit-requests-reset")
    return windows
}

private fun windowLabel(minutes: Long?, fallback: String): String = when {
    minutes == null -> fallback
    minutes in 280..320 -> "Janela de 5 horas"
    minutes in 9_800..10_400 -> "Semana"
    minutes % 1_440 == 0L -> "Janela de ${minutes / 1_440} dias"
    minutes % 60 == 0L -> "Janela de ${minutes / 60} h"
    else -> "Janela de $minutes min"
}

/** Accepts "6m0s"/"1h2m3.5s"/"20ms" durations (OpenAI) and RFC 3339 instants (Anthropic). */
private fun parseReset(value: String, nowMillis: Long): Long? {
    runCatching { return Instant.parse(value).toEpochMilli() }
    val match = Regex("""^(?:(\d+)h)?(?:(\d+)m(?!s))?(?:([\d.]+)s)?(?:(\d+)ms)?$""").matchEntire(value.trim()) ?: return null
    val (h, m, s, ms) = match.destructured
    val total = (h.toLongOrNull() ?: 0) * 3_600_000 + (m.toLongOrNull() ?: 0) * 60_000 +
        ((s.toDoubleOrNull() ?: 0.0) * 1_000).toLong() + (ms.toLongOrNull() ?: 0)
    return if (total > 0) nowMillis + total else null
}

/** Latest usage-limit windows seen for the signed-in account (shared across conversations). */
internal object RateLimitMemory {
    /** The most recent windows from any provider (what the conversation panel shows). */
    var windows by mutableStateOf<List<RateLimitWindow>>(emptyList())

    /** Windows per provider ([ProviderPreset] name), since prismas may use different ones. */
    private val byProvider = java.util.concurrent.ConcurrentHashMap<String, List<RateLimitWindow>>()

    fun record(provider: String, latest: List<RateLimitWindow>) {
        windows = latest
        byProvider[provider] = latest
    }

    fun forProvider(provider: String): List<RateLimitWindow> = byProvider[provider].orEmpty()
}

/**
 * What a prisma knows about the account it is spending: usage-limit windows of its provider and,
 * where the provider tells, the remaining balance. Balances are fetched in the background and
 * cached, so building a prompt never waits for the network.
 */
internal object AccountUsage {
    private const val BALANCE_TTL_MS = 10 * 60_000L
    private val balances = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String?>>()
    private val fetching = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** The prompt block, or "" when nothing is known yet. Also refreshes the balance in the background. */
    fun awareness(context: Context, settings: ProviderSettings, sessionTokens: Long? = null): String {
        refreshBalance(context, settings)
        val now = System.currentTimeMillis()
        val limits = RateLimitMemory.forProvider(settings.preset.name).filter { it.usedPercent != null }
        val balance = balances[settings.preset.name]?.second
        if (limits.isEmpty() && balance == null && sessionTokens == null) return ""
        return buildString {
            append("\n<uso_da_conta provedor=\"").append(settings.preset.displayName).append("\" modelo=\"").append(settings.modelId).append("\">\n")
            limits.forEach { window ->
                append("- ").append(window.label).append(": ").append(window.usedPercent!!.toInt()).append("% usado")
                window.resetsAtMillis?.takeIf { it > now }?.let { append(" (renova em ").append(usageRemaining(it - now)).append(')') }
                append('\n')
            }
            balance?.let { append("- Saldo: ").append(it).append('\n') }
            sessionTokens?.takeIf { it > 0 }?.let { append("- Nesta conversa já foram gastos cerca de ").append(it / 1_000).append(" mil tokens.\n") }
            append("Se algum limite passar de 80% ou o saldo estiver baixo, avise o usuário uma vez, de forma breve, ")
            append("e economize: respostas mais curtas e só as ferramentas necessárias.\n</uso_da_conta>")
        }
    }

    private fun refreshBalance(context: Context, settings: ProviderSettings) {
        val key = settings.preset.name
        val cached = balances[key]
        if (cached != null && System.currentTimeMillis() - cached.first < BALANCE_TTL_MS) return
        if (settings.preset != ProviderPreset.OPENROUTER && settings.preset != ProviderPreset.DEEPSEEK) return
        if (!fetching.add(key)) return
        val app = context.applicationContext
        scope.launch {
            try {
                val text = runCatching { ProviderSettingsRepository(app).accountBalance(settings) }.getOrNull()
                balances[key] = System.currentTimeMillis() to text
            } finally {
                fetching.remove(key)
            }
        }
    }

    private fun usageRemaining(millis: Long): String {
        val minutes = millis / 60_000
        return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
    }
}
