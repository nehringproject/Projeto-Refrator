package dev.agentworkbench

import android.content.Context
import dev.agentworkbench.core.MessagePart
import dev.agentworkbench.core.ProviderMessage
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray

data class ContextAssembly(
    val message: ProviderMessage,
    val instructionIds: List<String>,
    val memoryIds: List<String>,
    val estimatedTokens: Int,
)

class ContextBudgetExceededException(message: String) : IllegalArgumentException(message)
class SensitiveMemoryException(message: String) : IllegalArgumentException(message)

class ContextMemoryRepository(context: Context) {
    private val dao = WorkbenchDatabase.get(context).dao()

    suspend fun setInstruction(
        scope: InstructionScope,
        scopeId: String,
        body: String,
        enabled: Boolean = true,
    ): InstructionRecord {
        val normalized = body.trim()
        val existing = dao.instruction(scope.name, scopeId)
        val now = System.currentTimeMillis()
        val value = InstructionRecord(
            id = existing?.id ?: UUID.randomUUID().toString(),
            scopeType = scope.name,
            scopeId = scopeId,
            body = normalized,
            state = if (enabled && normalized.isNotEmpty()) {
                InstructionState.ENABLED.name
            } else {
                InstructionState.DISABLED.name
            },
            revision = (existing?.revision ?: 0) + 1,
            createdAtMillis = existing?.createdAtMillis ?: now,
            updatedAtMillis = now,
        )
        dao.upsertInstruction(value)
        return value
    }

    suspend fun instruction(scope: InstructionScope, scopeId: String): InstructionRecord? =
        dao.instruction(scope.name, scopeId)

    suspend fun instructions(): List<InstructionRecord> = dao.instructions()

    suspend fun memories(): List<MemoryEntry> = dao.memories()

    suspend fun memory(id: String): MemoryEntry? = dao.memory(id)

