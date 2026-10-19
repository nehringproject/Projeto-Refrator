package dev.agentworkbench

import android.content.Context
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * A Bot is a long-lived agent with one clear job (in the spirit of OpenAI Dots / xAI Grok Bots):
 * its own name and avatar, a job description and standing rules that shape every turn, and its
 * own conversation. The phone is its computer (parallel screen + Ubuntu), so nothing runs in a
 * vendor cloud.
 */
data class Bot(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** Short tag shown next to the name in the chat list ("AI", "Bot"…). */
    val label: String = "prisma",
    /** The job: goal, sources, tools and working style. */
    val job: String,
    /** Standing boundaries, e.g. "Nunca envie mensagens sem minha aprovação". */
    val rules: String = "",
    /** Index into [Mascots.all]: the bot's little sticker friend. */
    val mascot: Int = 0,
    /** The bot's own conversation, created on first open. */
    val sessionId: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    /** Whether it may start a chat by itself (see [PrismaAutonomy]). */
    val autonomy: PrismaAutonomy = PrismaAutonomy.ROUTINES,
    /** Most messages per day it may send on its own. */
    val dailyLimit: Int = 3,
    /** Quiet hours (local time, 0..23): no messages from quietFrom until quietUntil. */
    val quietFrom: Int = 22,
    val quietUntil: Int = 7,
    /** Plugin/connection ids this prisma may use; empty = every enabled one. */
    val plugins: List<String> = emptyList(),
    /** Its own provider ([ProviderPreset] name); null = the app's current provider. */
    val providerPreset: String? = null,
    /** Its own model id on that provider; null = the provider's saved/default model. */
    val modelId: String? = null,
) {
    /** True when [hour] falls inside this prisma's quiet hours. */
    fun isQuietAt(hour: Int): Boolean = if (quietFrom == quietUntil) {
        false
    } else if (quietFrom < quietUntil) {
        hour in quietFrom until quietUntil
    } else {
        hour >= quietFrom || hour < quietUntil
    }

    /** Injected into the system context of every turn in this bot's conversation. */
    fun systemPrompt(): String = buildString {
        append("<bot name=\"").append(name.replace("\"", "'")).append("\">\n")
        append("Você é ").append(name).append(", um Prisma do Refrator (um agente com um trabalho fixo). ")
        append("Mantenha o foco nesse trabalho e use as ferramentas do Refrator quando ajudarem.\n")
        append("Trabalho:\n").append(job.trim()).append('\n')
        if (rules.isNotBlank()) append("Regras que você sempre segue:\n").append(rules.trim()).append('\n')
        append("</bot>")
    }
}

/** Ready-made starting points; creating from one copies it into an editable bot. */
object BotTemplates {
    val all = listOf(
        Bot(
            id = "template-nova",
            name = "Nova",
            label = "assistente",
            job = "Assistente geral: responde perguntas, cria ideias, escreve textos e organiza informações " +
                "de forma clara e direta.",
            mascot = 21,
        ),
        Bot(
            id = "template-code",
            name = "Code Assist",
            job = "Engenheiro de software: lê e altera o código em /workspace, roda comandos no terminal e no " +
                "Ubuntu, escreve testes e explica as mudanças antes de aplicá-las.",
            rules = "Mostre o plano antes de mudanças grandes. Não apague arquivos sem confirmar.",
            mascot = 19,
        ),
        Bot(
            id = "template-planner",
            name = "Planejador",
            label = "assistente",
            job = "Planeja semanas, estudos e projetos: quebra objetivos em etapas realistas com prazos e " +
                "revisa o progresso quando eu pedir.",
            mascot = 7,
        ),
        Bot(
            id = "template-operator",
            name = "Operador",
            job = "Opera o celular por mim na tela paralela: abre apps, preenche campos, lê a tela e conclui " +
                "tarefas sem mexer na minha tela.",
            rules = "Nunca envie mensagens, faça compras ou mude contas sem minha aprovação explícita.",
            mascot = 16,
        ),
        Bot(
            id = "template-summary",
            name = "Resumo Rápido",
            label = "assistente",
            job = "Faz resumos curtos e objetivos de textos, notícias, documentos e notificações, destacando " +
                "o que exige ação.",
            mascot = 2,
        ),
    )

