package dev.agentworkbench

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.agentworkbench.core.ToolDefinition
import dev.agentworkbench.core.ToolEffect
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tools of the Sociedade Prisma (Maestro organising prismas, routines and groups; prismas asking
 * each other) plus the native phone plugins (WhatsApp, agenda, alarms, settings…). Native tools
 * open the right app with everything filled in: the final tap stays with the user.
 */
internal object SocietyTools {
    class Invalid(message: String) : IllegalArgumentException(message)

    /** effect + human summary for the approval dialog. */
    data class Plan(val effect: ToolEffect, val summary: String)

    private val society = listOf(
        ToolDefinition(
            "society_overview",
            "Mostra a Sociedade Prisma: todos os Prismas (id, nome, trabalho, autonomia), rotinas agendadas e grupos com decisões recentes.",
            "{}",
        ),
        ToolDefinition(
            "prisma_ask",
            "Pergunta algo a outro Prisma e recebe a resposta dele (um Prisma pedindo ajuda ou delegando a outro). Use o nome ou id.",
            """{"type":"object","properties":{"prisma":{"type":"string","minLength":1,"maxLength":80},"message":{"type":"string","minLength":1,"maxLength":4000}},"required":["prisma","message"],"additionalProperties":false}""",
        ),
        ToolDefinition(
            "prisma_create",
            "Cria um novo Prisma (agente) para o usuário com nome, trabalho e regras.",
            """{"type":"object","properties":{"name":{"type":"string","minLength":1,"maxLength":40},"job":{"type":"string","minLength":1,"maxLength":2000},"rules":{"type":"string","maxLength":1000},"autonomy":{"type":"string","enum":["NEVER","ROUTINES","FREE"],"default":"ROUTINES"}},"required":["name","job"],"additionalProperties":false}""",
        ),
        ToolDefinition(
            "routine_create",
            "Agenda uma rotina: no horário, o Prisma manda mensagem primeiro ao usuário (ou roda uma rodada num grupo). days: lista com dom, seg, ter, qua, qui, sex, sab (vazio = todo dia).",
            """{"type":"object","properties":{"prisma":{"type":"string","minLength":1,"maxLength":80},"title":{"type":"string","minLength":1,"maxLength":80},"instruction":{"type":"string","minLength":1,"maxLength":2000},"time":{"type":"string","pattern":"^[0-2]?[0-9]:[0-5][0-9]$"},"days":{"type":"array","items":{"type":"string","enum":["dom","seg","ter","qua","qui","sex","sab"]},"maxItems":7},"group":{"type":"string","maxLength":80}},"required":["prisma","title","instruction","time"],"additionalProperties":false}""",
        ),
        ToolDefinition(
            "routine_delete",
            "Remove uma rotina pelo id (veja society_overview).",
            """{"type":"object","properties":{"id":{"type":"string","minLength":4,"maxLength":80}},"required":["id"],"additionalProperties":false}""",
        ),
        ToolDefinition(
            "group_create",
            "Cria um grupo da Sociedade Prisma com pauta, membros (nomes dos Prismas) e modo; o Maestro modera.",
            """{"type":"object","properties":{"name":{"type":"string","minLength":1,"maxLength":60},"topic":{"type":"string","maxLength":2000},"members":{"type":"array","items":{"type":"string","maxLength":80},"minItems":2,"maxItems":8},"mode":{"type":"string","enum":["CHAT","DEBATE","BRAINSTORM","REVIEW","TASK"],"default":"DEBATE"}},"required":["name","members"],"additionalProperties":false}""",
        ),
        ToolDefinition(
            "group_post",
            "Manda uma mensagem sua num grupo da Sociedade Prisma (pelo nome ou id). kind=announcement vira aviso fixado no topo.",
            """{"type":"object","properties":{"group":{"type":"string","minLength":1,"maxLength":80},"text":{"type":"string","minLength":1,"maxLength":2000},"kind":{"type":"string","enum":["message","announcement"],"default":"message"}},"required":["group","text"],"additionalProperties":false}""",
        ),
        ToolDefinition(
            "private_message",
            "Manda uma mensagem particular ao usuário na sua conversa individual com ele (chega como notificação). Use para algo só dele, fora do grupo.",
            """{"type":"object","properties":{"text":{"type":"string","minLength":1,"maxLength":4000}},"required":["text"],"additionalProperties":false}""",
        ),
        ToolDefinition(
            "task_board",
            "Quadro de tarefas do grupo (o usuário acompanha por ele). action: list, add, update, remove, start (o dono passa a " +
                "trabalhar sozinho em segundo plano, com tela e pasta próprias, até terminar) ou stop. criteria = critério de pronto. " +
                "Use para dividir o trabalho, assumir tarefas e registrar andamento, bloqueios e entregas. " +
                "status: todo, doing, blocked, review, done. owner: nome/@ de um Prisma, \"você\" ou vazio. Dentro de um grupo, group é opcional.",
            """{"type":"object","properties":{"action":{"type":"string","enum":["list","add","update","remove","start","stop"]},"criteria":{"type":"string","maxLength":600},"id":{"type":"string","maxLength":12},"title":{"type":"string","maxLength":160},"owner":{"type":"string","maxLength":80},"status":{"type":"string","enum":["todo","doing","blocked","review","done"]},"note":{"type":"string","maxLength":600},"progress":{"type":"integer","minimum":0,"maximum":100},"group":{"type":"string","maxLength":80}},"required":["action"],"additionalProperties":false}""",
        ),
        ToolDefinition(
            "team_notebook_write",
            "Anota no caderno compartilhado do grupo (todos os membros veem): decisões, padrões combinados, caminhos de arquivos, URLs. kind como no notebook_write.",
            """{"type":"object","properties":{"text":{"type":"string","minLength":1,"maxLength":500},"kind":{"type":"string","enum":["compromisso","fato","preferencia","aprendizado","contexto"],"default":"contexto"},"group":{"type":"string","maxLength":80}},"required":["text"],"additionalProperties":false}""",
        ),
        ToolDefinition(
            "team_notebook_read",
            "Lê o caderno compartilhado do grupo.",
            """{"type":"object","properties":{"group":{"type":"string","maxLength":80}},"additionalProperties":false}""",
        ),
        ToolDefinition(
            "prisma_dm",
            "Manda uma mensagem privada a outro Prisma (só ele vê; o usuário pode ver nos bastidores). Use para combinar detalhes sem poluir o grupo. Ele recebe na hora e pode responder com prisma_dm.",
            """{"type":"object","properties":{"prisma":{"type":"string","minLength":1,"maxLength":80},"text":{"type":"string","minLength":1,"maxLength":3000}},"required":["prisma","text"],"additionalProperties":false}""",
        ),
        ToolDefinition(
            "look_at_image",
            "Mostra para você (modelo com visão) uma imagem do workspace: captura, arte, PNG/JPG/WebP. Use para avaliar visual de verdade.",
            """{"type":"object","properties":{"path":{"type":"string","minLength":1,"maxLength":300}},"required":["path"],"additionalProperties":false}""",
        ),
        ToolDefinition(
            "web_serve",
            "Serve uma pasta ou arquivo do workspace por HTTP local estável (http://127.0.0.1:8780), que continua no ar entre chamadas e com a tela desligada. Use para abrir jogos/páginas no Chrome da tela paralela. Só o próprio celular acessa.",
            """{"type":"object","properties":{"path":{"type":"string","maxLength":300,"description":"Caminho relativo ao workspace, ex.: jogos/meu-jogo/index.html"}},"required":["path"],"additionalProperties":false}""",
        ),
        ToolDefinition(
            "notebook_write",
            "Anota algo durável no seu caderno pessoal (só você e o usuário leem; vai com você para todo lugar). " +
                "kind: compromisso (algo que você ficou de fazer; use due para prazo), fato, preferencia, aprendizado ou contexto. " +
                "place: geral, privado ou o nome de um grupo seu (padrão: onde você está). Algo quase igual já anotado é atualizado, não duplicado.",
            """{"type":"object","properties":{"text":{"type":"string","minLength":1,"maxLength":500},"kind":{"type":"string","enum":["compromisso","fato","preferencia","aprendizado","contexto"],"default":"contexto"},"place":{"type":"string","maxLength":80},"due":{"type":"string","maxLength":16,"description":"Prazo AAAA-MM-DD ou AAAA-MM-DD HH:MM"},"pinned":{"type":"boolean","default":false}},"required":["text"],"additionalProperties":false}""",
        ),
        ToolDefinition(
            "notebook_read",
            "Lê o seu caderno. place: todos, geral, privado ou o nome de um grupo seu. query filtra por palavras. include_done mostra também os feitos.",
            """{"type":"object","properties":{"place":{"type":"string","maxLength":80,"default":"todos"},"query":{"type":"string","maxLength":200},"include_done":{"type":"boolean","default":false}},"additionalProperties":false}""",
        ),
        ToolDefinition(
            "notebook_done",
            "Atualiza uma anotação pelo id entre colchetes: marca como feita (padrão), reabre (reopen=true) ou apaga (delete=true).",
            """{"type":"object","properties":{"id":{"type":"string","minLength":4,"maxLength":40},"reopen":{"type":"boolean","default":false},"delete":{"type":"boolean","default":false}},"required":["id"],"additionalProperties":false}""",
        ),
        ToolDefinition(
            "group_read",
            "Lê as mensagens de um grupo do qual você faz parte, com a ata (resumo do que veio antes), funções e decisões.",
            """{"type":"object","properties":{"group":{"type":"string","minLength":1,"maxLength":80},"last":{"type":"integer","minimum":5,"maximum":120,"default":40}},"required":["group"],"additionalProperties":false}""",
        ),
        ToolDefinition(
            "notify_user",
            "Manda um aviso ao usuário como notificação do celular (lembretes, alertas, comunicados).",
            """{"type":"object","properties":{"title":{"type":"string","minLength":1,"maxLength":80},"text":{"type":"string","minLength":1,"maxLength":900}},"required":["title","text"],"additionalProperties":false}""",
        ),
    )

