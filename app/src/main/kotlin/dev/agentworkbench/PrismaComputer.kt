package dev.agentworkbench

import android.content.Context
import android.os.Build
import android.util.Base64
import dev.agentworkbench.core.ToolDefinition
import dev.agentworkbench.core.ToolEffect
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Each Prisma's own computer on the phone: its folder, a terminal that keeps its state between
 * calls (an Ubuntu shell through proot in the Dev build, the app's Linux shell otherwise) and,
 * through [PrismaBrowsers], a browser with its own logins.
 *
 * The Ubuntu is shared (one install, packages for everyone); what is each Prisma's own is /root,
 * /tmp and /workspace. At most [MAX_ACTIVE_SHELLS] terminals live at once: the least recently
 * used one sleeps (its process ends, its folder and current directory stay) and wakes on demand.
 */
internal object PrismaComputer {
    const val MAX_ACTIVE_SHELLS = 3
    private const val IDLE_SLEEP_MS = 10 * 60_000L
    private const val OUTPUT_LIMIT = 48_000
    private const val HISTORY_LIMIT = 256_000L
    private const val START_TIMEOUT_MS = 10 * 60_000L

    /** Where a Prisma's computer lives. */
    class Place(val handle: String, val work: File, val home: File, val tmp: File, val teams: File, val history: File, val state: File)

    private class Shell(val botId: String, val place: Place, val process: Process) {
        val writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
        val lines = Channel<String>(Channel.UNLIMITED)
        val mutex = Mutex()

        @Volatile var lastUsed = System.currentTimeMillis()

        @Volatile var busy = false
    }

    private val shells = ConcurrentHashMap<String, Shell>()
    private val startLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reaper: Job? = null

    /** Ids of the Prismas whose terminal is awake now. */
    fun awake(): Set<String> = shells.filterValues { it.process.isAlive }.keys

    fun placeFor(context: Context, bot: Bot): Place {
        val app = context.applicationContext
        val bots = BotRepository(app)
        val handle = Mentions.handles((bots.list() + bot).distinctBy { it.id })[bot.id] ?: bot.id.take(8)
        val workspace = File(app.filesDir, "workspace")
        val own = File(internalRuntimeRoot(app), "agents/$handle")
        return Place(
            handle = handle,
            work = File(workspace, "prismas/$handle").apply { mkdirs() },
            home = File(own, "home").apply { mkdirs() },
            tmp = File(own, "tmp").apply { mkdirs() },
            teams = File(workspace, "times").apply { mkdirs() },
            history = File(own, "history.log"),
            state = File(own, "state.json"),
        )
    }

    // ---- terminal ----

    class ShellResult(val output: String, val exitCode: Int?, val cwd: String?, val timedOut: Boolean, val restarted: Boolean)

    suspend fun run(context: Context, bot: Bot, command: String, timeoutMillis: Long, background: Boolean): ShellResult {
        val app = context.applicationContext
        ensureReaper()
        val shell = shellFor(app, bot)
        return shell.mutex.withLock {
            shell.busy = true
            shell.lastUsed = System.currentTimeMillis()
            try {
                execute(shell, command, timeoutMillis, background)
            } finally {
                shell.busy = false
                shell.lastUsed = System.currentTimeMillis()
            }
        }.also { result -> appendHistory(shell.place, command, result, background) }
    }

    private suspend fun execute(shell: Shell, command: String, timeoutMillis: Long, background: Boolean): ShellResult = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString().replace("-", "").take(10)
        val encoded = Base64.encodeToString(command.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val decode = "eval \"\$(printf '%s' '$encoded' | base64 -d)\""
        val body = if (background) {
            "mkdir -p ~/.jobs; ( $decode ) > ~/.jobs/$id.log 2>&1 < /dev/null & " +
                "echo \"job $id rodando em segundo plano (pid \$!) · acompanhe com: tail -n 40 ~/.jobs/$id.log\""
        } else {
            "{ $decode\n} < /dev/null 2>&1"
        }
        val marker = "__RFX_${id}__"
        shell.writer.write("$body\nprintf '\\n$marker %s %s\\n' \"\$?\" \"\$PWD\"\n")
        shell.writer.flush()
        val output = StringBuilder()
        var exit: Int? = null
        var cwd: String? = null
        val finished = withTimeoutOrNull(timeoutMillis) {
            while (true) {
                val line = shell.lines.receiveCatching().getOrNull() ?: return@withTimeoutOrNull false
                if (line.startsWith(marker)) {
                    val parts = line.removePrefix(marker).trim().split(' ', limit = 2)
                    exit = parts.getOrNull(0)?.toIntOrNull()
                    cwd = parts.getOrNull(1)
                    return@withTimeoutOrNull true
                }
                output.append(line).append('\n')
                if (output.length > OUTPUT_LIMIT * 2) output.delete(0, output.length - OUTPUT_LIMIT)
            }
            true
        }
        var text = output.toString().removeSuffix("\n").removeSuffix("\n")
        if (text.length > OUTPUT_LIMIT) text = "… (início cortado)\n" + text.takeLast(OUTPUT_LIMIT)
        cwd?.let { saveCwd(shell.place, it) }
        when (finished) {
            true -> ShellResult(text, exit, cwd, timedOut = false, restarted = false)
            else -> {
                // Timed out or the shell died: start clean next time (folder and last directory stay).
                stop(shell)
                ShellResult(text, null, null, timedOut = finished == null, restarted = true)
            }
        }
    }

    private suspend fun shellFor(context: Context, bot: Bot): Shell = startLock.withLock {
        shells[bot.id]?.let { if (it.process.isAlive) return it else shells.remove(bot.id) }
        // Keep at most MAX_ACTIVE_SHELLS awake: the least recently used idle one goes to sleep.
        val deadline = System.currentTimeMillis() + 5 * 60_000L
        while (shells.size >= MAX_ACTIVE_SHELLS) {
            shells.entries.removeAll { !it.value.process.isAlive }
            if (shells.size < MAX_ACTIVE_SHELLS) break
            val victim = shells.values.filterNot { it.busy }.minByOrNull { it.lastUsed }
            if (victim != null) {
                stop(victim)
            } else {
                if (System.currentTimeMillis() > deadline) {
                    throw SocietyTools.Invalid("Os $MAX_ACTIVE_SHELLS terminais estão ocupados agora; tente de novo em instantes.")
                }
                delay(1_000)
            }
        }
        start(context, bot).also { shells[bot.id] = it }
    }

    private suspend fun start(context: Context, bot: Bot): Shell = withContext(Dispatchers.IO) {
        val place = placeFor(context, bot)
        val workspace = File(context.filesDir, "workspace")
        val runtime = DistributionBindings.runtimePackBridge(context, internalRuntimeRoot(context), workspace)
        val probe = runtime.commandLine("true")
            ?: throw SocietyTools.Invalid("O Linux do app ainda não está instalado: abra a aba Terminal uma vez para preparar a base.")
        val bash = probe[1]
        val environment = HashMap(System.getenv())
        runtime.configureEnvironment(environment)
        environment["LANG"] = "C.UTF-8"
        environment["TERM"] = "dumb"
        val runtimeHome = File(environment.getValue("HOME")).apply { mkdirs() }
        // Same bundled commands as the user's terminal (refrator-linux, refrator-pkg-install…).
        val shimBin = CliBridgeShims.install(runtimeHome, bash)
        context.assets.list("terminal/bin").orEmpty().forEach { name ->
            File(shimBin, name).apply {
                writeText("#!$bash\n" + context.assets.open("terminal/bin/$name").bufferedReader().use { it.readText() })
                setExecutable(true, true)
            }
        }
        environment["PATH"] = "${shimBin.path}:${environment["PATH"].orEmpty()}"
        environment["REFRATOR_TARGET_SDK"] = context.applicationInfo.targetSdkVersion.toString()
        environment["REFRATOR_WORKSPACE"] = workspace.path
        val ubuntu = context.applicationInfo.targetSdkVersion < Build.VERSION_CODES.Q
        val script = if (ubuntu) {
            environment["REFRATOR_AGENT_HOME"] = place.home.path
            environment["REFRATOR_AGENT_TMP"] = place.tmp.path
            environment["REFRATOR_AGENT_WORK"] = place.work.path
            environment["REFRATOR_AGENT_TEAMS"] = place.teams.path
            "exec refrator-linux shell"
        } else {
            // The protected build cannot run proot: the app's own Linux shell, with the same folders.
            environment["HOME"] = place.home.path
            environment["TMPDIR"] = place.tmp.path
            "cd '${place.work.path}' && exec bash --noprofile --norc"
        }
        val command = runtime.commandLine(script) ?: error("runtime indisponível")
        val process = ProcessBuilder(command)
            .directory(place.work)
            .redirectErrorStream(true)
            .apply { environment().clear(); environment().putAll(environment) }
            .start()
        val shell = Shell(bot.id, place, process)
        Thread({
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { shell.lines.trySend(it) }
            } catch (_: java.io.IOException) {
            } finally {
                shell.lines.close()
            }
        }, "prisma-shell-${place.handle}").apply { isDaemon = true; start() }
        // Wait until the shell answers (the first Ubuntu start can take a while), then go back to
        // the directory it was in before sleeping.
        val back = savedCwd(place)?.let { "cd -- '${it.replace("'", "")}' 2>/dev/null; " }.orEmpty()
        shell.writer.write("${back}printf '\\n__RFX_ready__\\n'\n")
        shell.writer.flush()
        val ready = withTimeoutOrNull(START_TIMEOUT_MS) {
            while (true) {
                val line = shell.lines.receiveCatching().getOrNull() ?: return@withTimeoutOrNull false
                if (line == "__RFX_ready__") return@withTimeoutOrNull true
            }
            false
        }
        if (ready != true) {
            process.destroyForcibly()
            throw SocietyTools.Invalid("O terminal não iniciou. Abra a aba Terminal e rode refrator-linux install para conferir o Ubuntu.")
        }
        shell
    }

    private fun stop(shell: Shell) {
        shells.remove(shell.botId, shell)
        runCatching { shell.writer.close() }
        runCatching { shell.process.destroy() }
        scope.launch {
            delay(1_500)
            if (shell.process.isAlive) shell.process.destroyForcibly()
        }
    }

    /** Puts terminals idle for 10 minutes to sleep, freeing memory. */
    private fun ensureReaper() {
        if (reaper?.isActive == true) return
        reaper = scope.launch {
            while (isActive) {
                delay(60_000)
                val now = System.currentTimeMillis()
                shells.values.filter { !it.busy && now - it.lastUsed > IDLE_SLEEP_MS }.forEach(::stop)
                if (shells.isEmpty()) break
            }
        }
    }

    private fun saveCwd(place: Place, cwd: String) {
        runCatching { place.state.writeText(JSONObject().put("cwd", cwd).toString()) }
    }

    private fun savedCwd(place: Place): String? =
        runCatching { JSONObject(place.state.readText()).optString("cwd").takeIf(String::isNotBlank) }.getOrNull()

    // ---- what the user sees in the Prisma's profile ----

    private fun appendHistory(place: Place, command: String, result: ShellResult, background: Boolean) {
        runCatching {
            val time = SimpleDateFormat("dd/MM HH:mm", Locale.ROOT).format(Date())
            val status = when {
                result.timedOut -> "passou do tempo"
                result.restarted -> "terminal reiniciado"
                background -> "segundo plano"
                else -> "saída ${result.exitCode ?: "?"}"
            }
            val entry = buildString {
                append("[").append(time).append("] ").append(result.cwd ?: "").append(" $ ").append(command.take(2_000)).append('\n')
                val shown = result.output.trim()
                if (shown.isNotEmpty()) append(if (shown.length > 3_000) shown.take(1_200) + "\n…\n" + shown.takeLast(1_500) else shown).append('\n')
                append("(").append(status).append(")\n\n")
            }
            synchronized(this) {
                place.history.appendText(entry)
                if (place.history.length() > HISTORY_LIMIT) {
                    val text = place.history.readText()
                    place.history.writeText(text.takeLast((HISTORY_LIMIT / 2).toInt()).substringAfter("\n\n"))
                }
            }
        }
    }

    /** The most recent commands and outputs, oldest first. */
    fun recentHistory(context: Context, bot: Bot, maxChars: Int = 12_000): String {
        val file = placeFor(context, bot).history
        if (!file.isFile) return ""
        val text = runCatching { file.readText() }.getOrDefault("")
        return if (text.length > maxChars) text.takeLast(maxChars).substringAfter("\n\n") else text
    }

    /** Files of the Prisma's own folder (relative paths, folders first), up to [limit]. */
    fun files(context: Context, bot: Bot, limit: Int = 200): List<Pair<String, Long>> {
        val root = placeFor(context, bot).work
        val out = mutableListOf<Pair<String, Long>>()
        fun visit(dir: File, depth: Int) {
            dir.listFiles().orEmpty()
                .filterNot { it.name.startsWith(".") }
                .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
                .forEach { file ->
                    if (out.size >= limit) return
                    out += file.relativeTo(root).path + (if (file.isDirectory) "/" else "") to (if (file.isDirectory) -1L else file.length())
                    if (file.isDirectory && depth < 2) visit(file, depth + 1)
                }
        }
        visit(root, 0)
        return out
    }

    // ---- tools ----

    private const val SHELL_DESCRIPTION =
        "Roda um comando no SEU computador: um Linux de verdade (Ubuntu, bash) que guarda o estado entre chamadas " +
            "(pasta atual, variáveis, venv ativado). /workspace é a sua pasta (no app aparece como prismas/<você>/), " +
            "/root é a sua home, /times tem as pastas dos times e /sdcard o armazenamento do celular. Instale o que precisar " +
            "(apt install -y, pip, npm): o Ubuntu é compartilhado, então pacotes do apt ficam para todos. Para coisas longas " +
            "(servidores, builds, downloads grandes) use background=true e acompanhe com tail ~/.jobs/<id>.log. " +
            "O terminal dorme após 10 min parado: seus arquivos e a pasta atual ficam, processos em segundo plano param."

    val definitions: List<ToolDefinition> = listOf(
        ToolDefinition(
            "computer_shell",
            SHELL_DESCRIPTION,
            """{"type":"object","properties":{"command":{"type":"string","minLength":1,"maxLength":20000},"timeout_s":{"type":"integer","minimum":5,"maximum":900,"default":120},"background":{"type":"boolean","default":false}},"required":["command"],"additionalProperties":false}""",
        ),
        ToolDefinition(
            "web_open",
            "Abre um endereço no SEU navegador (só seu, com logins próprios, roda escondido) e devolve a página como texto: " +
                "elementos numerados [N] (links, botões, campos) e o texto principal. Muito mais rápido que tela e screenshots.",
            """{"type":"object","properties":{"url":{"type":"string","minLength":4,"maxLength":2000},"max_chars":{"type":"integer","minimum":500,"maximum":20000,"default":5000}},"required":["url"],"additionalProperties":false}""",
        ),
        ToolDefinition(
            "web_read",
            "Lê de novo a página atual do seu navegador como texto com elementos numerados.",
            """{"type":"object","properties":{"max_chars":{"type":"integer","minimum":500,"maximum":20000,"default":5000}},"additionalProperties":false}""",
        ),
        ToolDefinition(
            "web_act",
            "Faz VÁRIAS ações seguidas no seu navegador numa chamada (como uma pessoa) e devolve a página final como texto. " +
                "Passos: {\"click\":N}, {\"fill\":[N,\"texto\"]}, {\"select\":[N,\"opção\"]}, {\"submit\":N} (envia o formulário do campo N), " +
                "{\"scroll\":\"down\"|\"up\"}, {\"back\":true}, {\"open\":\"https://...\"}, {\"wait_ms\":800}. N é o número entre colchetes da última leitura. " +
                "Campos de senha nunca são preenchidos por você: use web_login.",
            """{"type":"object","properties":{"steps":{"type":"array","minItems":1,"maxItems":25,"items":{"type":"object"}},"max_chars":{"type":"integer","minimum":500,"maximum":20000,"default":5000}},"required":["steps"],"additionalProperties":false}""",
        ),
        ToolDefinition(
            "web_login",
            "Pede ao usuário para entrar na conta de um site no SEU navegador: abre a página de login na tela dele, ele digita a senha " +
                "(você nunca vê) e o login fica salvo só no seu navegador. Espera até ele terminar (até 15 min).",
            """{"type":"object","properties":{"url":{"type":"string","minLength":4,"maxLength":2000},"reason":{"type":"string","minLength":1,"maxLength":300}},"required":["url","reason"],"additionalProperties":false}""",
        ),
    )

    val toolNames: Set<String> = definitions.map { it.name }.toSet()

    fun plan(name: String, args: JSONObject): SocietyTools.Plan = when (name) {
        "computer_shell" -> SocietyTools.Plan(ToolEffect.WORKSPACE_MUTATION, "Rodar no seu terminal: ${args.optString("command").take(160)}")
        "web_open" -> SocietyTools.Plan(ToolEffect.READ_ONLY, "Abrir ${args.optString("url").take(120)} no navegador.")
        "web_read" -> SocietyTools.Plan(ToolEffect.READ_ONLY, "Ler a página do navegador.")
        "web_act" -> SocietyTools.Plan(ToolEffect.EXTERNAL_MUTATION, "Agir no navegador (${args.optJSONArray("steps")?.length() ?: 0} passos).")
        "web_login" -> SocietyTools.Plan(ToolEffect.READ_ONLY, "Pedir login em ${args.optString("url").take(120)}.")
        else -> throw SocietyTools.Invalid("Ferramenta desconhecida: $name")
    }

    suspend fun execute(context: Context, name: String, args: JSONObject, callerBotId: String?): String {
        val app = context.applicationContext
        val bots = BotRepository(app)
        val bot = bots.byId(callerBotId) ?: bots.ensureAdmin()
        return when (name) {
            "computer_shell" -> {
                val command = args.optString("command").trim().ifBlank { throw SocietyTools.Invalid("Informe o comando.") }
                val timeout = args.optInt("timeout_s", 120).coerceIn(5, 900) * 1_000L
                val result = run(app, bot, command, timeout, args.optBoolean("background", false))
                JSONObject()
                    .put("output", result.output.ifBlank { "(sem saída)" })
                    .put("exit_code", result.exitCode ?: JSONObject.NULL)
                    .put("cwd", result.cwd ?: JSONObject.NULL)
                    .apply {
                        if (result.timedOut) put("note", "Passou do tempo: o terminal foi reiniciado (arquivos e pasta ficam). Para coisas longas use background=true.")
                        else if (result.restarted) put("note", "O terminal fechou durante o comando e será reaberto na próxima chamada.")
                    }
                    .toString()
            }
            "web_open" -> PrismaBrowsers.open(app, bot, placeFor(app, bot).handle, args.getString("url").trim(), maxChars(args))
            "web_read" -> PrismaBrowsers.read(app, bot, placeFor(app, bot).handle, maxChars(args))
            "web_act" -> PrismaBrowsers.act(app, bot, placeFor(app, bot).handle, args.optJSONArray("steps") ?: throw SocietyTools.Invalid("Informe steps."), maxChars(args))
            "web_login" -> PrismaBrowsers.login(app, bot, placeFor(app, bot).handle, args.getString("url").trim(), args.optString("reason").trim())
            else -> throw SocietyTools.Invalid("Ferramenta desconhecida: $name")
        }
    }

    private fun maxChars(args: JSONObject): Int = args.optInt("max_chars", 5_000).coerceIn(500, 20_000)
}