    fun instantiate(template: Bot): Bot = template.copy(
        id = UUID.randomUUID().toString(),
        sessionId = null,
        createdAtMillis = System.currentTimeMillis(),
    )
}

/** Bots live in a small JSON file (written atomically); no database migration needed. */
class BotRepository(context: Context) {
    private val file = File(context.applicationContext.filesDir, "bots.json")

    @Synchronized
    fun list(): List<Bot> = runCatching { readAll() }.getOrDefault(emptyList())

    /**
     * Strict read used before writing: a malformed entry is skipped, but an unreadable file
     * throws, so a save can never silently replace every prisma with just one.
     */
    private fun readAll(): List<Bot> {
        if (!file.isFile) return emptyList()
        val array = JSONArray(file.readText())
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { runCatching { decode(it) }.getOrNull() }
        }
    }

    fun bySession(sessionId: String?): Bot? = sessionId?.let { id -> list().firstOrNull { it.sessionId == id } }

    fun byId(id: String?): Bot? = id?.let { wanted -> list().firstOrNull { it.id == wanted } }

    /** Makes sure the general administrator exists (first launch, or after it was removed). */
    @Synchronized
    fun ensureAdmin(): Bot = list().firstOrNull { it.id == PrismaAdmin.ID }
        ?: PrismaAdmin.default().also(::save)

    @Synchronized
    fun save(bot: Bot) {
        val updated = safeExisting().filterNot { it.id == bot.id } + bot
        write(updated)
    }

    @Synchronized
    fun delete(id: String) {
        write(safeExisting().filterNot { it.id == id })
    }

    /** Current prisms; if the file is corrupt it is kept aside as bots.json.bad instead of overwritten. */
    private fun safeExisting(): List<Bot> = runCatching { readAll() }.getOrElse {
        file.copyTo(File(file.parentFile, "${file.name}.bad"), overwrite = true)
        emptyList()
    }

    private fun write(bots: List<Bot>) {
        val json = JSONArray().apply { bots.forEach { put(encode(it)) } }.toString()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(json)
        check(temporary.renameTo(file)) { "Não foi possível salvar os bots." }
    }

    private fun encode(bot: Bot) = JSONObject()
        .put("id", bot.id)
        .put("name", bot.name)
        .put("label", bot.label)
        .put("job", bot.job)
        .put("rules", bot.rules)
        .put("mascot", bot.mascot)
        .put("session_id", bot.sessionId ?: JSONObject.NULL)
        .put("created_at", bot.createdAtMillis)
        .put("autonomy", bot.autonomy.name)
        .put("daily_limit", bot.dailyLimit)
        .put("quiet_from", bot.quietFrom)
        .put("quiet_until", bot.quietUntil)
        .put("plugins", JSONArray(bot.plugins))
        .put("provider", bot.providerPreset ?: JSONObject.NULL)
        .put("model", bot.modelId ?: JSONObject.NULL)

    private fun decode(json: JSONObject) = Bot(
        id = json.getString("id"),
        name = json.optString("name").ifBlank { "Bot" },
        label = json.optString("label").ifBlank { "prisma" },
        job = json.optString("job"),
        rules = json.optString("rules"),
        // Bots saved before mascots existed only had a palette; spread them over the set.
        mascot = if (json.has("mascot")) json.optInt("mascot") else json.optInt("palette") * 4,
        sessionId = json.optString("session_id").takeIf { it.isNotBlank() && it != "null" },
        createdAtMillis = json.optLong("created_at", System.currentTimeMillis()),
        autonomy = enumOr(json.optString("autonomy"), PrismaAutonomy.ROUTINES),
        dailyLimit = json.optInt("daily_limit", 3).coerceIn(0, 50),
        quietFrom = json.optInt("quiet_from", 22).coerceIn(0, 23),
        quietUntil = json.optInt("quiet_until", 7).coerceIn(0, 23),
        plugins = json.optJSONArray("plugins").strings(),
        providerPreset = json.nullableString("provider"),
        modelId = json.nullableString("model"),
    )
}