    /** plugin id → its tools. Offered only while the plugin is active in the store. */
    private val native = mapOf(
        "whatsapp" to listOf(
            ToolDefinition(
                "whatsapp_send",
                "Abre o WhatsApp com a mensagem pronta para o número (com DDI, ex. 5511999998888) ou, sem número, para escolher o contato. O usuário toca em enviar.",
                """{"type":"object","properties":{"phone":{"type":"string","maxLength":24},"text":{"type":"string","minLength":1,"maxLength":4000}},"required":["text"],"additionalProperties":false}""",
            ),
        ),
        "calendar" to listOf(
            ToolDefinition(
                "calendar_list",
                "Lista os próximos compromissos da agenda do celular.",
                """{"type":"object","properties":{"days":{"type":"integer","minimum":1,"maximum":31,"default":2}},"additionalProperties":false}""",
            ),
            ToolDefinition(
                "calendar_add",
                "Abre a agenda com um evento preenchido (início/fim no formato AAAA-MM-DD HH:MM). O usuário confirma.",
                """{"type":"object","properties":{"title":{"type":"string","minLength":1,"maxLength":200},"start":{"type":"string","minLength":10,"maxLength":16},"end":{"type":"string","maxLength":16},"location":{"type":"string","maxLength":200},"description":{"type":"string","maxLength":2000}},"required":["title","start"],"additionalProperties":false}""",
            ),
        ),
        "alarms" to listOf(
            ToolDefinition(
                "alarm_set",
                "Cria um alarme no app Relógio.",
                """{"type":"object","properties":{"hour":{"type":"integer","minimum":0,"maximum":23},"minute":{"type":"integer","minimum":0,"maximum":59},"label":{"type":"string","maxLength":80}},"required":["hour","minute"],"additionalProperties":false}""",
            ),
            ToolDefinition(
                "timer_set",
                "Inicia um timer no app Relógio.",
                """{"type":"object","properties":{"seconds":{"type":"integer","minimum":1,"maximum":86400},"label":{"type":"string","maxLength":80}},"required":["seconds"],"additionalProperties":false}""",
            ),
        ),
        "settings" to listOf(
            ToolDefinition(
                "settings_open",
                "Abre um painel de configurações do Android para o usuário ajustar.",
                """{"type":"object","properties":{"panel":{"type":"string","enum":["wifi","internet","bluetooth","volume","som","bateria","tela","localizacao","notificacoes","apps","dados","modo_aviao","nao_perturbe","data_hora","acessibilidade","geral"]}},"required":["panel"],"additionalProperties":false}""",
            ),
        ),
        "maps" to listOf(
            ToolDefinition(
                "maps_open",
                "Abre o mapa com uma busca ou rota até o destino.",
                """{"type":"object","properties":{"query":{"type":"string","minLength":1,"maxLength":300},"directions":{"type":"boolean","default":false}},"required":["query"],"additionalProperties":false}""",
            ),
        ),
        "sms" to listOf(
            ToolDefinition(
                "sms_draft",
                "Abre o app de SMS com a mensagem pronta para o número. O usuário envia.",
                """{"type":"object","properties":{"phone":{"type":"string","minLength":3,"maxLength":24},"text":{"type":"string","minLength":1,"maxLength":1000}},"required":["phone","text"],"additionalProperties":false}""",
            ),
        ),
        "phone" to listOf(
            ToolDefinition(
                "phone_dial",
                "Abre o discador com o número (não liga sozinho).",
                """{"type":"object","properties":{"phone":{"type":"string","minLength":3,"maxLength":24}},"required":["phone"],"additionalProperties":false}""",
            ),
        ),
        "email" to listOf(
            ToolDefinition(
                "email_compose",
                "Abre o app de e-mail (Gmail etc.) com destinatário, assunto e texto preenchidos. O usuário envia.",
                """{"type":"object","properties":{"to":{"type":"string","maxLength":300},"subject":{"type":"string","maxLength":300},"body":{"type":"string","maxLength":8000}},"additionalProperties":false}""",
            ),
        ),
        "apps" to listOf(
            ToolDefinition(
                "app_open",
                "Abre um app instalado pelo nome (ex.: Spotify, Instagram, Canva).",
                """{"type":"object","properties":{"name":{"type":"string","minLength":1,"maxLength":80}},"required":["name"],"additionalProperties":false}""",
            ),
        ),
    )