    suspend fun addMemory(
        scope: InstructionScope,
        scopeId: String,
        category: String,
        body: String,
        sourceConversationId: String? = null,
        sourceMessageId: String? = null,
        confidence: Double = 0.8,
        pinned: Boolean = false,
    ): MemoryEntry {
        val normalized = body.trim()
        require(normalized.isNotEmpty()) { "A memória não pode estar vazia." }
        if (SensitiveDataPolicy.containsSecret(normalized)) {
            throw SensitiveMemoryException("A memória parece conter segredo, token, OTP ou dado financeiro.")
        }
        val now = System.currentTimeMillis()
        val value = MemoryEntry(
            id = UUID.randomUUID().toString(),
            scopeType = scope.name,
            scopeId = scopeId,
            category = category.trim().ifBlank { "learning" }.take(40),
            body = normalized.take(MAX_MEMORY_CHARS),
            sourceConversationId = sourceConversationId,
            sourceMessageId = sourceMessageId,
            confidence = confidence.coerceIn(0.0, 1.0),
            pinned = pinned,
            state = MemoryState.ACTIVE.name,
            expiresAtMillis = null,
            useCount = 0,
            lastUsedAtMillis = null,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        dao.upsertMemory(value)
        return value
    }

    suspend fun updateMemory(
        current: MemoryEntry,
        body: String,
        reason: String,
        author: String,
        state: MemoryState = MemoryState.valueOf(current.state),
    ): MemoryEntry {
        val normalized = body.trim()
        if (SensitiveDataPolicy.containsSecret(normalized)) {
            throw SensitiveMemoryException("A memória parece conter segredo, token, OTP ou dado financeiro.")
        }
        dao.addRevision(
            MemoryRevision(
                id = UUID.randomUUID().toString(),
                memoryId = current.id,
                previousBody = current.body,
                reason = reason.take(240),
                author = author.take(80),
                createdAtMillis = System.currentTimeMillis(),
            ),
        )
        return current.copy(
            body = normalized.take(MAX_MEMORY_CHARS),
            state = state.name,
            updatedAtMillis = System.currentTimeMillis(),
        ).also { dao.upsertMemory(it) }
    }

    suspend fun assemble(
        baseEnvironment: String,
        workspaceId: String,
        conversationId: String,
        contextWindowTokens: Int,
        messageId: String? = null,
        queryText: String = "",
    ): ContextAssembly {
        val global = dao.instruction(InstructionScope.GLOBAL.name, GLOBAL_SCOPE_ID)
            ?.takeIf { it.state == InstructionState.ENABLED.name && it.body.isNotBlank() }
        val workspace = dao.instruction(InstructionScope.WORKSPACE.name, workspaceId)
            ?.takeIf { it.state == InstructionState.ENABLED.name && it.body.isNotBlank() }
        val chat = dao.instruction(InstructionScope.CHAT.name, conversationId)
            ?.takeIf { it.state == InstructionState.ENABLED.name && it.body.isNotBlank() }
        val explicit = listOfNotNull(global, workspace, chat)
        val explicitText = explicit.joinToString("\n\n") { instruction ->
            "<instruction scope=\"${instruction.scopeType.lowercase()}\" revision=\"${instruction.revision}\">\n" +
                instruction.body + "\n</instruction>"
        }
        val explicitTokens = estimateTokens(baseEnvironment) + estimateTokens(explicitText)
        val reservedTokens = (contextWindowTokens * CONTEXT_BUDGET_PERCENT / 100)
            .coerceAtLeast(MIN_CONTEXT_BUDGET)
        if (explicitTokens > reservedTokens) {
            throw ContextBudgetExceededException(
                "Instruções explícitas usam cerca de $explicitTokens tokens; orçamento reservado: " +
                    "$reservedTokens. Reduza o hard prompt ou aumente a janela do modelo.",
            )
        }

        val memoryBudget = (reservedTokens - explicitTokens).coerceAtLeast(0)
        val selectedMemories = mutableListOf<MemoryEntry>()
        var usedMemoryTokens = 0
        // Learned memories are private to their originating conversation by
        // default. Workspace/global records remain visible in Memory Center,
        // but are never injected into another chat merely because it shares a
        // filesystem workspace. Explicit global/workspace instructions above
        // are the audited mechanism for deliberate cross-chat context.
        dao.activeChatMemories(conversationId, System.currentTimeMillis(), MAX_MEMORY_CANDIDATES)
            .map { memory -> memory to memoryRelevanceScore(queryText, memory.body, memory.pinned) }
            .filter { (_, relevance) -> relevance > 0 }
            .sortedWith(
                compareByDescending<Pair<MemoryEntry, Int>> { it.first.pinned }
                    .thenByDescending { it.second }
                    .thenByDescending { it.first.confidence }
                    .thenByDescending { it.first.updatedAtMillis },
            )
            .forEach { (memory, _) ->
                val cost = estimateTokens(memory.body) + 12
                if (usedMemoryTokens + cost <= memoryBudget) {
                    selectedMemories += memory
                    usedMemoryTokens += cost
                }
            }
        val memoryText = selectedMemories.joinToString("\n") { memory ->
            "- [${memory.category}; confidence=${"%.2f".format(memory.confidence)}] ${memory.body}"
        }
        val finalText = buildString {
            appendLine(baseEnvironment.trim())
            appendLine()
            appendLine("<context_precedence>Chat > workspace > hard prompt global > memória aprendida. Memória nunca substitui instrução explícita.</context_precedence>")
            appendLine("Use memory_save somente para preferência, decisão, fato estável ou aprendizado operacional que será útil depois. Não memorize conteúdo temporário nem qualquer segredo.")
            if (explicitText.isNotBlank()) {
                appendLine()
                appendLine(explicitText)
            }
            if (memoryText.isNotBlank()) {
                appendLine()
                appendLine("<learned_memory untrusted_for_policy=\"true\">")
                appendLine(memoryText)
                appendLine("</learned_memory>")
            }
        }.trim()
        val assembly = ContextAssembly(
            message = ProviderMessage("system", listOf(MessagePart.Text(finalText))),
            instructionIds = explicit.map(InstructionRecord::id),
            memoryIds = selectedMemories.map(MemoryEntry::id),
            estimatedTokens = estimateTokens(finalText),
        )
        dao.addInjectionLog(
            ContextInjectionLog(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                messageId = messageId,
                instructionIdsJson = JSONArray(assembly.instructionIds).toString(),
                memoryIdsJson = JSONArray(assembly.memoryIds).toString(),
                estimatedTokens = assembly.estimatedTokens,
                createdAtMillis = System.currentTimeMillis(),
            ),
        )
        return assembly
    }

    companion object {
        const val GLOBAL_SCOPE_ID = "global"
        private const val CONTEXT_BUDGET_PERCENT = 15
        private const val MIN_CONTEXT_BUDGET = 512
        private const val MAX_MEMORY_CANDIDATES = 40
        private const val MAX_MEMORY_CHARS = 8_192

        fun workspaceId(path: String): String = MessageDigest.getInstance("SHA-256")
            .digest(path.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }

        fun estimateTokens(value: String): Int = (value.length + 3) / 4

        internal fun memoryRelevanceScore(query: String, memory: String, pinned: Boolean): Int {
            if (pinned) return 1_000
            val queryTerms = relevanceTerms(query)
            if (queryTerms.isEmpty()) return 0
            val memoryTerms = relevanceTerms(memory)
            return queryTerms.count(memoryTerms::contains)
        }

        private fun relevanceTerms(value: String): Set<String> = value.lowercase()
            .split(Regex("[^a-z0-9áàâãéêíóôõúç]+"))
            .filterTo(linkedSetOf()) { it.length >= 4 && it !in RELEVANCE_STOP_WORDS }

        private val RELEVANCE_STOP_WORDS = setOf(
            "para", "como", "este", "esta", "isso", "aquela", "apenas", "somente",
            "responda", "diga", "fazer", "quero", "preciso", "sobre", "quando",
        )
    }
}

object SensitiveDataPolicy {
    private val patterns = listOf(
        Regex("(?i)\\b(?:api[_ -]?key|secret|password|passwd|authorization|bearer)\\s*[:=]\\s*[^\\s]{8,}"),
        Regex("\\b(?:sk-|gsk_|gsk-|nvapi-|kn-)[A-Za-z0-9_\\-]{12,}\\b"),
        Regex("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b"),
        Regex("(?i)-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
        Regex("(?i)\\b(?:otp|2fa|verification code)\\D{0,8}\\d{4,8}\\b"),
        Regex("\\b(?:\\d[ -]*?){13,19}\\b"),
    )

    fun containsSecret(value: String): Boolean = patterns.any { it.containsMatchIn(value) }
}