    /** Native plugins that start enabled until the user changes anything in the store. */
    private val defaultNative = setOf("whatsapp", "calendar", "alarms", "settings", "notices", "maps", "sms", "phone", "email", "apps")

    val names: Set<String> = (society + native.values.flatten()).map { it.name }.toSet() + PrismaComputer.toolNames

    fun definitions(context: Context): List<ToolDefinition> {
        val enabled = enabledPlugins(context)
        // Every Prisma has its own computer (terminal + browser), whatever plugins are on.
        return society + PrismaComputer.definitions + native.filterKeys { it in enabled }.values.flatten()
    }

    fun enabledPlugins(context: Context): Set<String> {
        val prefs = context.getSharedPreferences("plugins", Context.MODE_PRIVATE)
        return if (prefs.contains("enabled")) prefs.getStringSet("enabled", emptySet()).orEmpty() else defaultNative
    }

    fun plan(name: String, args: JSONObject): Plan = when (name) {
        in PrismaComputer.toolNames -> PrismaComputer.plan(name, args)
        "society_overview" -> Plan(ToolEffect.READ_ONLY, "Ver a Sociedade Prisma.")
        "prisma_ask" -> Plan(ToolEffect.READ_ONLY, "Perguntar a ${args.optString("prisma")}.")
        "notify_user" -> Plan(ToolEffect.READ_ONLY, "Avisar: ${args.optString("title")}.")
        "notebook_write" -> Plan(ToolEffect.READ_ONLY, "Anotar no caderno.")
        "notebook_read" -> Plan(ToolEffect.READ_ONLY, "Ler o caderno.")
        "notebook_done" -> Plan(ToolEffect.READ_ONLY, "Atualizar o caderno.")
        "group_read" -> Plan(ToolEffect.READ_ONLY, "Ler o grupo ${args.optString("group")}.")
        "task_board" -> Plan(ToolEffect.READ_ONLY, "Atualizar o quadro de tarefas.")
        "team_notebook_write" -> Plan(ToolEffect.READ_ONLY, "Anotar no caderno do time.")
        "team_notebook_read" -> Plan(ToolEffect.READ_ONLY, "Ler o caderno do time.")
        "prisma_dm" -> Plan(ToolEffect.READ_ONLY, "Mensagem privada para ${args.optString("prisma")}.")
        "web_serve" -> Plan(ToolEffect.READ_ONLY, "Servir ${args.optString("path")} no servidor local.")
        "look_at_image" -> Plan(ToolEffect.READ_ONLY, "Olhar a imagem ${args.optString("path")}.")
        "group_post" -> Plan(ToolEffect.READ_ONLY, "Publicar no grupo ${args.optString("group")}.")
        "private_message" -> Plan(ToolEffect.READ_ONLY, "Mandar mensagem particular ao usuário.")
        "calendar_list" -> Plan(ToolEffect.READ_ONLY, "Ler os próximos compromissos.")
        "settings_open" -> Plan(ToolEffect.READ_ONLY, "Abrir configurações: ${args.optString("panel")}.")
        "maps_open" -> Plan(ToolEffect.READ_ONLY, "Abrir o mapa: ${args.optString("query")}.")
        "app_open" -> Plan(ToolEffect.READ_ONLY, "Abrir o app ${args.optString("name")}.")
        "prisma_create" -> Plan(ToolEffect.EXTERNAL_MUTATION, "Criar o Prisma ${args.optString("name")}.")
        "routine_create" -> Plan(ToolEffect.EXTERNAL_MUTATION, "Agendar \"${args.optString("title")}\" às ${args.optString("time")}.")
        "routine_delete" -> Plan(ToolEffect.EXTERNAL_MUTATION, "Remover uma rotina.")
        "group_create" -> Plan(ToolEffect.EXTERNAL_MUTATION, "Criar o grupo ${args.optString("name")}.")
        "whatsapp_send" -> Plan(ToolEffect.EXTERNAL_MUTATION, "Preparar mensagem no WhatsApp.")
        "calendar_add" -> Plan(ToolEffect.EXTERNAL_MUTATION, "Criar evento: ${args.optString("title")}.")
        "alarm_set" -> Plan(ToolEffect.EXTERNAL_MUTATION, "Criar alarme às %02d:%02d.".format(args.optInt("hour"), args.optInt("minute")))
        "timer_set" -> Plan(ToolEffect.EXTERNAL_MUTATION, "Iniciar timer de ${args.optInt("seconds")} s.")
        "sms_draft" -> Plan(ToolEffect.EXTERNAL_MUTATION, "Preparar SMS.")
        "phone_dial" -> Plan(ToolEffect.EXTERNAL_MUTATION, "Abrir o discador.")
        "email_compose" -> Plan(ToolEffect.EXTERNAL_MUTATION, "Preparar e-mail.")
        else -> throw Invalid("Ferramenta desconhecida: $name")
    }

    suspend fun execute(
        context: Context,
        activity: Activity?,
        name: String,
        args: JSONObject,
        callerBotId: String? = null,
        /** The conversation the tool runs in: a private session id or "grupo-<groupId>-<botId>". */
        conversationId: String? = null,
    ): String {
        val app = context.applicationContext
        return when (name) {
            in PrismaComputer.toolNames -> PrismaComputer.execute(app, name, args, callerBotId)
            "private_message" -> {
                val bots = BotRepository(app)
                val caller = bots.byId(callerBotId) ?: bots.ensureAdmin()
                val text = args.getString("text").trim()
                val sessionId = PrismaDispatcher.appendMessage(app, caller, text)
                PrismaNotifications.privateMessage(app, caller, text, sessionId)
                ok("Mensagem privada enviada ao usuário na conversa com ${caller.name}.")
            }
            "group_post" -> postToGroup(app, args, callerBotId, conversationId)
            "look_at_image" -> {
                val path = args.getString("path").trim().trimStart('/')
                val file = java.io.File(java.io.File(app.filesDir, "workspace"), path).canonicalFile
                if (!file.isFile || file.extension.lowercase() !in setOf("png", "jpg", "jpeg", "webp")) throw Invalid("Imagem não encontrada no workspace: $path")
                JSONObject().put("path", path).put("bytes", file.length()).toString()
            }
            "task_board", "team_notebook_write", "team_notebook_read", "prisma_dm", "web_serve" ->
                teamTool(app, name, args, callerBotId, conversationId)
            "society_overview" -> overview(app)
            "prisma_ask" -> ask(app, args)
            "prisma_create" -> createPrisma(app, args)
            "routine_create" -> createRoutine(app, args)
            "routine_delete" -> {
                val id = args.getString("id")
                RoutineRepository(app).byId(id) ?: throw Invalid("Rotina não encontrada.")
                RoutineRepository(app).delete(id)
                RoutineScheduler.cancel(app, id)
                ok("Rotina removida.")
            }
            "group_create" -> createGroup(app, args)
            "notebook_write", "notebook_read", "notebook_done", "group_read" -> memoryTool(app, name, args, callerBotId, conversationId)
            "notify_user" -> {
                PrismaNotifier.notify(app, args.getString("title"), args.getString("text"), kind = PrismaNotifications.Kind.ROUTINE)
                ok("Aviso enviado como notificação.")
            }
            "whatsapp_send" -> whatsapp(app, activity, args)
            "calendar_list" -> calendarList(app, activity, args.optInt("days", 2).coerceIn(1, 31))
            "calendar_add" -> calendarAdd(app, activity, args)
            "alarm_set" -> launch(app, activity, Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, args.getInt("hour"))
                putExtra(AlarmClock.EXTRA_MINUTES, args.getInt("minute"))
                args.optString("label").takeIf(String::isNotBlank)?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            }, "Alarme criado no Relógio.")
            "timer_set" -> launch(app, activity, Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, args.getInt("seconds"))
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                args.optString("label").takeIf(String::isNotBlank)?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            }, "Timer iniciado.")
            "settings_open" -> launch(app, activity, settingsIntent(app, args.getString("panel")), "Configurações abertas.")
            "maps_open" -> {
                val query = Uri.encode(args.getString("query"))
                val uri = if (args.optBoolean("directions")) "google.navigation:q=$query" else "geo:0,0?q=$query"
                runCatching { launch(app, activity, Intent(Intent.ACTION_VIEW, Uri.parse(uri)), "Mapa aberto.") }
                    .getOrElse { launch(app, activity, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$query")), "Mapa aberto no navegador.") }
            }
            "sms_draft" -> launch(app, activity, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + digits(args.getString("phone")))).apply {
                putExtra("sms_body", args.getString("text"))
            }, "SMS pronto; o usuário confirma o envio.")
            "phone_dial" -> launch(app, activity, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + digits(args.getString("phone")))), "Discador aberto.")
            "email_compose" -> launch(app, activity, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(args.optString("to")))).apply {
                putExtra(Intent.EXTRA_SUBJECT, args.optString("subject"))
                putExtra(Intent.EXTRA_TEXT, args.optString("body"))
            }, "E-mail pronto; o usuário confirma o envio.")
            "app_open" -> openApp(app, activity, args.getString("name"))
            else -> throw Invalid("Ferramenta desconhecida: $name")
        }
    }

    // --- Sociedade ------------------------------------------------------------------------

    private fun overview(context: Context): String {
        val bots = BotRepository(context)
        bots.ensureAdmin()
        val all = bots.list()
        val groups = GroupRepository(context).also(GroupStore::ensureLoaded).let { GroupStore.groups.value }
        val routines = RoutineRepository(context).list()
        val now = System.currentTimeMillis()
        return JSONObject()
            .put("prismas", JSONArray().apply {
                all.forEach { bot ->
                    put(JSONObject().put("id", bot.id).put("nome", bot.name).put("admin", PrismaAdmin.isAdmin(bot))
                        .put("trabalho", bot.job.take(240)).put("autonomia", bot.autonomy.label))
                }
            })
            .put("rotinas", JSONArray().apply {
                routines.forEach { routine ->
                    put(JSONObject().put("id", routine.id).put("titulo", routine.title)
                        .put("prisma", all.firstOrNull { it.id == routine.botId }?.name ?: routine.botId)
                        .put("quando", routine.scheduleLabel()).put("ativa", routine.enabled)
                        .put("proxima", routine.nextRunAfter(now)?.let { stamp(it) } ?: JSONObject.NULL)
                        .put("ultimo_resultado", routine.lastResult ?: JSONObject.NULL))
                }
            })
            .put("grupos", JSONArray().apply {
                groups.forEach { group ->
                    put(JSONObject().put("id", group.id).put("nome", group.name).put("modo", group.mode.label)
                        .put("membros", JSONArray(group.memberIds.map { id -> all.firstOrNull { it.id == id }?.name ?: id }))
                        .put("rodada", group.round).put("decisoes", JSONArray(group.decisions.takeLast(3))))
                }
            })
            .toString(2)
    }

    private suspend fun ask(context: Context, args: JSONObject): String {
        val target = findBot(context, args.getString("prisma"))
        val answer = PrismaModel.complete(
            context,
            system = target.systemPrompt() + askSocietyMemory(context, target) +
                "\n\nOutro Prisma da Sociedade Prisma está pedindo a sua ajuda. Responda de forma objetiva, em até 150 palavras.",
            user = args.getString("message"),
            bot = target,
        )
        return JSONObject().put("prisma", target.name).put("resposta", answer).toString(2)
    }

    private fun createPrisma(context: Context, args: JSONObject): String {
        val bots = BotRepository(context)
        val name = args.getString("name").trim()
        if (bots.list().any { it.name.equals(name, ignoreCase = true) }) throw Invalid("Já existe um Prisma chamado $name.")
        val used = bots.list().map { it.mascot }.toSet()
        // Every Prisma has its own model from birth: the app's current one, pinned.
        val app = ProviderSettingsRepository(context).load()
        val bot = Bot(
            name = name,
            job = args.getString("job").trim(),
            rules = args.optString("rules").trim(),
            mascot = Mascots.all.indices.firstOrNull { it !in used } ?: Mascots.forKey(name),
            autonomy = enumOr(args.optString("autonomy"), PrismaAutonomy.ROUTINES),
            providerPreset = app.preset.name,
            modelId = app.modelId,
        )
        bots.save(bot)
        RoutineScheduler.scheduleAll(context)
        PrismaChanges.bump()
        return ok("Prisma ${bot.name} criado (id ${bot.id}).")
    }

    private fun createRoutine(context: Context, args: JSONObject): String {
        val bot = findBot(context, args.getString("prisma"))
        val (hour, minute) = args.getString("time").split(':').map { it.trim().toInt() }
        require(hour in 0..23 && minute in 0..59) { "Horário inválido." }
        val dayNames = listOf("dom", "seg", "ter", "qua", "qui", "sex", "sab")
        val days = args.optJSONArray("days").strings().mapNotNull { day -> dayNames.indexOf(day.lowercase(Locale.ROOT)).takeIf { it >= 0 }?.plus(1) }.toSet()
        val group = args.optString("group").takeIf(String::isNotBlank)?.let { findGroup(context, it) }
        val routine = PrismaRoutine(
            botId = bot.id,
            title = args.getString("title").trim(),
            instruction = args.getString("instruction").trim(),
            days = days,
            hour = hour,
            minute = minute,
            groupId = group?.id,
        )
        RoutineRepository(context).save(routine)
        RoutineScheduler.schedule(context, routine)
        PrismaChanges.bump()
        val next = routine.nextRunAfter(System.currentTimeMillis())?.let(::stamp) ?: "—"
        return ok("Rotina \"${routine.title}\" agendada (${routine.scheduleLabel()}), próxima vez: $next. id ${routine.id}")
    }

    private fun createGroup(context: Context, args: JSONObject): String {
        val members = args.optJSONArray("members").strings().map { findBot(context, it) }.filterNot(PrismaAdmin::isAdmin).distinctBy { it.id }
        if (members.size < 2) throw Invalid("Um grupo precisa de pelo menos 2 Prismas (além do Maestro).")
        val repository = GroupRepository(context)
        GroupStore.ensureLoaded(repository)
        val group = PrismaGroup(
            name = args.getString("name").trim(),
            topic = args.optString("topic").trim(),
            memberIds = members.map { it.id },
            mode = enumOr(args.optString("mode"), GroupMode.DEBATE),
            messages = listOf(GroupMessage(authorId = GroupAuthors.SYSTEM, kind = GroupMessageKind.SYSTEM, text = "Grupo criado pelo Maestro. ${members.joinToString(", ") { it.name }} entraram.")),
        )
        GroupStore.put(repository, group)
        return ok("Grupo ${group.name} criado com ${members.joinToString(", ") { it.name }} (id ${group.id}).")
    }

    private fun postToGroup(context: Context, args: JSONObject, callerBotId: String?, conversationId: String?): String {
        val group = findGroup(context, args.getString("group"))
        if (conversationId?.startsWith("grupo-${group.id}-") == true) {
            throw Invalid("Você já está no grupo ${group.name}: sua resposta final já vai para ele. Não publique de novo.")
        }
        val announcement = args.optString("kind", "message") == "announcement"
        val text = args.getString("text").trim()
        val author = BotRepository(context).byId(callerBotId)?.id ?: PrismaAdmin.ID
        GroupStore.update(GroupRepository(context), group.id) { current ->
            current.withMessage(
                GroupMessage(
                    authorId = author,
                    kind = if (announcement) GroupMessageKind.ANNOUNCEMENT else GroupMessageKind.TEXT,
                    text = text,
                ),
            ).copy(pinnedNotice = if (announcement) text else current.pinnedNotice)
        }
        // The rest of the team hears it right away.
        GroupEngine.ensureLive(context, group.id)
        return ok("Publicado no grupo ${group.name}.")
    }

    private fun findBot(context: Context, key: String): Bot {
        val bots = BotRepository(context)
        bots.ensureAdmin()
        val wanted = key.trim().removePrefix("@")
        return bots.list().firstOrNull { it.id == wanted || it.name.equals(wanted, ignoreCase = true) }
            ?: bots.list().firstOrNull { it.name.contains(wanted, ignoreCase = true) }
            ?: throw Invalid("Prisma \"$key\" não encontrado. Use society_overview para ver os nomes.")
    }

    private fun findGroup(context: Context, key: String): PrismaGroup {
        val repository = GroupRepository(context)
        GroupStore.ensureLoaded(repository)
        val groups = GroupStore.groups.value
        return groups.firstOrNull { it.id == key || it.name.equals(key.trim(), ignoreCase = true) }
            ?: groups.firstOrNull { it.name.contains(key.trim(), ignoreCase = true) }
            ?: throw Invalid("Grupo \"$key\" não encontrado.")
    }

    // --- The team: board, shared notebook, private messages, stable web server ------------

    /** The group a tool call is about: the group it runs in, or the one named in `group`. */
    private fun teamGroup(context: Context, args: JSONObject, conversationId: String?): PrismaGroup {
        val repository = GroupRepository(context)
        GroupStore.ensureLoaded(repository)
        conversationId?.takeIf { it.startsWith("grupo-") }?.let { id ->
            GroupStore.groups.value.firstOrNull { id.startsWith("grupo-${it.id}-") }?.let { return it }
        }
        val named = args.optString("group").takeIf(String::isNotBlank) ?: throw Invalid("Diga de qual grupo (parâmetro group).")
        return findGroup(context, named)
    }

    private fun teamTool(context: Context, name: String, args: JSONObject, callerBotId: String?, conversationId: String?): String {
        val bots = BotRepository(context)
        val me = bots.byId(callerBotId)
        return when (name) {
            "task_board" -> {
                val group = teamGroup(context, args, conversationId)
                val participants = group.memberIds.mapNotNull(bots::byId) + bots.ensureAdmin()
                fun owner(raw: String): String? {
                    val value = raw.trim().removePrefix("@")
                    if (value.isBlank()) return null
                    if (Mentions.mentionsUser("@$value") || value.equals("usuario", true)) return GroupAuthors.USER
                    val handles = Mentions.handles(participants)
                    return participants.firstOrNull { handles[it.id] == Mentions.normalize(value) || it.name.equals(value, ignoreCase = true) }?.id
                        ?: participants.firstOrNull { it.name.contains(value, ignoreCase = true) }?.id
                        ?: throw Invalid("Não achei \"$raw\" no grupo.")
                }
                fun status(raw: String) = when (raw.lowercase(Locale.ROOT)) {
                    "doing" -> TaskStatus.DOING
                    "blocked" -> TaskStatus.BLOCKED
                    "review" -> TaskStatus.REVIEW
                    "done" -> TaskStatus.DONE
                    else -> TaskStatus.TODO
                }
                val repository = GroupRepository(context)
                val now = System.currentTimeMillis()
                when (args.getString("action")) {
                    "list" -> Unit
                    "add" -> {
                        val title = args.optString("title").trim().takeIf(String::isNotBlank) ?: throw Invalid("Dê um título à tarefa.")
                        val task = GroupTask(
                            title = title.take(160),
                            ownerId = args.optString("owner").takeIf(String::isNotBlank)?.let(::owner) ?: me?.id,
                            status = args.optString("status").takeIf(String::isNotBlank)?.let(::status) ?: TaskStatus.TODO,
                            note = args.optString("note").take(600),
                            progress = if (args.has("progress")) args.optInt("progress").coerceIn(0, 100) else null,
                            criteria = args.optString("criteria").take(600),
                        )
                        GroupStore.update(repository, group.id) { it.copy(tasks = it.tasks + task) }
                    }
                    "update" -> {
                        val id = args.optString("id").trim().trim('[', ']').takeIf(String::isNotBlank) ?: throw Invalid("Informe o id da tarefa.")
                        if (group.tasks.none { it.id == id }) throw Invalid("Tarefa $id não existe no quadro.")
                        GroupStore.update(repository, group.id) { current ->
                            current.copy(tasks = current.tasks.map { task ->
                                if (task.id != id) task else task.copy(
                                    title = args.optString("title").trim().takeIf(String::isNotBlank)?.take(160) ?: task.title,
                                    ownerId = if (args.has("owner")) owner(args.optString("owner")) else task.ownerId,
                                    status = args.optString("status").takeIf(String::isNotBlank)?.let(::status) ?: task.status,
                                    note = if (args.has("note")) args.optString("note").take(600) else task.note,
                                    progress = if (args.has("progress")) args.optInt("progress").coerceIn(0, 100) else task.progress,
                                    criteria = if (args.has("criteria")) args.optString("criteria").take(600) else task.criteria,
                                    updatedAtMillis = now,
                                )
                            })
                        }
                    }
                    "remove" -> {
                        val id = args.optString("id").trim().trim('[', ']')
                        GroupStore.update(repository, group.id) { current -> current.copy(tasks = current.tasks.filterNot { it.id == id }) }
                    }
                    "start", "stop" -> {
                        val id = args.optString("id").trim().trim('[', ']').takeIf(String::isNotBlank) ?: throw Invalid("Informe o id da tarefa.")
                        val task = group.tasks.firstOrNull { it.id == id } ?: throw Invalid("Tarefa $id não existe no quadro.")
                        if (task.ownerId == null || task.ownerId == GroupAuthors.USER) throw Invalid("Dê um dono (um Prisma) à tarefa antes de iniciar.")
                        if (args.getString("action") == "start") MissionEngine.start(context, group.id, id) else MissionEngine.stop(context, group.id, id)
                    }
                    else -> throw Invalid("Ação desconhecida.")
                }
                val names = participants.associate { it.id to it.name }
                val board = GroupStore.get(group.id)?.tasks.orEmpty()
                if (board.isEmpty()) "(quadro vazio)" else board.joinToString("\n") { task ->
                    "- [${task.id}] ${task.title} — ${names[task.ownerId] ?: if (task.ownerId == GroupAuthors.USER) "usuário" else "sem dono"} " +
                        "(${task.status.label}${task.progress?.let { ", $it%" }.orEmpty()})${task.note.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}"
                }
            }
            "team_notebook_write" -> {
                val group = teamGroup(context, args, conversationId)
                val kind = when (args.optString("kind").lowercase(Locale.ROOT)) {
                    "compromisso" -> NoteKind.TASK
                    "fato" -> NoteKind.FACT
                    "preferencia", "preferência" -> NoteKind.PREFERENCE
                    "aprendizado" -> NoteKind.LEARNING
                    else -> NoteKind.CONTEXT
                }
                when (val result = PrismaNotebook(context).add(
                    teamNotebookId(group.id),
                    NoteDraft(kind, args.getString("text"), NotebookLogic.PLACE_GENERAL, source = me?.name ?: "prisma"),
                )) {
                    is NoteWrite.Added -> ok("Anotado no caderno do time (id ${result.note.id}).")
                    is NoteWrite.Merged -> ok("Já havia algo parecido; atualizei a anotação ${result.note.id}.")
                    is NoteWrite.Rejected -> throw Invalid(result.reason)
                }
            }
            "team_notebook_read" -> {
                val group = teamGroup(context, args, conversationId)
                NotebookLogic.render(PrismaNotebook(context).state(teamNotebookId(group.id)).notes.takeLast(80)).ifBlank { "(caderno do time vazio)" }
            }
            "prisma_dm" -> {
                val sender = me ?: throw Invalid("Só um Prisma manda mensagem privada a outro.")
                val target = findBot(context, args.getString("prisma"))
                if (target.id == sender.id) throw Invalid("Essa é você mesmo.")
                val groupId = conversationId?.takeIf { it.startsWith("grupo-") }?.let { id ->
                    GroupStore.groups.value.firstOrNull { id.startsWith("grupo-${it.id}-") }?.id
                }
                PrismaDms.send(context, PrismaDm(fromId = sender.id, toId = target.id, text = args.getString("text").trim(), groupId = groupId))
                // Wake the target wherever it is listening.
                groupId?.let { GroupEngine.ensureLive(context, it) }
                ok("Mensagem privada entregue a ${target.name}.")
            }
            "web_serve" -> {
                val path = args.getString("path").trim().trimStart('/')
                val file = java.io.File(java.io.File(context.filesDir, "workspace"), path).canonicalFile
                if (!file.exists()) throw Invalid("Não existe no workspace: $path")
                val url = LocalWebServer.serve(context, path + if (file.isDirectory && !path.endsWith("/")) "/" else "")
                teamGroupOrNull(context, conversationId)?.let {
                    GroupEngine.environmentNotice(context, "🌐 Servidor local estável no ar: $url (continua com a tela desligada).", it.id)
                }
                ok("Servindo em $url — abra esse endereço no Chrome (inclusive na tela paralela).")
            }
            else -> throw Invalid("Ferramenta desconhecida: $name")
        }
    }

    private fun teamGroupOrNull(context: Context, conversationId: String?): PrismaGroup? =
        conversationId?.takeIf { it.startsWith("grupo-") }?.let { id ->
            GroupStore.groups.value.firstOrNull { id.startsWith("grupo-${it.id}-") }
        }

    // --- Memory: the prisma's notebook and its groups --------------------------------------

    private fun memoryTool(context: Context, name: String, args: JSONObject, callerBotId: String?, conversationId: String?): String {
        val bots = BotRepository(context)
        val me = bots.byId(callerBotId) ?: throw Invalid("Só um Prisma tem caderno; abra a conversa de um Prisma.")
        val notebook = PrismaNotebook(context)
        val myGroups = groupsVisibleTo(context, me)
        val groupNames = myGroups.associate { it.id to it.name }
        // Where the prisma is right now: one of its groups, or its private chat.
        val here = conversationId?.takeIf { it.startsWith("grupo-") }
            ?.let { id -> myGroups.firstOrNull { id.startsWith("grupo-${it.id}-") } }
            ?.let { NotebookLogic.groupPlace(it.id) }
            ?: NotebookLogic.PLACE_PRIVATE
        fun findGroup(raw: String): PrismaGroup? {
            val value = raw.trim().lowercase(Locale.ROOT)
            return myGroups.firstOrNull { it.name.lowercase(Locale.ROOT) == value || it.id.startsWith(value) }
                ?: myGroups.firstOrNull { it.name.lowercase(Locale.ROOT).contains(value) }
        }
        fun placeFor(raw: String?): String = when (val value = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()) {
            "" -> here
            "geral" -> NotebookLogic.PLACE_GENERAL
            "privado", "particular" -> NotebookLogic.PLACE_PRIVATE
            else -> findGroup(value)?.let { NotebookLogic.groupPlace(it.id) }
                ?: throw Invalid("Você não está num grupo chamado \"$raw\". Seus grupos: ${myGroups.joinToString { it.name }.ifBlank { "nenhum" }}.")
        }
        return when (name) {
            "notebook_write" -> {
                val kind = when (args.optString("kind").lowercase(Locale.ROOT)) {
                    "compromisso", "tarefa" -> NoteKind.TASK
                    "fato" -> NoteKind.FACT
                    "preferencia", "preferência" -> NoteKind.PREFERENCE
                    "aprendizado" -> NoteKind.LEARNING
                    else -> NoteKind.CONTEXT
                }
                val due = args.optString("due").takeIf(String::isNotBlank)?.let(::parseTime)
                val result = notebook.add(
                    me.id,
                    NoteDraft(
                        kind = kind,
                        text = args.getString("text"),
                        place = placeFor(args.optString("place").takeIf { args.has("place") }),
                        source = here,
                        dueAtMillis = due,
                        pinned = args.optBoolean("pinned"),
                    ),
                )
                when (result) {
                    is NoteWrite.Added -> ok("Anotado (${result.note.kind.prompt}, id ${result.note.id}).")
                    is NoteWrite.Merged -> ok("Já havia algo parecido; atualizei a anotação ${result.note.id}.")
                    is NoteWrite.Rejected -> throw Invalid(result.reason)
                }
            }
            "notebook_read" -> {
                val raw = args.optString("place", "todos")
                val includeDone = args.optBoolean("include_done")
                val query = args.optString("query").trim()
                val state = notebook.state(me.id)
                val notes = state.notes
                    .filter { raw.isBlank() || raw.equals("todos", ignoreCase = true) || it.place == placeFor(raw) }
                    .filter { includeDone || it.status == NoteStatus.OPEN }
                    .filter { query.isBlank() || it.text.contains(query, ignoreCase = true) || NotebookLogic.similarity(it.text, query) > 0.2 }
                    .sortedByDescending { it.updatedAtMillis }
                    .take(60)
                buildString {
                    append(NotebookLogic.render(notes, groupNames).ifBlank { "(nada encontrado no caderno)" })
                    state.privateSummary?.takeIf { raw.isBlank() || raw.equals("todos", true) || raw.equals("privado", true) }?.let {
                        append("\n\nResumo do privado (escrito pelo app):\n").append(it.text)
                    }
                }
            }
            "notebook_done" -> {
                val id = args.getString("id").trim().trim('[', ']')
                val changed = when {
                    args.optBoolean("delete") -> notebook.delete(me.id, id)
                    args.optBoolean("reopen") -> notebook.update(me.id, id) { it.copy(status = NoteStatus.OPEN) }
                    else -> notebook.update(me.id, id) { it.copy(status = NoteStatus.DONE) }
                }
                if (!changed) throw Invalid("Anotação $id não encontrada no seu caderno.")
                ok(
                    when {
                        args.optBoolean("delete") -> "Anotação apagada."
                        args.optBoolean("reopen") -> "Anotação reaberta."
                        else -> "Marcada como feita."
                    },
                )
            }
            "group_read" -> {
                val group = findGroup(args.getString("group"))
                    ?: throw Invalid("Você não faz parte de um grupo \"${args.getString("group")}\". Seus grupos: ${myGroups.joinToString { it.name }.ifBlank { "nenhum" }}.")
                val names = bots.list().associate { it.id to it.name } + (GroupAuthors.USER to "Usuário")
                val last = args.optInt("last", 40).coerceIn(5, 120)
                buildString {
                    append("Grupo ").append(group.name).append('\n')
                    if (group.topic.isNotBlank()) append("Objetivo: ").append(group.topic).append('\n')
                    if (group.roles.isNotEmpty()) append("Funções: ").append(group.roles.entries.joinToString("; ") { "${names[it.key] ?: it.key}: ${it.value}" }).append('\n')
                    group.pinnedNotice?.let { append("Aviso fixado: ").append(it).append('\n') }
                    if (group.memory.isNotBlank()) append("Ata (antes das mensagens abaixo): ").append(group.memory).append('\n')
                    if (group.decisions.isNotEmpty()) append("Decisões: ").append(group.decisions.takeLast(5).joinToString(" | ")).append('\n')
                    append("Mensagens:\n")
                    group.messages.filter { !it.streaming && it.text.isNotBlank() && it.kind != GroupMessageKind.SYSTEM }.takeLast(last).forEach { message ->
                        append('[').append(stamp(message.createdAtMillis)).append("] ")
                            .append(names[message.authorId] ?: "Prisma").append(": ").append(message.text.take(1_200)).append('\n')
                    }
                }
            }
            else -> throw Invalid("Ferramenta desconhecida: $name")
        }
    }

    // --- Native plugins -------------------------------------------------------------------

    private fun whatsapp(context: Context, activity: Activity?, args: JSONObject): String {
        val text = args.getString("text")
        val phone = digits(args.optString("phone"))
        val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { installed(context, it) }
            ?: throw Invalid("WhatsApp não está instalado.")
        val intent = if (phone.length >= 8) {
            Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$phone?text=" + Uri.encode(text))).setPackage(pkg)
        } else {
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).setPackage(pkg)
        }
        return launch(context, activity, intent, "WhatsApp aberto com a mensagem pronta; o usuário toca em enviar.")
    }

    private fun calendarList(context: Context, activity: Activity?, days: Int): String {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            activity?.requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 7_301)
            throw Invalid("Preciso de acesso à agenda: pedi a permissão ao usuário; tente de novo depois que ele permitir.")
        }
        val start = System.currentTimeMillis()
        val end = start + days * 86_400_000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, start)
            android.content.ContentUris.appendId(it, end)
        }.build()
        val events = JSONArray()
        context.contentResolver.query(
            uri,
            arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY),
            null,
            null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext() && events.length() < 60) {
                events.put(
                    JSONObject()
                        .put("titulo", cursor.getString(0) ?: "(sem título)")
                        .put("inicio", stamp(cursor.getLong(1)))
                        .put("fim", stamp(cursor.getLong(2)))
                        .put("local", cursor.getString(3) ?: "")
                        .put("dia_inteiro", cursor.getInt(4) == 1),
                )
            }
        }
        return JSONObject().put("de", stamp(start)).put("ate", stamp(end)).put("eventos", events).toString(2)
    }

    private fun calendarAdd(context: Context, activity: Activity?, args: JSONObject): String {
        val start = parseTime(args.getString("start"))
        val end = args.optString("end").takeIf(String::isNotBlank)?.let(::parseTime) ?: (start + 60 * 60_000L)
        val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
            putExtra(CalendarContract.Events.TITLE, args.getString("title"))
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
            args.optString("location").takeIf(String::isNotBlank)?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
            args.optString("description").takeIf(String::isNotBlank)?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
        }
        return launch(context, activity, intent, "Agenda aberta com o evento preenchido; o usuário salva.")
    }

    private fun settingsIntent(context: Context, panel: String): Intent {
        val panelApi = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val action = when (panel) {
            "wifi" -> if (panelApi) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
            "internet" -> if (panelApi) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIRELESS_SETTINGS
            "volume", "som" -> if (panel == "volume" && panelApi) Settings.Panel.ACTION_VOLUME else Settings.ACTION_SOUND_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "bateria" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "tela" -> Settings.ACTION_DISPLAY_SETTINGS
            "localizacao" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "notificacoes" -> return Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "dados" -> Settings.ACTION_DATA_ROAMING_SETTINGS
            "modo_aviao" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "nao_perturbe" -> "android.settings.ZEN_MODE_SETTINGS"
            "data_hora" -> Settings.ACTION_DATE_SETTINGS
            "acessibilidade" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return Intent(action)
    }

    private fun openApp(context: Context, activity: Activity?, name: String): String {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
        val wanted = name.trim().lowercase(Locale.ROOT)
        val match = apps.firstOrNull { it.loadLabel(pm).toString().lowercase(Locale.ROOT) == wanted }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase(Locale.ROOT).contains(wanted) }
            ?: throw Invalid("Não achei um app chamado \"$name\".")
        val intent = pm.getLaunchIntentForPackage(match.activityInfo.packageName) ?: throw Invalid("Esse app não pode ser aberto.")
        return launch(context, activity, intent, "Abri ${match.loadLabel(pm)}.")
    }

    private fun launch(context: Context, activity: Activity?, intent: Intent, done: String): String {
        try {
            if (activity != null) {
                activity.startActivity(intent)
            } else {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (_: ActivityNotFoundException) {
            throw Invalid("Nenhum app do celular sabe abrir isso.")
        }
        return ok(done)
    }

    private fun installed(context: Context, pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    private fun digits(value: String): String = value.filter(Char::isDigit)

    private fun parseTime(value: String): Long {
        val text = value.trim().replace('T', ' ')
        val format = if (text.length <= 10) "yyyy-MM-dd" else "yyyy-MM-dd HH:mm"
        return SimpleDateFormat(format, Locale.ROOT).parse(text)?.time ?: throw Invalid("Data inválida: $value (use AAAA-MM-DD HH:MM).")
    }

    private fun stamp(millis: Long): String = SimpleDateFormat("EEE dd/MM HH:mm", Locale("pt", "BR")).format(Date(millis))

    private fun ok(message: String) = JSONObject().put("ok", true).put("resultado", message).toString(2)
}

/** Bumped when tools change prismas or routines, so open screens can reload. */
internal object PrismaChanges {
    var version by mutableStateOf(0)
    fun bump() { version++ }
}

/** Extra system context for a prisma: who else lives in the Sociedade Prisma (more detail for Maestro). */
internal fun societyContext(context: Context, bot: Bot): String {
    val others = runCatching { BotRepository(context).list() }.getOrDefault(emptyList()).filterNot { it.id == bot.id }
    return buildString {
        append("\n\n<sociedade_prisma>\n")
        if (PrismaAdmin.isAdmin(bot)) {
            append("Você é o administrador geral da Sociedade Prisma. Prismas: ")
            append(others.joinToString("; ") { "${it.name} (${it.job.take(80)})" }.ifBlank { "nenhum ainda" })
            append(".\nUse society_overview para ver rotinas e grupos; prisma_create, routine_create, group_create e group_post para organizar; ")
            append("prisma_ask para consultar um Prisma; notify_user para avisos. Peça confirmação antes de criar ou apagar coisas.\n")
        } else {
            append("Você vive na Sociedade Prisma com: ").append(others.joinToString(", ") { it.name }.ifBlank { "o Maestro" })
            append(". O Maestro é o administrador. Use prisma_ask para pedir ajuda a outro Prisma quando ele souber mais.\n")
        }
        val calendar = Calendar.getInstance()
        append("Agora: ").append(SimpleDateFormat("EEEE, dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(calendar.time)).append('\n')
        append("Você está na sua conversa privada com o usuário.\n")
        append(runCatching { privateSocietyMemory(context, bot) }.getOrDefault(""))
        append("\n</sociedade_prisma>")
    }
}
