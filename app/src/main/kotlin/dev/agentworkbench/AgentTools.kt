package dev.agentworkbench

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Build
import android.os.StatFs
import androidx.javascriptengine.JavaScriptSandbox
import com.googlecode.tesseract.android.TessBaseAPI
import dev.agentworkbench.core.ApprovalAuthority
import dev.agentworkbench.core.ApprovalChallenge
import dev.agentworkbench.core.Capability
import dev.agentworkbench.core.CommandSpec
import dev.agentworkbench.core.ConfirmationMethod
import dev.agentworkbench.core.DistributionProfile
import dev.agentworkbench.core.EnvironmentTrust
import dev.agentworkbench.core.ExecutionMode
import dev.agentworkbench.core.ExecutionPermit
import dev.agentworkbench.core.ExecutionRunner
import dev.agentworkbench.core.MessagePart
import dev.agentworkbench.core.PermitResult
import dev.agentworkbench.core.PolicyContext
import dev.agentworkbench.core.PolicyDecision
import dev.agentworkbench.core.PolicyEngine
import dev.agentworkbench.core.ProviderMessage
import dev.agentworkbench.core.ProviderToolCall
import dev.agentworkbench.core.RunnerEvent
import dev.agentworkbench.core.ToolDefinition
import dev.agentworkbench.core.ToolEffect
import dev.agentworkbench.core.ToolRequest
import dev.agentworkbench.core.fingerprint
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.DigestOutputStream
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.errors.NoWorkTreeException
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

data class AgentToolInvocation(
    val callId: String,
    val toolName: String,
    val argumentsJson: String,
)

data class AgentToolResult(
    val callId: String,
    val toolName: String,
    val payload: String,
    val isError: Boolean,
) {
    fun providerMessage(): ProviderMessage = ProviderMessage(
        role = "tool",
        parts = listOf(
            MessagePart.ToolResult(
                callId = callId,
                payload = payload,
                isError = isError,
            ),
        ),
        toolCallId = callId,
    )
}

data class PreparedAgentTool(
    val invocation: AgentToolInvocation,
    val arguments: JSONObject,
    val request: ToolRequest,
    val decision: PolicyDecision,
    val challenge: ApprovalChallenge?,
    val command: CommandSpec? = null,
) {
    val requiresApproval: Boolean
        get() = decision is PolicyDecision.Ask
}

sealed interface AgentToolPreparation {
    data class Ready(val prepared: PreparedAgentTool) : AgentToolPreparation
    data class Rejected(val result: AgentToolResult) : AgentToolPreparation
}

class AgentToolbox(
    private val appContext: Context,
    private val profile: DistributionProfile,
    private val executionMode: ExecutionMode,
    workspaceRoot: File,
    private val activity: Activity? = appContext as? Activity,
    private val conversationId: String? = null,
) {
    private val workspace = workspaceRoot.canonicalFile
    private val contextMemory = ContextMemoryRepository(appContext)
    private val executionRepository = ExecutionRepository(appContext)
    private val workbenchDao = WorkbenchDatabase.get(appContext).dao()
    private val agentPlatform = AgentPlatformRepository(appContext)
    private val hookEngine = HookEngine(agentPlatform)
    private val workspaceId = ContextMemoryRepository.workspaceId(workspace.absolutePath)
    private val approvals = ApprovalAuthority()
    private val policy = PolicyEngine()
    private val powerRunner: ExecutionRunner? = DistributionBindings
        .runners(workspace)
        .firstOrNull { it.descriptor.id == "power-android-shell" }
    private val privilegedShell = DistributionBindings.privilegedShellBridge(appContext)
    private val shadowDisplay = DistributionBindings.shadowDisplayBridge(appContext)
    private val termuxBridge = DistributionBindings.termuxBridge(appContext)
    private val accessibility = DistributionBindings.accessibilityBridge(appContext)
    private val notifications = DistributionBindings.notificationBridge(appContext)
    private val pythonRuntime = DistributionBindings.pythonRuntime(
        appContext,
        workspace,
    )
    private val termuxConfigRepository = TermuxBridgeConfigRepository(appContext)
    private val processManager = AgentProcessManager.get(appContext)
    private val documentTree = DocumentTreeAccess(appContext)
    private val publicDownloads = PublicDownloadsAccess(appContext)
    private val editor = WorkspaceEditor(appContext, workspace)
    private val codeIntelligence = CodeIntelligence(workspace)
    private val browser = AgentBrowserSession.get(activity ?: appContext.applicationContext, workspace)
    private val browserWorkspace = BrowserWorkspaceRepository(appContext)
    private val internalRuntime = InternalRuntime(
        context = appContext,
        workspaceRoot = workspace,
        externalPackagesAllowed = powerRunner != null,
    )

    val definitions: List<ToolDefinition> = buildList {
        add(
            tool(
                "ask_user",
                "Pausa o agente para fazer uma pergunta curta ao usuário. Pode oferecer de 1 a 4 opções e, opcionalmente, aceitar texto livre; continue somente após receber a resposta tool.",
                """{"type":"object","properties":{"question":{"type":"string","minLength":1,"maxLength":500},"options":{"type":"array","items":{"type":"string","minLength":1,"maxLength":100},"maxItems":4},"allow_free_text":{"type":"boolean","default":true}}},"required":["question"],"additionalProperties":false}""",
            ),
        )
        add(tool("memory_list", "Lista memórias aprendidas auditáveis, sem expor segredos.", "{}"))
        add(tool("agent_profiles", "Lista perfis de agentes disponíveis e o perfil selecionado.", "{}"))
        add(tool("mcp_servers", "Lista servidores MCP configurados sem expor credenciais ou variáveis de ambiente.", "{}"))
        add(tool("command_templates", "Lista comandos reutilizáveis e seus esquemas de argumentos.", "{}"))
        add(
            tool(
                "memory_save",
                "Salva uma preferência, decisão, fato estável ou aprendizado operacional. O padrão é exclusivo da conversa; use workspace ou global somente quando o usuário pedir compartilhamento. Segredos, tokens, OTP e dados financeiros são recusados localmente.",
                """{"type":"object","properties":{"scope":{"type":"string","enum":["global","workspace","chat"],"default":"chat"},"category":{"type":"string","enum":["preference","decision","fact","learning"],"default":"learning"},"body":{"type":"string","minLength":1,"maxLength":8192},"confidence":{"type":"number","minimum":0,"maximum":1,"default":0.8}},"required":["body"],"additionalProperties":false}""",
            ),
        )
        add(
            tool(
                "memory_update",
                "Atualiza ou desativa uma memória existente preservando sua revisão anterior.",
                """{"type":"object","properties":{"id":{"type":"string","minLength":8,"maxLength":80},"body":{"type":"string","minLength":1,"maxLength":8192},"state":{"type":"string","enum":["ACTIVE","DISABLED","CONFLICT"],"default":"ACTIVE"},"reason":{"type":"string","minLength":1,"maxLength":240}},"required":["id","body","reason"],"additionalProperties":false}""",
            ),
        )
        add(
            tool(
                "access_matrix",
                "Fonte de verdade sobre os níveis de acesso atuais: sandbox, pasta SAF, Shizuku UID, Termux instalado, bridge de toolchain e root.",
                "{}",
            ),
        )
        add(tool("shadow_display_status", "Mostra o estado da tela Android paralela controlada pelo agente.", "{}"))
            add(tool("shadow_apps", "Lista aplicativos inicializáveis que podem ser abertos na tela paralela.", """{"type":"object","properties":{"query":{"type":"string","maxLength":100,"default":""},"limit":{"type":"integer","minimum":1,"maximum":200,"default":100}},"additionalProperties":false}"""))
            add(tool("shadow_display_start", "Cria uma tela Android paralela via Shizuku sem ocupar a tela física.", """{"type":"object","properties":{"width":{"type":"integer","minimum":360,"maximum":1920,"default":720},"height":{"type":"integer","minimum":640,"maximum":3200,"default":1600},"density_dpi":{"type":"integer","minimum":120,"maximum":640,"default":280}},"additionalProperties":false}"""))
            add(tool("shadow_display_stop", "Fecha a tela paralela e libera GPU, memória e processos visuais.", "{}"))
            add(tool("shadow_launch", "Abre um aplicativo instalado exclusivamente na tela paralela.", """{"type":"object","properties":{"package_name":{"type":"string","minLength":3,"maxLength":200}},"required":["package_name"],"additionalProperties":false}"""))
            add(tool("shadow_screenshot", "Salva o frame atual da tela paralela no workspace; FLAG_SECURE continua oculto.", """{"type":"object","properties":{"path":{"type":"string","default":"shadow/latest.png"}},"additionalProperties":false}"""))
            add(tool("shadow_ui", "Lê janelas e nós de acessibilidade somente do display paralelo, incluindo textos e coordenadas.", """{"type":"object","properties":{"max_nodes":{"type":"integer","minimum":1,"maximum":300,"default":160}},"additionalProperties":false}"""))
            add(tool("shadow_wait", "Aguarda sem consumir provider enquanto o aplicativo continua na tela paralela.", """{"type":"object","properties":{"seconds":{"type":"integer","minimum":1,"maximum":600,"default":10}},"additionalProperties":false}"""))
            add(tool("shadow_tap", "Toca uma coordenada somente na tela paralela.", """{"type":"object","properties":{"x":{"type":"integer","minimum":0},"y":{"type":"integer","minimum":0}},"required":["x","y"],"additionalProperties":false}"""))
            add(tool("shadow_swipe", "Desliza somente na tela paralela.", """{"type":"object","properties":{"x1":{"type":"integer","minimum":0},"y1":{"type":"integer","minimum":0},"x2":{"type":"integer","minimum":0},"y2":{"type":"integer","minimum":0},"duration_ms":{"type":"integer","minimum":50,"maximum":10000,"default":350}},"required":["x1","y1","x2","y2"],"additionalProperties":false}"""))
            add(tool("shadow_text", "Digita texto na tela paralela sem interferir no teclado da tela física.", """{"type":"object","properties":{"text":{"type":"string","minLength":1,"maxLength":500}},"required":["text"],"additionalProperties":false}"""))
        add(tool("shadow_key", "Envia um Android keycode para a tela paralela (por exemplo BACK=4, HOME=3).", """{"type":"object","properties":{"key_code":{"type":"integer","minimum":0,"maximum":1000}},"required":["key_code"],"additionalProperties":false}"""))
        add(tool("android_device_info", "Lê modelo, versão Android, ABI e espaço do sandbox.", "{}"))
        add(
            tool(
                "workspace_list",
                "Lista arquivos e diretórios apenas dentro do workspace privado do app.",
                """{"type":"object","properties":{"path":{"type":"string","default":"."},"depth":{"type":"integer","minimum":0,"maximum":4,"default":2}},"additionalProperties":false}""",
            ),
        )
        add(
            tool(
                "workspace_read",
                "Lê um arquivo UTF-8 do workspace com limite rígido de tamanho.",
                """{"type":"object","properties":{"path":{"type":"string"},"max_bytes":{"type":"integer","minimum":1,"maximum":131072,"default":65536}},"required":["path"],"additionalProperties":false}""",
            ),
        )
        add(
            tool(
                "workspace_search",
                "Busca texto literal em arquivos pequenos do workspace.",
                """{"type":"object","properties":{"query":{"type":"string"},"path":{"type":"string","default":"."},"max_results":{"type":"integer","minimum":1,"maximum":100,"default":30}},"required":["query"],"additionalProperties":false}""",
            ),
        )
        add(
            tool(
                "workspace_write",
                "Cria ou substitui atomicamente um arquivo UTF-8 no workspace após aprovação.",
                """{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"},"overwrite":{"type":"boolean","default":false}},"required":["path","content"],"additionalProperties":false}""",
            ),
        )
        add(tool("external_tree_status", "Mostra a pasta externa concedida pelo seletor oficial do Android e se ela permite leitura/escrita.", "{}"))
        add(
            tool(
                "external_tree_list",
                "Lista arquivos dentro da pasta externa escolhida pelo usuário, sem escapar da árvore SAF.",
                """{"type":"object","properties":{"path":{"type":"string","default":""},"depth":{"type":"integer","minimum":0,"maximum":4,"default":2}},"additionalProperties":false}""",
            ),
        )
        add(
            tool(
                "external_tree_read",
                "Lê um arquivo UTF-8 dentro da pasta externa concedida pelo Android.",
                """{"type":"object","properties":{"path":{"type":"string"},"max_bytes":{"type":"integer","minimum":1,"maximum":131072,"default":65536}},"required":["path"],"additionalProperties":false}""",
            ),
        )
        add(
            tool(
                "external_tree_write",
                "Cria ou substitui arquivo UTF-8 dentro da pasta externa concedida; cria subpastas quando necessário.",
                """{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"},"overwrite":{"type":"boolean","default":false}},"required":["path","content"],"additionalProperties":false}""",
            ),
        )
        add(tool("git_status", "Mostra branch e alterações do repositório Git do workspace.", "{}"))
        add(
            tool(
                "git_log",
                "Mostra commits recentes do repositório Git do workspace.",
                """{"type":"object","properties":{"max_count":{"type":"integer","minimum":1,"maximum":50,"default":10}},"additionalProperties":false}""",
            ),
        )
        add(tool("git_diff", "Mostra o diff não staged do workspace, limitado a 128 KiB.", "{}"))
        add(tool("git_init", "Inicializa um repositório Git no workspace após aprovação.", "{}"))
        add(
            tool(
                "git_commit",
                "Adiciona todas as mudanças e cria um commit local após aprovação.",
                """{"type":"object","properties":{"message":{"type":"string","minLength":1,"maxLength":200}},"required":["message"],"additionalProperties":false}""",
            ),
        )
        if (activity != null) {
            add(
                tool(
                    "capture_app_screen",
                    "Captura somente a janela do Refrator e salva PNG no workspace. Não captura outros apps.",
                    """{"type":"object","properties":{"name":{"type":"string","default":"agent-screen"}},"additionalProperties":false}""",
                ),
            )
        }
        add(
            tool(
                "ocr_image",
                "Executa OCR Tesseract 5 local em uma imagem do workspace; idiomas eng, por ou eng+por.",
                """{"type":"object","properties":{"path":{"type":"string"},"languages":{"type":"string","enum":["eng","por","eng+por"],"default":"eng+por"}},"required":["path"],"additionalProperties":false}""",
            ),
        )
        add(
            tool(
                "weather_forecast",
                "Busca condições atuais e previsão diária real para uma cidade usando Open-Meteo. Requer aprovação de rede.",
                """{"type":"object","properties":{"location":{"type":"string","minLength":2,"maxLength":120},"days":{"type":"integer","minimum":1,"maximum":7,"default":3}},"required":["location"],"additionalProperties":false}""",
            ),
        )
        add(
            tool(
                "https_fetch",
                "Faz GET HTTPS sem credenciais em APIs públicas permitidas, sem redirects, com timeout e resposta limitada. Hosts: Open-Meteo, GitHub API/raw e DuckDuckGo API.",
                """{"type":"object","properties":{"url":{"type":"string","minLength":8,"maxLength":2048}},"required":["url"],"additionalProperties":false}""",
            ),
        )
        add(
            tool(
                "web_search",
                "Pesquisa a web por palavras-chave e retorna resultados reais com titulo, URL e resumo. Use antes de web_open quando a URL ainda nao for conhecida.",
                """{"type":"object","properties":{"query":{"type":"string","minLength":2,"maxLength":256},"count":{"type":"integer","minimum":1,"maximum":10,"default":5}},"required":["query"],"additionalProperties":false}""",
            ),
        )
        add(
            tool(
                "web_open",
                "Abre uma pagina HTTPS, segue redirects seguros e devolve texto legivel, titulo, URL final e links encontrados.",
                """{"type":"object","properties":{"url":{"type":"string","minLength":8,"maxLength":2048},"max_chars":{"type":"integer","minimum":1000,"maximum":131072,"default":65536}},"required":["url"],"additionalProperties":false}""",
            ),
        )
        add(
                tool(
                    "browser_search",
                    "Pesquisa em um navegador WebView real com JavaScript, cookies e DOM. Retorna texto renderizado e elementos interativos numerados.",
                    """{"type":"object","properties":{"query":{"type":"string","minLength":2,"maxLength":256},"engine":{"type":"string","enum":["duckduckgo","google","brave","bing"],"default":"duckduckgo"},"wait_ms":{"type":"integer","minimum":0,"maximum":15000,"default":2000}},"required":["query"],"additionalProperties":false}""",
                ),
            )
            add(
                tool(
                    "browser_open",
                    "Abre uma URL HTTPS em sessão WebView persistente, executa JavaScript e retorna snapshot do DOM renderizado com IDs clicáveis.",
                    """{"type":"object","properties":{"url":{"type":"string","minLength":8,"maxLength":2048},"wait_ms":{"type":"integer","minimum":0,"maximum":15000,"default":1500}},"required":["url"],"additionalProperties":false}""",
                ),
            )
            add(
                tool(
                    "browser_snapshot",
                    "Lê a página renderizada atual e lista texto, URL, título, rolagem e elementos interativos com IDs estáveis para a página atual.",
                    """{"type":"object","properties":{"max_chars":{"type":"integer","minimum":1000,"maximum":131072,"default":65536},"max_elements":{"type":"integer","minimum":1,"maximum":300,"default":160}},"additionalProperties":false}""",
                ),
            )
            add(tool("browser_click", "Clica um elemento retornado por browser_snapshot. Pode navegar ou alterar estado remoto e exige aprovação conforme a política.", """{"type":"object","properties":{"element_id":{"type":"string","pattern":"^e[1-9][0-9]{0,2}$"},"wait_ms":{"type":"integer","minimum":0,"maximum":15000,"default":1500}},"required":["element_id"],"additionalProperties":false}"""))
            add(tool("browser_type", "Digita em input, textarea ou conteúdo editável da página renderizada; opcionalmente envia o formulário.", """{"type":"object","properties":{"element_id":{"type":"string","pattern":"^e[1-9][0-9]{0,2}$"},"text":{"type":"string","maxLength":16384},"submit":{"type":"boolean","default":false},"wait_ms":{"type":"integer","minimum":0,"maximum":15000,"default":1000}},"required":["element_id","text"],"additionalProperties":false}"""))
            add(tool("browser_scroll", "Rola a página WebView atual e devolve um novo snapshot renderizado.", """{"type":"object","properties":{"delta_y":{"type":"integer","minimum":-10000,"maximum":10000,"default":1200},"wait_ms":{"type":"integer","minimum":0,"maximum":15000,"default":500}},"additionalProperties":false}"""))
            add(tool("browser_back", "Volta uma página no histórico da sessão WebView e devolve novo snapshot.", """{"type":"object","properties":{"wait_ms":{"type":"integer","minimum":0,"maximum":15000,"default":1000}},"additionalProperties":false}"""))
            add(tool("browser_wait", "Aguarda JavaScript, requisições e componentes dinâmicos da página atual antes de criar novo snapshot.", """{"type":"object","properties":{"wait_ms":{"type":"integer","minimum":100,"maximum":15000,"default":2000}},"additionalProperties":false}"""))
            add(tool("browser_screenshot", "Captura o viewport renderizado do navegador em PNG dentro de workspace/browser.", """{"type":"object","properties":{"name":{"type":"string","pattern":"^[A-Za-z0-9._-]{1,64}$","default":"browser-page"}},"additionalProperties":false}"""))
            add(tool("browser_download_status", "Consulta a solicitação de download capturada pelo navegador JavaScript sem expor cookies da sessão.", "{}"))
            add(tool("browser_download_start", "Salva em Downloads a solicitação capturada pelo WebView usando a mesma sessão, cookies, User-Agent e referer. Use após clicar no botão de download da página.", """{"type":"object","properties":{"file_name":{"type":"string","minLength":1,"maxLength":180}},"additionalProperties":false}"""))
            add(tool("browser_open_external", "Abre a página atual no navegador visível para o usuário concluir CAPTCHA, login ou confirmação obrigatória. Não tenta burlar proteção anti-bot.", "{}"))
            add(tool("browser_close", "Fecha a sessão WebView, libera memória e descarta o histórico de navegação em memória.", "{}"))
            add(tool("browser_tabs", "Lista abas, perfil e proprietário do controle no navegador integrado.", "{}"))
            add(tool("browser_tab_open", "Cria e seleciona uma nova aba em um perfil de navegador.", """{"type":"object","properties":{"url":{"type":"string","minLength":8,"maxLength":2048},"profile_id":{"type":"string","maxLength":80,"default":"default"}},"required":["url"],"additionalProperties":false}"""))
            add(tool("browser_tab_select", "Seleciona uma aba existente para as próximas ferramentas browser_*.", """{"type":"object","properties":{"tab_id":{"type":"string","minLength":8,"maxLength":80}},"required":["tab_id"],"additionalProperties":false}"""))
            add(tool("browser_tab_close", "Fecha uma aba do navegador sem apagar o perfil autenticado.", """{"type":"object","properties":{"tab_id":{"type":"string","minLength":8,"maxLength":80}},"required":["tab_id"],"additionalProperties":false}"""))
            add(tool("browser_wait_for", "Aguarda seletor, texto, URL ou readyState sem depender de atraso fixo.", """{"type":"object","properties":{"selector":{"type":"string","maxLength":500},"text":{"type":"string","maxLength":500},"url_contains":{"type":"string","maxLength":1000},"ready_state":{"type":"string","enum":["loading","interactive","complete"]},"timeout_ms":{"type":"integer","minimum":100,"maximum":60000,"default":10000}},"additionalProperties":false}"""))
            add(tool("browser_find", "Localiza texto na página renderizada atual.", """{"type":"object","properties":{"query":{"type":"string","minLength":1,"maxLength":500},"max_results":{"type":"integer","minimum":1,"maximum":100,"default":20}},"required":["query"],"additionalProperties":false}"""))
            add(tool("browser_inspect", "Mostra estado, perfil e propriedade de controle da aba atual.", "{}"))
            add(tool("browser_console", "Lê mensagens recentes do console JavaScript, sem cookies ou cabeçalhos.", """{"type":"object","properties":{"limit":{"type":"integer","minimum":1,"maximum":500,"default":100}},"additionalProperties":false}"""))
            add(tool("browser_network_log", "Lê metadados recentes de requisições; não expõe cookies, Authorization nem corpos.", """{"type":"object","properties":{"limit":{"type":"integer","minimum":1,"maximum":500,"default":100}},"additionalProperties":false}"""))
            add(tool("browser_errors", "Lê erros recentes de HTTP, TLS, carregamento e renderer.", """{"type":"object","properties":{"limit":{"type":"integer","minimum":1,"maximum":500,"default":100}},"additionalProperties":false}"""))
            add(tool("browser_history", "Lê o histórico recente observado na aba atual.", """{"type":"object","properties":{"limit":{"type":"integer","minimum":1,"maximum":500,"default":100}},"additionalProperties":false}"""))
            add(tool("browser_record_start", "Inicia gravação local e reproduzível das ações do agente.", "{}"))
            add(tool("browser_record_stop", "Encerra a gravação local de ações.", "{}"))
            add(tool("browser_record_export", "Exporta a gravação para JSON no workspace.", """{"type":"object","properties":{"name":{"type":"string","minLength":1,"maxLength":120,"default":"browser-recording"}},"additionalProperties":false}"""))
            add(tool("browser_handoff", "Entrega a aba visível ao usuário para login, CAPTCHA ou confirmação sensível.", "{}"))
        add(tool("browser_resume_control", "Retoma o controle da aba depois que o usuário concluiu a intervenção.", "{}"))
        add(
            tool(
                "curl",
                "Equivalente callable ao curl para GET ou HEAD em qualquer URL HTTPS. Retorna status, headers selecionados e corpo textual limitado; não depende de binário CLI.",
                """{"type":"object","properties":{"url":{"type":"string","minLength":8,"maxLength":2048},"method":{"type":"string","enum":["GET","HEAD"],"default":"GET"},"max_chars":{"type":"integer","minimum":1,"maximum":131072,"default":65536}},"required":["url"],"additionalProperties":false}""",
            ),
        )
        add(
            tool(
                "wget",
                "Equivalente callable ao wget: baixa uma URL HTTPS para o workspace com redirects validados, limite, SHA-256 e gravação atômica.",
                DOWNLOAD_TOOL_SCHEMA,
            ),
        )
        add(
            tool(
                "http_download",
                "Baixa arquivo binário por HTTPS para o workspace. Segue até 5 redirects HTTPS e retorna caminho, tamanho, MIME e SHA-256.",
                DOWNLOAD_TOOL_SCHEMA,
            ),
        )
        add(
            tool(
                "download_to_external",
                "Baixa por streaming diretamente para o Downloads publico do Android via MediaStore, sem arquivo temporario, Termux ou seletor SAF. Aceita arquivos maiores que o workspace e retorna URL final, URI, tamanho, MIME e SHA-256.",
                DOWNLOAD_TOOL_SCHEMA,
            ),
        )
        add(
            tool(
                "publish_to_downloads",
                "Copia por streaming um arquivo existente do workspace para o Downloads publico do Android via MediaStore, sem carregar o arquivo na memoria.",
                PUBLISH_DOWNLOADS_SCHEMA,
            ),
        )
        add(
            tool(
                "external_tree_publish_to_downloads",
                "Copia por streaming um arquivo da arvore SAF concedida (inclusive Termux) direto para o Downloads publico via MediaStore, sem workspace, shell ou copia temporaria.",
                EXTERNAL_PUBLISH_DOWNLOADS_SCHEMA,
            ),
        )
        add(
            tool(
                "runtime_inventory",
                "Detecta runtimes, shells e compiladores realmente executáveis no sandbox Android; não presume que Python, Node, Bash ou Clang existam.",
                "{}",
            ),
        )
        add(
            tool(
                "javascript_run",
                "Executa JavaScript no JavaScriptSandbox oficial do Android, em processo isolado, sem acesso a arquivos ou rede. Limites: 32 KiB de código, 8 s e 64 KiB de resultado.",
                """{"type":"object","properties":{"code":{"type":"string","minLength":1,"maxLength":32768}},"required":["code"],"additionalProperties":false}""",
            ),
        )
        add(
            tool(
                "file_hash",
                "Calcula SHA-256 ou SHA-512 de um arquivo do workspace sem carregar o arquivo inteiro na memória.",
                """{"type":"object","properties":{"path":{"type":"string"},"algorithm":{"type":"string","enum":["SHA-256","SHA-512"],"default":"SHA-256"}},"required":["path"],"additionalProperties":false}""",
            ),
        )
        add(
            tool(
                "archive_list",
                "Inspeciona com segurança as entradas e tamanhos de um ZIP do workspace, sem extrair.",
                """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"],"additionalProperties":false}""",
            ),
        )
        add(
            tool(
                "archive_extract",
                "Extrai ZIP para um diretório novo ou vazio do workspace após aprovação, bloqueando Zip Slip, sobrescrita e zip bombs.",
                """{"type":"object","properties":{"path":{"type":"string"},"destination":{"type":"string"}},"required":["path","destination"],"additionalProperties":false}""",
            ),
        )
        add(tool("file_stat", "Retorna metadados e SHA-256 de um caminho no workspace privado.", PATH_SCHEMA))
        add(tool("file_tree", "Lista uma árvore estruturada e limitada do workspace privado.", """{"type":"object","properties":{"path":{"type":"string","default":"."},"depth":{"type":"integer","minimum":0,"maximum":8,"default":3},"include_hidden":{"type":"boolean","default":false}},"additionalProperties":false}"""))
        add(tool("file_mkdir", "Cria diretórios dentro do workspace privado.", PATH_SCHEMA))
        add(tool("file_copy", "Copia arquivo ou árvore limitada dentro do workspace privado.", SOURCE_DESTINATION_SCHEMA))
        add(tool("file_move", "Move arquivo ou pasta dentro do workspace; substituições anteriores vão para lixeira recuperável.", SOURCE_DESTINATION_SCHEMA))
        add(tool("file_trash", "Move um caminho do workspace para a lixeira recuperável do app.", PATH_SCHEMA))
        add(tool("file_apply_patch", "Aplica patch unified diff estritamente validado em um arquivo do workspace.", PATCH_SCHEMA))
        add(tool("file_replace_lines", "Substitui intervalo de linhas com trava opcional por SHA-256 para impedir escrita sobre versão nova.", REPLACE_LINES_SCHEMA))
        add(tool("workspace_checkpoint", "Cria checkpoint ZIP limitado do workspace antes de mudanças amplas.", """{"type":"object","properties":{"label":{"type":"string","maxLength":120,"default":"agent checkpoint"}},"additionalProperties":false}"""))
        add(tool("workspace_checkpoint_restore", "Restaura arquivos de um checkpoint; arquivos novos são preservados.", ID_SCHEMA))
        add(tool("workspace_checkpoint_diff", "Compara o workspace atual a um checkpoint.", ID_SCHEMA))
        add(tool("workspace_snapshot", "Registra hashes e metadados para detectar alterações posteriores.", "{}"))
        add(tool("workspace_changes", "Mostra arquivos adicionados, removidos e modificados desde um snapshot.", ID_SCHEMA))
        add(tool("code_project_summary", "Resume linguagens, arquivos e projetos detectados no workspace privado.", "{}"))
        add(tool("code_symbols", "Indexa declarações lexicais limitadas; use um LSP disponível no runtime para análise semântica completa.", """{"type":"object","properties":{"path":{"type":"string"}},"additionalProperties":false}"""))
        add(tool("code_definition", "Procura definições lexicais de um símbolo no workspace.", SYMBOL_SCHEMA))
        add(tool("code_references", "Procura referências textuais limitadas de um símbolo no workspace.", SYMBOL_SCHEMA))
        add(tool("code_dependencies", "Extrai um grafo limitado de imports e dependências do código-fonte.", "{}"))
        add(tool("code_tests", "Localiza arquivos e funções de teste no workspace.", "{}"))
        add(tool("code_parse_diagnostics", "Converte saída de compiladores comuns em diagnósticos estruturados.", """{"type":"object","properties":{"text":{"type":"string","maxLength":262144}},"required":["text"],"additionalProperties":false}"""))
        add(tool("project_detect", "Detecta sistemas de build do workspace privado e seus comandos conhecidos.", "{}"))
        add(tool("runtime_status", "Mostra comandos, packs, ABI e processos do runtime interno.", "{}"))
        add(tool("python_status", "Mostra a versão e o estado reais do CPython integrado e do LiteLLM, no processo :python.", "{}"))
            add(tool("python_exec", "Executa código Python 3.13 no workspace atual e devolve stdout, stderr, valor e traceback estruturados.", """{"type":"object","properties":{"code":{"type":"string","minLength":1,"maxLength":1048576},"timeout_ms":{"type":"integer","minimum":1000,"maximum":1800000,"default":120000}},"required":["code"],"additionalProperties":false}"""))
            add(tool("python_run_file", "Executa um arquivo .py existente dentro do workspace usando o ambiente Python deste workspace.", """{"type":"object","properties":{"path":{"type":"string"},"timeout_ms":{"type":"integer","minimum":1000,"maximum":1800000,"default":120000}},"required":["path"],"additionalProperties":false}"""))
            add(tool("python_repl_open", "Abre uma sessão REPL Python persistente para o workspace e devolve o session_id.", "{}"))
            add(tool("python_repl_write", "Envia uma linha ou bloco à sessão REPL; o campo value informa se o bloco ainda está incompleto.", """{"type":"object","properties":{"session_id":{"type":"string","minLength":1,"maxLength":100},"code":{"type":"string","maxLength":262144}},"required":["session_id","code"],"additionalProperties":false}"""))
            add(tool("python_repl_interrupt", "Solicita KeyboardInterrupt para a sessão REPL antes da próxima avaliação.", ID_SCHEMA))
            add(tool("python_repl_close", "Fecha uma sessão REPL Python e libera seu namespace.", ID_SCHEMA))
            add(tool("python_package_install", "Instala com pip uma requirement PyPI completa no ambiente isolado do workspace. Pacotes nativos sem wheel Android compatível podem falhar claramente.", """{"type":"object","properties":{"requirement":{"type":"string","minLength":1,"maxLength":512}},"required":["requirement"],"additionalProperties":false}"""))
            add(tool("python_package_list", "Lista somente distribuições pip instaladas no ambiente Python deste workspace.", "{}"))
            add(tool("python_package_remove", "Remove uma distribuição pip somente do ambiente Python deste workspace.", """{"type":"object","properties":{"distribution":{"type":"string","minLength":1,"maxLength":200}},"required":["distribution"],"additionalProperties":false}"""))
            add(tool("python_env_status", "Mostra tamanho, arquivos e versão do ambiente Python isolado do workspace.", "{}"))
            add(tool("python_env_reset", "Apaga e recria o overlay pip deste workspace sem alterar os outros workspaces nem o runtime confiável.", "{}"))
        add(tool("python_test", "Executa um autoteste local conhecido no CPython integrado e confirma resultado 42.", "{}"))
        add(tool("runtime_exec", "Executa um comando foreground no runtime interno, dentro do workspace privado e com PATH controlado.", RUNTIME_COMMAND_SCHEMA))
            add(tool("process_start", "Inicia um job persistente em background no runtime interno e devolve o ID imediatamente.", RUNTIME_COMMAND_SCHEMA))
            add(tool("process_list", "Lista jobs internos persistidos, inclusive concluidos e interrompidos ao fechar o app.", """{"type":"object","properties":{"limit":{"type":"integer","minimum":1,"maximum":100,"default":30}},"additionalProperties":false}"""))
            add(tool("process_status", "Consulta metadados de um job do runtime interno.", ID_SCHEMA))
            add(tool("process_output", "Le incrementalmente o log persistente de um job interno.", """{"type":"object","properties":{"id":{"type":"string"},"offset":{"type":"integer","minimum":0,"default":0},"max_bytes":{"type":"integer","minimum":1,"maximum":131072,"default":65536}},"required":["id"],"additionalProperties":false}"""))
            add(tool("process_wait", "Aguarda por ate 30 segundos um job interno mudar de estado e retorna status e log incremental.", """{"type":"object","properties":{"id":{"type":"string"},"after_status":{"type":"string"},"offset":{"type":"integer","minimum":0,"default":0},"timeout_ms":{"type":"integer","minimum":0,"maximum":30000,"default":10000}},"required":["id"],"additionalProperties":false}"""))
            add(tool("process_cancel", "Cancela um job ativo do runtime interno.", ID_SCHEMA))
            add(tool("runtime_package_list", "Lista packs sh interpretados: origem, versao, SHA-256 e comandos.", "{}"))
            add(tool("runtime_package_install", "Baixa e instala atomicamente um pack de scripts sh via HTTPS. Exige SHA-256 e aprovação. ELF baixado é recusado; ferramentas nativas exigem componente assinado.", RUNTIME_PACKAGE_SCHEMA))
            add(tool("runtime_package_remove", "Desativa e move um pack interno para lixeira recuperavel.", """{"type":"object","properties":{"name":{"type":"string","pattern":"^[a-z0-9][a-z0-9._-]{0,63}$"}},"required":["name"],"additionalProperties":false}"""))
            add(tool("runtime_pack_catalog", "Lista packs oficiais do runtime interno: C/C++, Node, Java/Android, Go e Rust, com tamanho e estado real.", "{}"))
            add(tool("runtime_pack_status", "Mostra o backend Linux interno, W^X, ABI e packs de compiladores instalados.", "{}"))
            add(tool("runtime_pack_install", "Instala um pack oficial dentro do próprio app, sem Termux externo e sem root. Downloads e hashes passam pelo repositório assinado do runtime.", """{"type":"object","properties":{"id":{"type":"string","enum":["linux-base","native-build","node","java-android","go","rust"]}},"required":["id"],"additionalProperties":false}"""))
        add(tool("verification_start", "Inicia format-check, analise, build e testes no workspace usando os comandos do runtime interno.", """{"type":"object","properties":{"project_kind":{"type":"string","enum":["auto","gradle","maven","node","python","rust","go","cmake","flutter"],"default":"auto"},"phases":{"type":"array","items":{"type":"string","enum":["format_check","format","analyze","lint","configure","build","test"]}},"allow_format_write":{"type":"boolean","default":false},"timeout_ms":{"type":"integer","minimum":1000,"maximum":1800000,"default":900000}},"additionalProperties":false}"""))
        add(
                tool(
                    "android_shell",
                    "Executa /system/bin/sh no sandbox do app. Exige aprovação forte do script exato; Bash só pode ser chamado se estiver instalado.",
                    """{"type":"object","properties":{"script":{"type":"string","minLength":1,"maxLength":8192}},"required":["script"],"additionalProperties":false}""",
                ),
            )
        add(
                tool(
                    "shizuku_status",
                    "Informa se o Shizuku está rodando, se este app foi autorizado e o UID real do bridge (2000=shell, 0=root).",
                    "{}",
                ),
            )
            add(
                tool(
                    "adb_shell",
                    "Executa /system/bin/sh como o usuário ADB shell através do Shizuku. Não implica root. Exige aprovação forte do script exato.",
                    """{"type":"object","properties":{"script":{"type":"string","minLength":1,"maxLength":8192},"timeout_ms":{"type":"integer","minimum":1000,"maximum":30000,"default":20000}},"required":["script"],"additionalProperties":false}""",
                ),
            )
        add(tool("accessibility_status", "Mostra separadamente se o serviço Android está conectado e se a Central de Capacidades autorizou o agente.", "{}"))
            add(tool("accessibility_snapshot", "Lê a árvore de UI do app ativo. Campos password são substituídos localmente e IDs expiram quando a tela muda.", """{"type":"object","properties":{"max_nodes":{"type":"integer","minimum":1,"maximum":300,"default":160}},"additionalProperties":false}"""))
            add(tool("accessibility_action", "Executa ação em um nó do último snapshot. Nunca preenche campos password.", """{"type":"object","properties":{"node_id":{"type":"string","pattern":"^n0(?:\\.[0-9]{1,3}){0,18}$"},"action":{"type":"string","enum":["click","long_click","focus","scroll_forward","scroll_backward","set_text"]},"text":{"type":"string","maxLength":16384}},"required":["node_id","action"],"additionalProperties":false}"""))
            add(tool("accessibility_global", "Executa uma ação global oficial do AccessibilityService.", """{"type":"object","properties":{"action":{"type":"string","enum":["back","home","recents","notifications","quick_settings"]}},"required":["action"],"additionalProperties":false}"""))
        add(tool("accessibility_gesture", "Despacha tap ou swipe em coordenadas de tela pelo AccessibilityService autorizado.", """{"type":"object","properties":{"start_x":{"type":"number","minimum":0,"maximum":10000},"start_y":{"type":"number","minimum":0,"maximum":10000},"end_x":{"type":"number","minimum":0,"maximum":10000},"end_y":{"type":"number","minimum":0,"maximum":10000},"duration_ms":{"type":"integer","minimum":50,"maximum":5000,"default":200}},"required":["start_x","start_y","end_x","end_y"],"additionalProperties":false}"""))
        add(tool("notification_status", "Mostra conexão e autorização interna do Notification Listener.", "{}"))
        add(tool("notification_list", "Lista notificações recentes já redigidas localmente, sem revelar conteúdo marcado como segredo.", """{"type":"object","properties":{"limit":{"type":"integer","minimum":1,"maximum":100,"default":30}},"additionalProperties":false}"""))
    }

    fun providerToolCall(
        invocation: AgentToolInvocation,
    ): ProviderMessage = ProviderMessage(
        role = "assistant",
        parts = emptyList(),
        toolCalls = listOf(
            ProviderToolCall(
                callId = invocation.callId,
                toolName = invocation.toolName,
                argumentsJson = invocation.argumentsJson,
            ),
        ),
    )

    suspend fun prepare(invocation: AgentToolInvocation): AgentToolPreparation {
        val known = definitions.any { it.name == invocation.toolName }
        if (!known) return rejected(invocation, "Ferramenta desconhecida ou indisponível nesta edição.")
        if (invocation.argumentsJson.toByteArray(StandardCharsets.UTF_8).size > MAX_ARGUMENT_BYTES) {
            return rejected(invocation, "Argumentos excedem o limite de $MAX_ARGUMENT_BYTES bytes.")
        }
        val arguments = try {
            JSONObject(invocation.argumentsJson.ifBlank { "{}" })
        } catch (_: JSONException) {
            return rejected(invocation, "Argumentos da ferramenta não são JSON válido.")
        }

        val requestAndCommand = try {
            requestFor(invocation, arguments)
        } catch (error: ToolValidationException) {
            return rejected(invocation, error.message ?: "Argumentos inválidos.")
        }
        val (request, command) = requestAndCommand
        val missingLeases = missingCapabilityLeases(invocation.toolName)
        if (missingLeases.isNotEmpty()) {
            return rejected(
                invocation,
                "Autorize na Central de Capacidades: ${missingLeases.joinToString()}.",
            )
        }
        val decision = policy.evaluate(
            PolicyContext(
                distribution = profile,
                mode = executionMode,
                environmentTrust = if (command == null) {
                    EnvironmentTrust.ANDROID_APP
                } else {
                    EnvironmentTrust.POWER_USERSPACE
                },
            ),
            request,
        )
        if (decision is PolicyDecision.Deny) {
            return rejected(invocation, "${decision.code}: ${decision.reason}")
        }
        val challenge = if (decision is PolicyDecision.Ask) {
            approvals.prepare(request, decision, Instant.now())
                ?: return rejected(invocation, "A política recusou criar uma aprovação.")
        } else {
            null
        }
        return AgentToolPreparation.Ready(
            PreparedAgentTool(
                invocation = invocation,
                arguments = arguments,
                request = request,
                decision = decision,
                challenge = challenge,
                command = command,
            ),
        )
    }

    suspend fun execute(
        prepared: PreparedAgentTool,
        approved: Boolean,
    ): AgentToolResult {
        val missingLeases = missingCapabilityLeases(prepared.invocation.toolName)
        if (missingLeases.isNotEmpty()) {
            return result(
                prepared,
                "A autorização foi revogada antes da execução: ${missingLeases.joinToString()}.",
                true,
            )
        }
        val beforeHook = hookEngine.evaluate(
            HookEvent.BEFORE_TOOL,
            JSONObject(prepared.arguments.toString())
                .put("_tool_name", prepared.invocation.toolName)
                .put("_effect", prepared.request.effect.name),
        )
        if (!beforeHook.allowed) {
            return result(prepared, "Execução bloqueada por hook BEFORE_TOOL.", true)
        }
        val sanitizedPatchedArguments = JSONObject(beforeHook.patchedPayload.toString()).apply {
            remove("_tool_name")
            remove("_effect")
        }
        if (
            prepared.request.effect != ToolEffect.READ_ONLY &&
            sanitizedPatchedArguments.toString() != prepared.arguments.toString()
        ) {
            return result(
                prepared,
                "Hook tentou alterar argumentos de uma mutação já autorizada; nova aprovação é obrigatória.",
                true,
            )
        }
        val effectivePrepared = prepared.copy(
            arguments = sanitizedPatchedArguments,
        )
        var permit: ExecutionPermit? = null
        val challenge = prepared.challenge
        if (challenge != null) {
            if (!approved) return result(prepared, "Ação recusada pelo usuário.", true)
            val method = if (challenge.strongConfirmationRequired) {
                ConfirmationMethod.STRONG
            } else {
                ConfirmationMethod.EXPLICIT
            }
            permit = when (
                val issued = approvals.issue(
                    challengeId = challenge.id,
                    request = prepared.request,
                    method = method,
                    now = Instant.now(),
                )
            ) {
                is PermitResult.Issued -> issued.permit
                is PermitResult.Rejected ->
                    return result(prepared, "Aprovação inválida: ${issued.reason}", true)
            }
        } else if (prepared.decision !is PolicyDecision.Allow) {
            return result(prepared, "A ferramenta não recebeu autorização executável.", true)
        } else {
            permit = approvals.issuePolicyPermit(
                request = prepared.request,
                decision = prepared.decision,
                now = Instant.now(),
            )
        }

        return try {
            val payload = withContext(Dispatchers.IO) {
                if (
                    effectivePrepared.request.effect == ToolEffect.WORKSPACE_MUTATION &&
                    effectivePrepared.invocation.toolName !in setOf(
                        "workspace_checkpoint",
                        "workspace_checkpoint_restore",
                        "workspace_checkpoint_diff",
                        "memory_save",
                        "memory_update",
                    )
                ) {
                    createAutomaticCheckpoint(effectivePrepared.invocation.toolName)
                }
                executePrepared(effectivePrepared, permit)
            }
            hookEngine.evaluate(
                HookEvent.AFTER_TOOL,
                JSONObject()
                    .put("tool_name", prepared.invocation.toolName)
                    .put("succeeded", true),
            )
            result(prepared, payload, false)
        } catch (error: Exception) {
            hookEngine.evaluate(
                HookEvent.FAILURE,
                JSONObject()
                    .put("stage", "tool")
                    .put("tool_name", prepared.invocation.toolName)
                    .put("error", error.message?.take(500)),
            )
            result(
                prepared,
                "Falha segura em ${prepared.invocation.toolName}: " +
                    (error.message ?: error::class.java.simpleName).take(320),
                true,
            )
        }
    }

    private fun requestFor(
        invocation: AgentToolInvocation,
        arguments: JSONObject,
    ): Pair<ToolRequest, CommandSpec?> {
        val name = invocation.toolName
        var command: CommandSpec? = null
        val (capabilities, effect, reversible, summary) = when (name) {
            "ask_user" -> ToolPlan(
                emptySet(),
                ToolEffect.READ_ONLY,
                true,
                "Aguardar uma resposta explícita do usuário.",
            )
            "memory_list" -> ToolPlan(
                setOf(Capability.FILE_READ),
                ToolEffect.READ_ONLY,
                true,
                "Listar memórias aprendidas auditáveis.",
            )
            "agent_profiles", "mcp_servers", "command_templates" -> ToolPlan(
                setOf(Capability.FILE_READ),
                ToolEffect.READ_ONLY,
                true,
                "Inspecionar configuração da plataforma de agentes com $name.",
            )
            "memory_save" -> {
                arguments.requiredString("body", 8_192)
                arguments.requiredString("category", 40)
                requireTool(
                    arguments.optString("scope", "chat") in setOf("global", "workspace", "chat"),
                    "Escopo de memória inválido.",
                )
                ToolPlan(
                    setOf(Capability.FILE_WRITE),
                    ToolEffect.WORKSPACE_MUTATION,
                    true,
                    "Salvar memória aprendida auditável.",
                )
            }
            "memory_update" -> {
                arguments.requiredString("id", 80)
                arguments.requiredString("body", 8_192)
                arguments.requiredString("reason", 240)
                requireTool(
                    arguments.optString("state", MemoryState.ACTIVE.name) in
                        setOf(MemoryState.ACTIVE.name, MemoryState.DISABLED.name, MemoryState.CONFLICT.name),
                    "Estado de memória inválido.",
                )
                ToolPlan(
                    setOf(Capability.FILE_WRITE),
                    ToolEffect.WORKSPACE_MUTATION,
                    true,
                    "Atualizar memória aprendida com revisão.",
                )
            }
            "access_matrix" -> ToolPlan(
                setOf(Capability.PROCESS_INSPECT),
                ToolEffect.READ_ONLY,
                true,
                "Inspecionar os níveis reais de acesso do agente.",
            )
            "shadow_display_status" -> ToolPlan(
                setOf(Capability.PROCESS_INSPECT), ToolEffect.READ_ONLY, true,
                "Inspecionar a tela paralela.",
            )
            "shadow_apps" -> ToolPlan(
                setOf(Capability.PROCESS_INSPECT), ToolEffect.READ_ONLY, true,
                "Listar aplicativos inicializáveis para a tela paralela.",
            )
            "shadow_display_start" -> {
                arguments.intIn("width", 720, 360, 1920)
                arguments.intIn("height", 1600, 640, 3200)
                arguments.intIn("density_dpi", 280, 120, 640)
                ToolPlan(
                    setOf(Capability.EXTERNAL_APP_CONTROL, Capability.SCREEN_CAPTURE),
                    ToolEffect.EXTERNAL_MUTATION,
                    true,
                    "Criar tela Android paralela via Shizuku.",
                )
            }
            "shadow_display_stop" -> ToolPlan(
                setOf(Capability.EXTERNAL_APP_CONTROL), ToolEffect.EXTERNAL_MUTATION, true,
                "Fechar a tela Android paralela.",
            )
            "shadow_launch" -> {
                val packageName = arguments.requiredString("package_name", 200)
                requireTool(packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")), "Pacote inválido.")
                ToolPlan(
                    setOf(Capability.EXTERNAL_APP_CONTROL), ToolEffect.EXTERNAL_MUTATION, false,
                    "Abrir $packageName somente na tela paralela.",
                )
            }
            "shadow_screenshot" -> {
                resolve(arguments.optString("path", "shadow/latest.png"), mustExist = false)
                ToolPlan(
                    setOf(Capability.SCREEN_CAPTURE, Capability.FILE_WRITE),
                    ToolEffect.WORKSPACE_MUTATION,
                    true,
                    "Capturar frame da tela paralela no workspace.",
                )
            }
            "shadow_ui" -> ToolPlan(
                setOf(Capability.EXTERNAL_APP_CONTROL, Capability.SCREEN_CAPTURE),
                ToolEffect.READ_ONLY,
                true,
                "Ler a árvore semântica da tela paralela.",
            )
            "shadow_wait" -> {
                arguments.intIn("seconds", 10, 1, 600)
                ToolPlan(
                    setOf(Capability.PROCESS_INSPECT), ToolEffect.READ_ONLY, true,
                    "Aguardar o aplicativo paralelo sem nova chamada ao provider.",
                )
            }
            "shadow_tap", "shadow_swipe", "shadow_text", "shadow_key" -> ToolPlan(
                setOf(Capability.EXTERNAL_APP_CONTROL), ToolEffect.EXTERNAL_MUTATION, false,
                "Enviar ${name.removePrefix("shadow_")} somente à tela paralela.",
            )
            "android_device_info" -> ToolPlan(
                setOf(Capability.PROCESS_INSPECT),
                ToolEffect.READ_ONLY,
                true,
                "Ler informações básicas do dispositivo e do sandbox.",
            )
            "workspace_list" -> {
                resolve(arguments.optString("path", "."), mustExist = true)
                arguments.intIn("depth", 2, 0, 4)
                ToolPlan(setOf(Capability.FILE_READ), ToolEffect.READ_ONLY, true, "Listar workspace.")
            }
            "workspace_read" -> {
                val file = resolve(arguments.requiredString("path"), mustExist = true)
                requireTool(file.isFile, "O caminho não é um arquivo.")
                arguments.intIn("max_bytes", 65_536, 1, MAX_TEXT_BYTES)
                ToolPlan(setOf(Capability.FILE_READ), ToolEffect.READ_ONLY, true, "Ler ${relative(file)}.")
            }
            "workspace_search" -> {
                arguments.requiredString("query", MAX_QUERY_CHARS)
                resolve(arguments.optString("path", "."), mustExist = true)
                arguments.intIn("max_results", 30, 1, 100)
                ToolPlan(setOf(Capability.FILE_READ), ToolEffect.READ_ONLY, true, "Buscar texto no workspace.")
            }
            "workspace_write" -> {
                val file = resolve(arguments.requiredString("path"), mustExist = false)
                val content = arguments.requiredString("content", MAX_TEXT_BYTES)
                requireTool(content.toByteArray().size <= MAX_TEXT_BYTES, "Conteúdo excede 128 KiB.")
                val overwrite = arguments.optBoolean("overwrite", false)
                requireTool(overwrite || !file.exists(), "Arquivo já existe; overwrite deve ser true.")
                ToolPlan(
                    setOf(Capability.FILE_WRITE),
                    ToolEffect.WORKSPACE_MUTATION,
                    !file.exists(),
                    "${if (file.exists()) "Substituir" else "Criar"} ${relative(file)} (${content.length} caracteres).",
                )
            }
            "external_tree_status" -> ToolPlan(
                setOf(Capability.PROCESS_INSPECT),
                ToolEffect.READ_ONLY,
                true,
                "Verificar a concessão de pasta externa do Android.",
            )
            "external_tree_list" -> {
                arguments.intIn("depth", 2, 0, 4)
                ToolPlan(
                    setOf(Capability.FILE_READ, Capability.USER_SELECTED_FILES),
                    ToolEffect.READ_ONLY,
                    true,
                    "Listar pasta externa selecionada pelo usuário.",
                )
            }
            "external_tree_read" -> {
                arguments.requiredString("path", 1_024)
                arguments.intIn("max_bytes", 65_536, 1, MAX_TEXT_BYTES)
                ToolPlan(
                    setOf(Capability.FILE_READ, Capability.USER_SELECTED_FILES),
                    ToolEffect.READ_ONLY,
                    true,
                    "Ler arquivo da pasta externa selecionada.",
                )
            }
            "external_tree_write" -> {
                arguments.requiredString("path", 1_024)
                arguments.requiredString("content", MAX_TEXT_BYTES)
                ToolPlan(
                    setOf(Capability.FILE_WRITE, Capability.USER_SELECTED_FILES),
                    ToolEffect.EXTERNAL_MUTATION,
                    false,
                    "Gravar arquivo na pasta externa selecionada.",
                )
            }
            "git_status", "git_log", "git_diff" -> ToolPlan(
                setOf(Capability.GIT_READ, Capability.FILE_READ),
                ToolEffect.READ_ONLY,
                true,
                "Executar $name no repositório local.",
            )
            "git_init" -> ToolPlan(
                setOf(Capability.GIT_WRITE, Capability.FILE_WRITE),
                ToolEffect.WORKSPACE_MUTATION,
                true,
                "Inicializar repositório Git no workspace.",
            )
            "git_commit" -> {
                val message = arguments.requiredString("message", 200)
                ToolPlan(
                    setOf(Capability.GIT_WRITE, Capability.FILE_WRITE),
                    ToolEffect.WORKSPACE_MUTATION,
                    false,
                    "Criar commit local: $message",
                )
            }
            "capture_app_screen" -> {
                safeFileStem(arguments.optString("name", "agent-screen"))
                ToolPlan(
                    setOf(Capability.SCREEN_CAPTURE, Capability.FILE_WRITE),
                    ToolEffect.WORKSPACE_MUTATION,
                    true,
                    "Capturar a janela atual do Refrator.",
                )
            }
            "ocr_image" -> {
                val image = resolve(arguments.requiredString("path"), mustExist = true)
                requireTool(image.isFile, "A imagem não existe.")
                requireTool(image.length() in 1..MAX_IMAGE_BYTES, "Imagem vazia ou maior que 20 MiB.")
                language(arguments.optString("languages", "eng+por"))
                ToolPlan(
                    setOf(Capability.FILE_READ, Capability.OCR_LOCAL),
                    ToolEffect.READ_ONLY,
                    true,
                    "Executar OCR local em ${relative(image)}.",
                )
            }
            "weather_forecast" -> {
                val location = arguments.requiredString("location", 120)
                requireTool(location.length >= 2, "Local deve ter ao menos 2 caracteres.")
                arguments.intIn("days", 3, 1, 7)
                ToolPlan(
                    setOf(Capability.NETWORK_ACCESS),
                    ToolEffect.READ_ONLY,
                    true,
                    "Consultar previsão do tempo para $location via Open-Meteo.",
                )
            }
            "https_fetch" -> {
                val url = arguments.requiredString("url", 2_048)
                validateHttpsUrl(url)
                ToolPlan(
                    setOf(Capability.NETWORK_ACCESS),
                    ToolEffect.READ_ONLY,
                    true,
                    "Fazer GET HTTPS sem credenciais em ${URI(url).host}.",
                )
            }
            "web_search" -> {
                arguments.requiredString("query", MAX_QUERY_CHARS)
                arguments.intIn("count", 5, 1, 10)
                ToolPlan(
                    setOf(Capability.NETWORK_ACCESS),
                    ToolEffect.READ_ONLY,
                    true,
                    "Pesquisar a web e retornar resultados com origem.",
                )
            }
            "web_open" -> {
                validateGeneralHttpsUrl(arguments.requiredString("url", 2_048))
                arguments.intIn("max_chars", 65_536, 1_000, MAX_NETWORK_RESPONSE_CHARS)
                ToolPlan(
                    setOf(Capability.NETWORK_ACCESS),
                    ToolEffect.READ_ONLY,
                    true,
                    "Abrir e extrair o texto de uma pagina HTTPS.",
                )
            }
            "browser_search" -> {
                arguments.requiredString("query", MAX_QUERY_CHARS)
                browserSearchEngine(arguments.optString("engine", "duckduckgo"))
                arguments.intIn("wait_ms", 2_000, 0, 15_000)
                ToolPlan(
                    setOf(Capability.NETWORK_ACCESS),
                    ToolEffect.READ_ONLY,
                    true,
                    "Pesquisar a web em navegador JavaScript renderizado.",
                )
            }
            "browser_open" -> {
                validateGeneralHttpsUrl(arguments.requiredString("url", 2_048))
                arguments.intIn("wait_ms", 1_500, 0, 15_000)
                ToolPlan(
                    setOf(Capability.NETWORK_ACCESS),
                    ToolEffect.READ_ONLY,
                    true,
                    "Abrir página HTTPS em navegador JavaScript renderizado.",
                )
            }
            "browser_snapshot" -> {
                arguments.intIn("max_chars", 65_536, 1_000, MAX_NETWORK_RESPONSE_CHARS)
                arguments.intIn("max_elements", 160, 1, 300)
                ToolPlan(
                    setOf(Capability.NETWORK_ACCESS),
                    ToolEffect.READ_ONLY,
                    true,
                    "Ler DOM e elementos interativos da página renderizada.",
                )
            }
            "browser_click" -> {
                browserElementId(arguments.requiredString("element_id", 4))
                arguments.intIn("wait_ms", 1_500, 0, 15_000)
                ToolPlan(
                    setOf(Capability.NETWORK_ACCESS),
                    ToolEffect.EXTERNAL_MUTATION,
                    false,
                    "Clicar ${arguments.optString("element_id")} na página web atual.",
                )
            }
            "browser_type" -> {
                browserElementId(arguments.requiredString("element_id", 4))
                arguments.requiredString("text", 16_384, allowEmpty = true)
                arguments.intIn("wait_ms", 1_000, 0, 15_000)
                ToolPlan(
                    setOf(Capability.NETWORK_ACCESS),
                    ToolEffect.EXTERNAL_MUTATION,
                    false,
                    "Digitar em ${arguments.optString("element_id")} na página web atual" +
                        if (arguments.optBoolean("submit", false)) " e enviar o formulário." else ".",
                )
            }
            "browser_scroll", "browser_back", "browser_wait" -> {
                if (name == "browser_scroll") arguments.intIn("delta_y", 1_200, -10_000, 10_000)
                arguments.intIn(
                    "wait_ms",
                    if (name == "browser_wait") 2_000 else 1_000,
                    if (name == "browser_wait") 100 else 0,
                    15_000,
                )
                ToolPlan(
                    setOf(Capability.NETWORK_ACCESS),
                    ToolEffect.READ_ONLY,
                    true,
                    "Navegar localmente na página WebView com $name.",
                )
            }
            "browser_screenshot" -> {
                safeFileStem(arguments.optString("name", "browser-page"))
                ToolPlan(
                    setOf(Capability.SCREEN_CAPTURE, Capability.FILE_WRITE),
                    ToolEffect.WORKSPACE_MUTATION,
                    true,
                    "Capturar viewport renderizado do navegador.",
                )
            }
            "browser_download_status" -> ToolPlan(
                setOf(Capability.NETWORK_ACCESS),
                ToolEffect.READ_ONLY,
                true,
                "Consultar download capturado pela sessão WebView.",
            )
            "browser_download_start" -> {
                arguments.optNullableString("file_name")?.let { name ->
                    require(name.length in 1..180 && '/' !in name && '\\' !in name) {
                        "file_name deve ser apenas um nome de arquivo."
                    }
                }
                ToolPlan(
                    setOf(Capability.NETWORK_ACCESS, Capability.FILE_WRITE),
                    ToolEffect.EXTERNAL_MUTATION,
                    false,
                    "Baixar arquivo com a sessão autenticada do navegador para Downloads.",
                )
            }
            "browser_open_external" -> ToolPlan(
                setOf(Capability.NETWORK_ACCESS, Capability.EXTERNAL_APP_CONTROL),
                ToolEffect.EXTERNAL_MUTATION,
                false,
                "Abrir navegador visível para interação humana obrigatória.",
            )
            "browser_close" -> ToolPlan(
                setOf(Capability.NETWORK_ACCESS),
                ToolEffect.READ_ONLY,
                true,
                "Fechar a sessão do navegador e liberar memória.",
            )
            "browser_tab_open" -> {
                validateGeneralHttpsUrl(arguments.requiredString("url", 2_048))
                arguments.requiredString("profile_id", 80)
                ToolPlan(setOf(Capability.NETWORK_ACCESS), ToolEffect.READ_ONLY, true, "Abrir nova aba HTTPS.")
            }
            "browser_tab_select" -> {
                arguments.requiredString("tab_id", 80)
                ToolPlan(setOf(Capability.NETWORK_ACCESS), ToolEffect.READ_ONLY, true, "Selecionar aba do navegador.")
            }
            "browser_tab_close" -> {
                arguments.requiredString("tab_id", 80)
                ToolPlan(setOf(Capability.FILE_WRITE), ToolEffect.WORKSPACE_MUTATION, true, "Fechar aba do navegador.")
            }
            "browser_tabs", "browser_inspect", "browser_console", "browser_network_log",
            "browser_errors", "browser_history", "browser_record_start", "browser_record_stop",
            -> ToolPlan(
                setOf(Capability.NETWORK_ACCESS),
                ToolEffect.READ_ONLY,
                true,
                "Inspecionar ou controlar estado local do navegador com $name.",
            )
            "browser_wait_for" -> {
                arguments.optNullableString("selector")?.take(500)
                arguments.optNullableString("text")?.take(500)
                arguments.optNullableString("url_contains")?.take(1_000)
                arguments.intIn("timeout_ms", 10_000, 100, 60_000)
                ToolPlan(setOf(Capability.NETWORK_ACCESS), ToolEffect.READ_ONLY, true, "Aguardar condição verificável na página.")
            }
            "browser_find" -> {
                arguments.requiredString("query", 500)
                arguments.intIn("max_results", 20, 1, 100)
                ToolPlan(setOf(Capability.NETWORK_ACCESS), ToolEffect.READ_ONLY, true, "Localizar texto na página atual.")
            }
            "browser_record_export" -> {
                safeFileStem(arguments.optString("name", "browser-recording"))
                ToolPlan(
                    setOf(Capability.FILE_WRITE),
                    ToolEffect.WORKSPACE_MUTATION,
                    true,
                    "Exportar gravação do navegador no workspace.",
                )
            }
            "browser_handoff" -> ToolPlan(
                setOf(Capability.EXTERNAL_APP_CONTROL),
                ToolEffect.EXTERNAL_MUTATION,
                false,
                "Entregar controle da aba ao usuário para interação sensível.",
            )
            "browser_resume_control" -> ToolPlan(
                setOf(Capability.NETWORK_ACCESS),
                ToolEffect.READ_ONLY,
                true,
                "Retomar controle da aba após intervenção do usuário.",
            )
            "curl" -> {
                validateGeneralHttpsUrl(arguments.requiredString("url", 2_048))
                httpMethod(arguments.optString("method", "GET"))
                arguments.intIn("max_chars", 65_536, 1, MAX_NETWORK_RESPONSE_CHARS)
                ToolPlan(
                    setOf(Capability.NETWORK_ACCESS),
                    ToolEffect.READ_ONLY,
                    true,
                    "Consultar URL HTTPS com a ferramenta curl nativa do app.",
                )
            }
            "wget", "http_download" -> {
                val url = arguments.requiredString("url", 2_048)
                validateGeneralHttpsUrl(url)
                val destination = resolve(arguments.requiredString("path"), mustExist = false)
                val overwrite = arguments.optBoolean("overwrite", false)
                requireTool(overwrite || !destination.exists(), "Arquivo já existe; overwrite deve ser true.")
                downloadLimit(arguments)
                ToolPlan(
                    setOf(Capability.NETWORK_ACCESS, Capability.FILE_WRITE),
                    ToolEffect.WORKSPACE_MUTATION,
                    !destination.exists(),
                    "Baixar HTTPS para ${relative(destination)} com $name.",
                )
            }
            "download_to_external" -> {
                validateGeneralHttpsUrl(arguments.requiredString("url", 2_048))
                arguments.requiredString("path", 1_024)
                downloadLimit(arguments)
                requireTool(publicDownloads.available(), "Downloads publico direto exige Android 10 ou superior.")
                ToolPlan(
                    setOf(Capability.NETWORK_ACCESS, Capability.FILE_WRITE),
                    ToolEffect.EXTERNAL_MUTATION,
                    false,
                    "Baixar HTTPS diretamente para Downloads/${arguments.optString("path")}.",
                )
            }
            "publish_to_downloads" -> {
                val source = resolve(arguments.requiredString("source", 1_024), mustExist = true)
                requireTool(source.isFile, "A origem precisa ser um arquivo do workspace.")
                arguments.requiredString("path", 1_024)
                requireTool(publicDownloads.available(), "Downloads publico direto exige Android 10 ou superior.")
                ToolPlan(
                    setOf(Capability.FILE_READ, Capability.FILE_WRITE),
                    ToolEffect.EXTERNAL_MUTATION,
                    false,
                    "Publicar ${relative(source)} em Downloads/${arguments.optString("path")}.",
                )
            }
            "external_tree_publish_to_downloads" -> {
                arguments.requiredString("source", 1_024)
                arguments.requiredString("path", 1_024)
                val status = documentTree.status()
                requireTool(status.granted && status.canRead, "Escolha a pasta de origem na aba Ferramentas.")
                requireTool(publicDownloads.available(), "Downloads publico direto exige Android 10 ou superior.")
                ToolPlan(
                    setOf(Capability.USER_SELECTED_FILES, Capability.FILE_READ, Capability.FILE_WRITE),
                    ToolEffect.EXTERNAL_MUTATION,
                    false,
                    "Transmitir ${arguments.optString("source")} da arvore SAF para Downloads/${arguments.optString("path")}.",
                )
            }
            "runtime_inventory" -> ToolPlan(
                setOf(Capability.PROCESS_INSPECT),
                ToolEffect.READ_ONLY,
                true,
                "Detectar runtimes, shells e compiladores disponíveis no sandbox.",
            )
            "shizuku_status" -> ToolPlan(
                setOf(Capability.PROCESS_INSPECT),
                ToolEffect.READ_ONLY,
                true,
                "Verificar o estado e o UID do bridge Shizuku.",
            )
            "accessibility_status", "notification_status" -> ToolPlan(
                setOf(Capability.PROCESS_INSPECT), ToolEffect.READ_ONLY, true,
                "Consultar estado de uma capacidade Android.",
            )
            "accessibility_snapshot", "notification_list" -> ToolPlan(
                setOf(Capability.EXTERNAL_APP_CONTROL), ToolEffect.READ_ONLY, true,
                "Ler contexto Android autorizado e redigido.",
            )
            "accessibility_action" -> {
                arguments.requiredString("node_id", 100)
                arguments.requiredString("action", 32)
                ToolPlan(setOf(Capability.EXTERNAL_APP_CONTROL), ToolEffect.EXTERNAL_MUTATION, false, "Interagir com nó da interface ativa.")
            }
            "accessibility_global" -> {
                arguments.requiredString("action", 32)
                ToolPlan(setOf(Capability.EXTERNAL_APP_CONTROL), ToolEffect.EXTERNAL_MUTATION, false, "Executar ação global no Android.")
            }
            "accessibility_gesture" -> ToolPlan(
                setOf(Capability.EXTERNAL_APP_CONTROL), ToolEffect.EXTERNAL_MUTATION, false,
                "Despachar gesto na tela ativa.",
            )
            "javascript_run" -> {
                arguments.requiredString("code", MAX_JAVASCRIPT_CHARS)
                ToolPlan(
                    setOf(Capability.SHELL_EXECUTE),
                    ToolEffect.READ_ONLY,
                    true,
                    "Executar JavaScript em processo isolado, sem arquivos ou rede.",
                )
            }
            "file_hash" -> {
                val file = resolve(arguments.requiredString("path"), mustExist = true)
                requireTool(file.isFile, "O caminho não é um arquivo.")
                hashAlgorithm(arguments.optString("algorithm", "SHA-256"))
                ToolPlan(
                    setOf(Capability.FILE_READ),
                    ToolEffect.READ_ONLY,
                    true,
                    "Calcular hash de ${relative(file)}.",
                )
            }
            "archive_list" -> {
                val archive = resolve(arguments.requiredString("path"), mustExist = true)
                requireTool(archive.isFile, "O ZIP não existe.")
                requireTool(archive.length() in 1..MAX_ARCHIVE_BYTES, "ZIP vazio ou maior que 64 MiB.")
                ToolPlan(
                    setOf(Capability.FILE_READ),
                    ToolEffect.READ_ONLY,
                    true,
                    "Inspecionar ZIP ${relative(archive)}.",
                )
            }
            "archive_extract" -> {
                val archive = resolve(arguments.requiredString("path"), mustExist = true)
                requireTool(archive.isFile, "O ZIP não existe.")
                requireTool(archive.length() in 1..MAX_ARCHIVE_BYTES, "ZIP vazio ou maior que 64 MiB.")
                val destination = resolve(arguments.requiredString("destination"), mustExist = false)
                requireTool(!destination.exists() || destination.isDirectory, "Destino não é diretório.")
                requireTool(
                    !destination.exists() || destination.list()?.isEmpty() == true,
                    "Destino deve ser novo ou estar vazio.",
                )
                ToolPlan(
                    setOf(Capability.FILE_READ, Capability.FILE_WRITE),
                    ToolEffect.WORKSPACE_MUTATION,
                    !destination.exists(),
                    "Extrair ${relative(archive)} em ${relative(destination)}.",
                )
            }
            "file_stat", "file_tree", "code_symbols" -> {
                resolve(arguments.optString("path", "."), mustExist = true)
                if (name == "file_tree") arguments.intIn("depth", 3, 0, 8)
                ToolPlan(setOf(Capability.FILE_READ), ToolEffect.READ_ONLY, true, "Inspecionar o workspace com $name.")
            }
            "code_project_summary", "code_dependencies", "code_tests", "project_detect" -> ToolPlan(
                setOf(Capability.FILE_READ), ToolEffect.READ_ONLY, true, "Analisar código e projeto local com $name.",
            )
            "code_definition", "code_references" -> {
                arguments.requiredString("symbol", 200)
                ToolPlan(setOf(Capability.FILE_READ), ToolEffect.READ_ONLY, true, "Procurar símbolo no workspace.")
            }
            "code_parse_diagnostics" -> {
                arguments.requiredString("text", 262_144)
                ToolPlan(emptySet(), ToolEffect.READ_ONLY, true, "Estruturar diagnósticos de compilação.")
            }
            "file_mkdir" -> {
                resolve(arguments.requiredString("path"), mustExist = false)
                ToolPlan(setOf(Capability.FILE_WRITE), ToolEffect.WORKSPACE_MUTATION, true, "Criar diretório no workspace.")
            }
            "file_copy", "file_move" -> {
                resolve(arguments.requiredString("source"), mustExist = true)
                resolve(arguments.requiredString("destination"), mustExist = false)
                ToolPlan(setOf(Capability.FILE_READ, Capability.FILE_WRITE), ToolEffect.WORKSPACE_MUTATION, name == "file_move", "$name dentro do workspace.")
            }
            "file_trash" -> {
                resolve(arguments.requiredString("path"), mustExist = true)
                ToolPlan(setOf(Capability.FILE_WRITE), ToolEffect.WORKSPACE_MUTATION, true, "Mover caminho para lixeira recuperável.")
            }
            "file_apply_patch" -> {
                resolve(arguments.requiredString("path"), mustExist = true)
                arguments.requiredString("patch", 1_048_576)
                ToolPlan(setOf(Capability.FILE_READ, Capability.FILE_WRITE), ToolEffect.WORKSPACE_MUTATION, true, "Aplicar patch validado no workspace.")
            }
            "file_replace_lines" -> {
                resolve(arguments.requiredString("path"), mustExist = true)
                arguments.requiredString("replacement", 262_144, allowEmpty = true)
                requireTool(arguments.getInt("start_line") >= 1, "Linha inicial inválida.")
                requireTool(arguments.getInt("end_line") >= 0, "Linha final inválida.")
                ToolPlan(setOf(Capability.FILE_READ, Capability.FILE_WRITE), ToolEffect.WORKSPACE_MUTATION, true, "Substituir linhas com controle de versão.")
            }
            "workspace_checkpoint" -> ToolPlan(setOf(Capability.FILE_READ, Capability.FILE_WRITE), ToolEffect.WORKSPACE_MUTATION, true, "Criar checkpoint recuperável.")
            "workspace_checkpoint_restore" -> ToolPlan(setOf(Capability.FILE_WRITE), ToolEffect.WORKSPACE_MUTATION, true, "Restaurar checkpoint solicitado.")
            "workspace_checkpoint_diff", "workspace_changes" -> {
                arguments.requiredString("id", 100)
                ToolPlan(setOf(Capability.FILE_READ), ToolEffect.READ_ONLY, true, "Comparar estado do workspace.")
            }
            "workspace_snapshot" -> ToolPlan(setOf(Capability.FILE_READ, Capability.FILE_WRITE), ToolEffect.WORKSPACE_MUTATION, true, "Criar snapshot de metadados.")
            "runtime_status", "runtime_package_list", "runtime_pack_catalog", "runtime_pack_status", "process_list" -> ToolPlan(
                setOf(Capability.PROCESS_INSPECT), ToolEffect.READ_ONLY, true, "Consultar o runtime interno do app.",
            )
            "python_status", "python_package_list", "python_env_status", "python_test" -> ToolPlan(
                setOf(Capability.PROCESS_INSPECT), ToolEffect.READ_ONLY, true, "Consultar o CPython integrado.",
            )
            "python_exec" -> {
                arguments.requiredString("code", 1_048_576)
                arguments.optLong("timeout_ms", 120_000).coerceIn(1_000, 1_800_000)
                ToolPlan(
                    setOf(Capability.FILE_READ, Capability.FILE_WRITE, Capability.PROCESS_INSPECT, Capability.SHELL_EXECUTE, Capability.NETWORK_ACCESS),
                    ToolEffect.EXTERNAL_MUTATION, false, "Executar código no CPython integrado do workspace.",
                )
            }
            "python_run_file" -> {
                val file = resolve(arguments.requiredString("path"), mustExist = true)
                requireTool(file.isFile, "O arquivo Python não existe.")
                arguments.optLong("timeout_ms", 120_000).coerceIn(1_000, 1_800_000)
                ToolPlan(
                    setOf(Capability.FILE_READ, Capability.FILE_WRITE, Capability.PROCESS_INSPECT, Capability.SHELL_EXECUTE, Capability.NETWORK_ACCESS),
                    ToolEffect.EXTERNAL_MUTATION, false, "Executar ${relative(file)} no CPython integrado.",
                )
            }
            "python_repl_open" -> ToolPlan(
                setOf(Capability.SHELL_EXECUTE), ToolEffect.EXTERNAL_MUTATION, true, "Abrir REPL Python do workspace.",
            )
            "python_repl_write" -> {
                arguments.requiredString("session_id", 100)
                arguments.requiredString("code", 262_144, allowEmpty = true)
                ToolPlan(setOf(Capability.FILE_READ, Capability.FILE_WRITE, Capability.SHELL_EXECUTE, Capability.NETWORK_ACCESS), ToolEffect.EXTERNAL_MUTATION, false, "Executar entrada na REPL Python.")
            }
            "python_repl_interrupt", "python_repl_close" -> {
                arguments.requiredString("id", 100)
                ToolPlan(setOf(Capability.SHELL_EXECUTE), ToolEffect.EXTERNAL_MUTATION, true, "Controlar sessão REPL Python.")
            }
            "python_package_install" -> {
                arguments.requiredString("requirement", 512)
                ToolPlan(
                    setOf(Capability.NETWORK_ACCESS, Capability.FILE_WRITE, Capability.PACKAGE_INSTALL, Capability.SHELL_EXECUTE),
                    ToolEffect.EXTERNAL_MUTATION, false, "Instalar pacote pip no ambiente isolado deste workspace.",
                )
            }
            "python_package_remove" -> {
                arguments.requiredString("distribution", 200)
                ToolPlan(setOf(Capability.FILE_WRITE, Capability.PACKAGE_INSTALL), ToolEffect.DESTRUCTIVE, true, "Remover pacote pip do ambiente deste workspace.")
            }
            "python_env_reset" -> ToolPlan(
                setOf(Capability.FILE_WRITE, Capability.PACKAGE_INSTALL), ToolEffect.DESTRUCTIVE, true, "Recriar o ambiente Python deste workspace.",
            )
            "process_status", "process_output", "process_wait" -> {
                arguments.requiredString("id", 100)
                ToolPlan(setOf(Capability.PROCESS_INSPECT), ToolEffect.READ_ONLY, true, "Consultar job interno por ID.")
            }
            "process_cancel" -> {
                arguments.requiredString("id", 100)
                ToolPlan(setOf(Capability.SHELL_EXECUTE), ToolEffect.EXTERNAL_MUTATION, true, "Interromper job interno por ID.")
            }
            "runtime_exec", "process_start" -> {
                val script = arguments.requiredString("command", 65_536)
                ToolPlan(
                    setOf(Capability.FILE_READ, Capability.FILE_WRITE, Capability.PROCESS_INSPECT, Capability.SHELL_EXECUTE, Capability.NETWORK_ACCESS),
                    shellEffect(script), false, "Executar no runtime interno do app:\n$script",
                )
            }
            "runtime_package_install" -> {
                validateRuntimePackageArguments(arguments)
                ToolPlan(
                    setOf(Capability.NETWORK_ACCESS, Capability.FILE_WRITE, Capability.PACKAGE_INSTALL, Capability.SHELL_EXECUTE),
                    ToolEffect.EXTERNAL_MUTATION, true,
                    "Baixar e instalar pack sh interpretado ${arguments.getString("name")} ${arguments.getString("version")} com SHA-256 fixado. O script compartilha o UID do app e deve ser confiavel; ELF baixado sera recusado.",
                )
            }
            "runtime_package_remove" -> {
                arguments.requiredString("name", 64)
                ToolPlan(
                    setOf(Capability.FILE_WRITE, Capability.PACKAGE_INSTALL),
                    ToolEffect.DESTRUCTIVE, true, "Desativar pack do runtime e move-lo para lixeira recuperavel.",
                )
            }
            "runtime_pack_install" -> {
                arguments.requiredString("id", 64)
                ToolPlan(
                    setOf(Capability.NETWORK_ACCESS, Capability.FILE_WRITE, Capability.PACKAGE_INSTALL, Capability.SHELL_EXECUTE),
                    ToolEffect.EXTERNAL_MUTATION, true,
                    "Instalar pack oficial ${arguments.getString("id")} no runtime Linux privado do app.",
                )
            }
            "termux_status", "terminal_list" -> ToolPlan(
                setOf(Capability.PROCESS_INSPECT), ToolEffect.READ_ONLY, true, "Consultar estado do ambiente Termux.",
            )
            "terminal_read" -> {
                arguments.requiredString("id", 100)
                ToolPlan(setOf(Capability.PROCESS_INSPECT), ToolEffect.READ_ONLY, true, "Consultar execução Termux por ID.")
            }
            "terminal_close" -> {
                arguments.requiredString("id", 100)
                ToolPlan(setOf(Capability.SHELL_EXECUTE), ToolEffect.EXTERNAL_MUTATION, true, "Interromper execução Termux por ID.")
            }
            "terminal_write", "terminal_resize" -> {
                arguments.requiredString("id", 100)
                if (name == "terminal_write") arguments.requiredString("input", 16_384, allowEmpty = true)
                ToolPlan(setOf(Capability.SHELL_EXECUTE), ToolEffect.EXTERNAL_MUTATION, false, "Controlar sessão PTY Termux existente.")
            }
            "terminal_open" -> ToolPlan(setOf(Capability.SHELL_EXECUTE), ToolEffect.EXTERNAL_MUTATION, true, "Abrir sessão PTY no Termux.")
            "termux_exec" -> {
                val script = arguments.requiredString("command", 32_768)
                ToolPlan(
                    setOf(Capability.FILE_READ, Capability.FILE_WRITE, Capability.PROCESS_INSPECT, Capability.SHELL_EXECUTE, Capability.NETWORK_ACCESS),
                    shellEffect(script), false, "Executar no Linux Termux via SSH local:\n$script",
                )
            }
            "termux_file_list", "termux_file_read", "termux_project_detect" -> ToolPlan(
                setOf(Capability.FILE_READ), ToolEffect.READ_ONLY, true, "Ler o workspace Termux via ponte autenticada.",
            )
            "termux_file_write", "termux_file_apply_patch", "termux_file_mkdir" -> ToolPlan(
                setOf(Capability.FILE_READ, Capability.FILE_WRITE), ToolEffect.EXTERNAL_MUTATION, true, "Editar o workspace Termux via SFTP autenticado.",
            )
            "termux_file_trash" -> ToolPlan(
                setOf(Capability.FILE_WRITE), ToolEffect.EXTERNAL_MUTATION, true, "Mover caminho remoto para lixeira recuperável.",
            )
            "verification_start" -> ToolPlan(
                setOf(Capability.FILE_READ, Capability.FILE_WRITE, Capability.PROCESS_INSPECT, Capability.SHELL_EXECUTE),
                ToolEffect.EXTERNAL_MUTATION,
                false,
                "Executar pipeline automático de verificação no workspace Termux.",
            )
            "android_shell" -> {
                val script = arguments.requiredString("script", 8_192)
                rejectPhysicalDisplayOverride(script)
                command = DistributionBindings.powerCommand(
                    workspaceRoot = workspace,
                    commandId = "agent-shell-${UUID.randomUUID()}",
                    script = script,
                ) ?: throw ToolValidationException("Shell local não está disponível nesta edição.")
                ToolPlan(
                    setOf(
                        Capability.FILE_READ,
                        Capability.FILE_WRITE,
                        Capability.PROCESS_INSPECT,
                        Capability.SHELL_EXECUTE,
                        Capability.NETWORK_ACCESS,
                    ),
                    shellEffect(script),
                    false,
                    "Executar comando exato no shell Android:\n$script",
                )
            }
            "adb_shell" -> {
                val script = arguments.requiredString("script", 8_192)
                rejectPhysicalDisplayOverride(script)
                arguments.intIn("timeout_ms", 20_000, 1_000, 30_000)
                ToolPlan(
                    setOf(
                        Capability.FILE_READ,
                        Capability.FILE_WRITE,
                        Capability.PROCESS_INSPECT,
                        Capability.SHELL_EXECUTE,
                        Capability.NETWORK_ACCESS,
                        Capability.PACKAGE_INSTALL,
                        Capability.EXTERNAL_APP_CONTROL,
                    ),
                    shellEffect(script),
                    false,
                    "Executar comando exato como Android ADB shell via Shizuku (não root):\n$script",
                )
            }
            else -> throw ToolValidationException("Ferramenta não implementada.")
        }
        val request = ToolRequest(
            id = invocation.callId,
            toolName = name,
            capabilities = capabilities,
            effect = effect,
            workspaceScoped = name !in setOf(
                "android_shell",
                "adb_shell",
                "accessibility_snapshot",
                "accessibility_action",
                "accessibility_global",
                "accessibility_gesture",
                "notification_list",
                "external_tree_list",
                "external_tree_read",
                "external_tree_write",
                "download_to_external",
                "external_tree_publish_to_downloads",
                "termux_exec",
                "process_start",
                "process_list",
                "process_status",
                "process_output",
                "process_wait",
                "process_cancel",
                "terminal_open",
                "terminal_list",
                "terminal_read",
                "terminal_write",
                "terminal_resize",
                "terminal_close",
                "termux_file_list",
                "termux_file_read",
                "termux_file_write",
                "termux_file_apply_patch",
                "termux_file_mkdir",
                "termux_file_trash",
                "termux_project_detect",
                "verification_start",
                "runtime_package_install",
                "runtime_package_remove",
                "python_exec",
                "python_run_file",
                "python_repl_open",
                "python_repl_write",
                "python_repl_interrupt",
                "python_repl_close",
                "python_package_install",
                "python_package_remove",
                "python_env_reset",
            ),
            reversible = reversible,
            summary = summary,
            payloadFingerprint = command?.fingerprint()
                ?: invocation.argumentsJson.sha256(),
        )
        return request to command
    }

    private suspend fun missingCapabilityLeases(toolName: String): Set<String> {
        val required = when {
            toolName == "adb_shell" || toolName.startsWith("shadow_") -> setOf("shizuku")
            toolName.startsWith("accessibility_") && toolName != "accessibility_status" ->
                setOf("accessibility")
            toolName == "notification_list" -> setOf("notifications")
            toolName.startsWith("external_tree_") && toolName != "external_tree_status" ->
                setOf("external_tree")
            else -> emptySet()
        }
        if (required.isEmpty()) return emptySet()
        return required - executionRepository.activeCapabilityLeaseIds()
    }

    private suspend fun executePrepared(
        prepared: PreparedAgentTool,
        permit: ExecutionPermit?,
    ): String = when (prepared.invocation.toolName) {
        "ask_user" -> throw ToolValidationException(
            "ask_user deve ser resolvida pela interface de conversa.",
        )
        "memory_list" -> memoryList()
        "agent_profiles" -> agentProfiles()
        "mcp_servers" -> mcpServers()
        "command_templates" -> commandTemplates()
        "memory_save" -> memorySave(prepared.arguments)
        "memory_update" -> memoryUpdate(prepared.arguments)
        "access_matrix" -> accessMatrix()
        "shadow_display_status" -> shadowStatus()
        "shadow_apps" -> shadowApps(prepared.arguments)
        "shadow_display_start" -> shadowStart(prepared.arguments)
        "shadow_display_stop" -> shadowStop()
        "shadow_launch" -> requireShadowDisplay().launch(prepared.arguments.requiredString("package_name", 200))
        "shadow_screenshot" -> shadowScreenshot(prepared.arguments)
        "shadow_ui" -> shadowUi(prepared.arguments)
        "shadow_wait" -> shadowWait(prepared.arguments)
        "shadow_tap" -> requireShadowDisplay().tap(
            prepared.arguments.getInt("x"), prepared.arguments.getInt("y"),
        )
        "shadow_swipe" -> requireShadowDisplay().swipe(
            prepared.arguments.getInt("x1"), prepared.arguments.getInt("y1"),
            prepared.arguments.getInt("x2"), prepared.arguments.getInt("y2"),
            prepared.arguments.intIn("duration_ms", 350, 50, 10_000),
        )
        "shadow_text" -> requireShadowDisplay().text(prepared.arguments.requiredString("text", 500))
        "shadow_key" -> requireShadowDisplay().keyEvent(prepared.arguments.intIn("key_code", 4, 0, 1_000))
        "android_device_info" -> deviceInfo()
        "workspace_list" -> listWorkspace(prepared.arguments)
        "workspace_read" -> readWorkspace(prepared.arguments)
        "workspace_search" -> searchWorkspace(prepared.arguments)
        "workspace_write" -> writeWorkspace(prepared.arguments)
        "external_tree_status" -> externalTreeStatus()
        "external_tree_list" -> externalTreeList(prepared.arguments)
        "external_tree_read" -> externalTreeRead(prepared.arguments)
        "external_tree_write" -> externalTreeWrite(prepared.arguments)
        "git_status" -> gitStatus()
        "git_log" -> gitLog(prepared.arguments)
        "git_diff" -> gitDiff()
        "git_init" -> gitInit()
        "git_commit" -> gitCommit(prepared.arguments)
        "capture_app_screen" -> captureAppScreen(prepared.arguments)
        "ocr_image" -> ocrImage(prepared.arguments)
        "weather_forecast" -> weatherForecast(prepared.arguments)
        "https_fetch" -> httpsFetch(prepared.arguments)
        "web_search" -> webSearch(prepared.arguments)
        "web_open" -> webOpen(prepared.arguments)
        "browser_search" -> requireBrowser().search(
            query = prepared.arguments.requiredString("query", MAX_QUERY_CHARS),
            engine = browserSearchEngine(prepared.arguments.optString("engine", "duckduckgo")),
            waitMillis = prepared.arguments.intIn("wait_ms", 2_000, 0, 15_000).toLong(),
        )
        "browser_open" -> requireBrowser().open(
            url = prepared.arguments.requiredString("url", 2_048),
            waitMillis = prepared.arguments.intIn("wait_ms", 1_500, 0, 15_000).toLong(),
        )
        "browser_snapshot" -> requireBrowser().snapshot(
            maxChars = prepared.arguments.intIn("max_chars", 65_536, 1_000, MAX_NETWORK_RESPONSE_CHARS),
            maxElements = prepared.arguments.intIn("max_elements", 160, 1, 300),
        )
        "browser_click" -> requireBrowser().click(
            elementId = browserElementId(prepared.arguments.requiredString("element_id", 4)),
            waitMillis = prepared.arguments.intIn("wait_ms", 1_500, 0, 15_000).toLong(),
        )
        "browser_type" -> requireBrowser().type(
            elementId = browserElementId(prepared.arguments.requiredString("element_id", 4)),
            text = prepared.arguments.requiredString("text", 16_384, allowEmpty = true),
            submit = prepared.arguments.optBoolean("submit", false),
            waitMillis = prepared.arguments.intIn("wait_ms", 1_000, 0, 15_000).toLong(),
        )
        "browser_scroll" -> requireBrowser().scroll(
            deltaY = prepared.arguments.intIn("delta_y", 1_200, -10_000, 10_000),
            waitMillis = prepared.arguments.intIn("wait_ms", 500, 0, 15_000).toLong(),
        )
        "browser_back" -> requireBrowser().back(
            prepared.arguments.intIn("wait_ms", 1_000, 0, 15_000).toLong(),
        )
        "browser_wait" -> requireBrowser().waitForPage(
            prepared.arguments.intIn("wait_ms", 2_000, 100, 15_000).toLong(),
        )
        "browser_screenshot" -> requireBrowser().screenshot(
            safeFileStem(prepared.arguments.optString("name", "browser-page")),
        )
        "browser_download_status" -> requireBrowser().downloadStatus()
        "browser_download_start" -> requireBrowser().startPendingDownload(
            prepared.arguments.optNullableString("file_name"),
        )
        "browser_open_external" -> requireBrowser().openCurrentPageExternally()
        "browser_close" -> requireBrowser().close()
        "browser_tabs" -> browserTabs()
        "browser_tab_open" -> browserTabOpen(prepared.arguments)
        "browser_tab_select" -> browserTabSelect(prepared.arguments)
        "browser_tab_close" -> browserTabClose(prepared.arguments)
        "browser_wait_for" -> requireBrowser().waitFor(
            selector = prepared.arguments.optNullableString("selector"),
            text = prepared.arguments.optNullableString("text"),
            urlContains = prepared.arguments.optNullableString("url_contains"),
            readyState = prepared.arguments.optNullableString("ready_state"),
            timeoutMillis = prepared.arguments.optLong("timeout_ms", 10_000),
        )
        "browser_find" -> requireBrowser().find(
            prepared.arguments.requiredString("query", 500),
            prepared.arguments.intIn("max_results", 20, 1, 100),
        )
        "browser_inspect" -> requireBrowser().state()
        "browser_console" -> requireBrowser().consoleLog(prepared.arguments.intIn("limit", 100, 1, 500))
        "browser_network_log" -> requireBrowser().networkLog(prepared.arguments.intIn("limit", 100, 1, 500))
        "browser_errors" -> requireBrowser().errors(prepared.arguments.intIn("limit", 100, 1, 500))
        "browser_history" -> requireBrowser().history(prepared.arguments.intIn("limit", 100, 1, 500))
        "browser_record_start" -> requireBrowser().startRecording()
        "browser_record_stop" -> requireBrowser().stopRecording()
        "browser_record_export" -> requireBrowser().exportRecording(
            safeFileStem(prepared.arguments.optString("name", "browser-recording")),
        )
        "browser_handoff" -> browserHandoff()
        "browser_resume_control" -> browserResumeControl()
        "curl" -> curl(prepared.arguments)
        "wget", "http_download" -> download(prepared.arguments, prepared.invocation.toolName)
        "download_to_external" -> downloadToExternal(prepared.arguments)
        "publish_to_downloads" -> publishToDownloads(prepared.arguments)
        "external_tree_publish_to_downloads" -> externalTreePublishToDownloads(prepared.arguments)
        "runtime_inventory" -> runtimeInventory()
        "shizuku_status" -> shizukuStatus()
        "accessibility_status" -> accessibility.status()
        "accessibility_snapshot" -> accessibility.snapshot(
            prepared.arguments.intIn("max_nodes", 160, 1, 300),
        )
        "accessibility_action" -> accessibility.nodeAction(
            prepared.arguments.requiredString("node_id", 100),
            prepared.arguments.requiredString("action", 32),
            prepared.arguments.optNullableString("text"),
        )
        "accessibility_global" -> accessibility.globalAction(
            prepared.arguments.requiredString("action", 32),
        )
        "accessibility_gesture" -> accessibility.gesture(
            prepared.arguments.getDouble("start_x").toFloat(),
            prepared.arguments.getDouble("start_y").toFloat(),
            prepared.arguments.getDouble("end_x").toFloat(),
            prepared.arguments.getDouble("end_y").toFloat(),
            prepared.arguments.optLong("duration_ms", 200),
        )
        "notification_status" -> notifications.status()
        "notification_list" -> notifications.list(
            prepared.arguments.intIn("limit", 30, 1, 100),
        )
        "javascript_run" -> javascriptRun(prepared.arguments)
        "file_hash" -> fileHash(prepared.arguments)
        "archive_list" -> archiveList(prepared.arguments)
        "archive_extract" -> archiveExtract(prepared.arguments)
        "file_stat" -> editor.stat(prepared.arguments.requiredString("path")) .toString(2)
        "file_tree" -> editor.tree(
            prepared.arguments.optString("path", "."),
            prepared.arguments.intIn("depth", 3, 0, 8),
            prepared.arguments.optBoolean("include_hidden", false),
        ).toString(2)
        "file_mkdir" -> JSONObject().put("path", editor.mkdir(prepared.arguments.requiredString("path"))).toString(2)
        "file_copy" -> editor.copy(
            prepared.arguments.requiredString("source"), prepared.arguments.requiredString("destination"),
            prepared.arguments.optBoolean("overwrite", false),
        ).toString(2)
        "file_move" -> editor.move(
            prepared.arguments.requiredString("source"), prepared.arguments.requiredString("destination"),
            prepared.arguments.optBoolean("overwrite", false),
        ).toString(2)
        "file_trash" -> editor.trash(prepared.arguments.requiredString("path")).toString(2)
        "file_apply_patch" -> editor.applyUnifiedPatch(
            prepared.arguments.requiredString("path"), prepared.arguments.requiredString("patch", 1_048_576),
        ).toJson().toString(2)
        "file_replace_lines" -> editor.replaceLines(
            prepared.arguments.requiredString("path"), prepared.arguments.getInt("start_line"),
            prepared.arguments.getInt("end_line"), prepared.arguments.requiredString("replacement", 262_144, true),
            prepared.arguments.optNullableString("expected_sha256"),
        ).toString(2)
        "workspace_checkpoint" -> editor.checkpoint(prepared.arguments.optString("label", "agent checkpoint")).toString(2)
        "workspace_checkpoint_restore" -> editor.restoreCheckpoint(prepared.arguments.requiredString("id", 100)).toString(2)
        "workspace_checkpoint_diff" -> editor.checkpointDiff(prepared.arguments.requiredString("id", 100)).toString(2)
        "workspace_snapshot" -> editor.createSnapshot().toString(2)
        "workspace_changes" -> editor.changesSinceSnapshot(prepared.arguments.requiredString("id", 100)).toString(2)
        "code_project_summary" -> codeIntelligence.projectSummary().toString(2)
        "code_symbols" -> codeIntelligence.symbols(prepared.arguments.optNullableString("path")).toString(2)
        "code_definition" -> codeIntelligence.definition(prepared.arguments.requiredString("symbol", 200)).toString(2)
        "code_references" -> codeIntelligence.references(prepared.arguments.requiredString("symbol", 200)).toString(2)
        "code_dependencies" -> codeIntelligence.dependencyGraph().toString(2)
        "code_tests" -> codeIntelligence.findTests().toString(2)
        "code_parse_diagnostics" -> codeIntelligence.parseDiagnostics(prepared.arguments.requiredString("text", 262_144)).toString(2)
        "project_detect" -> JSONArray(ProjectDetector.detect(workspace).map(ProjectProfile::toJson)).toString(2)
        "runtime_status" -> internalRuntime.status().toString(2)
        "runtime_exec" -> runtimeExec(prepared.arguments)
        "runtime_package_list" -> internalRuntime.listPackages().toString(2)
        "runtime_package_install" -> internalRuntime.install(runtimePackageRequest(prepared.arguments)).toString(2)
        "runtime_package_remove" -> internalRuntime.remove(prepared.arguments.requiredString("name", 64)).toString(2)
        "runtime_pack_catalog" -> internalRuntime.runtimePackCatalog().toString(2)
        "runtime_pack_status" -> internalRuntime.runtimePackStatus().toString(2)
        "runtime_pack_install" -> internalRuntime.installRuntimePack(prepared.arguments.requiredString("id", 64)).toString(2)
        "python_status" -> requirePythonRuntime().status()
        "python_exec" -> requirePythonRuntime().execute(
            prepared.arguments.requiredString("code", 1_048_576),
            prepared.arguments.optLong("timeout_ms", 120_000).coerceIn(1_000, 1_800_000),
        )
        "python_run_file" -> requirePythonRuntime().runFile(
            resolve(prepared.arguments.requiredString("path"), mustExist = true).path,
            prepared.arguments.optLong("timeout_ms", 120_000).coerceIn(1_000, 1_800_000),
        )
        "python_repl_open" -> requirePythonRuntime().replOpen()
        "python_repl_write" -> requirePythonRuntime().replWrite(
            prepared.arguments.requiredString("session_id", 100),
            prepared.arguments.requiredString("code", 262_144, allowEmpty = true),
        )
        "python_repl_interrupt" -> requirePythonRuntime().replInterrupt(prepared.arguments.requiredString("id", 100))
        "python_repl_close" -> requirePythonRuntime().replClose(prepared.arguments.requiredString("id", 100))
        "python_package_install" -> requirePythonRuntime().packageInstall(prepared.arguments.requiredString("requirement", 512))
        "python_package_list" -> requirePythonRuntime().packageList()
        "python_package_remove" -> requirePythonRuntime().packageRemove(prepared.arguments.requiredString("distribution", 200))
        "python_env_status" -> requirePythonRuntime().environmentStatus()
        "python_env_reset" -> requirePythonRuntime().environmentReset()
        "python_test" -> requirePythonRuntime().test()
        "termux_status" -> termuxStatus()
        "termux_exec" -> termuxExec(prepared.arguments)
        "process_start" -> internalProcessStart(prepared.arguments)
        "process_list" -> JSONArray(processManager.list(prepared.arguments.intIn("limit", 30, 1, 100)).map { it.toJson() }).toString(2)
        "process_status" -> processManager.load(prepared.arguments.requiredString("id", 100))?.toJson()?.toString(2)
            ?: throw ToolValidationException("Job não encontrado.")
        "process_output" -> processManager.output(
            prepared.arguments.requiredString("id", 100), prepared.arguments.optLong("offset", 0),
            prepared.arguments.intIn("max_bytes", 65_536, 1, 131_072),
        ).toString(2)
        "process_wait" -> processWait(prepared.arguments)
        "process_cancel" -> JSONObject().put("cancelled", processManager.cancelInternal(prepared.arguments.requiredString("id", 100), internalRuntime)).toString(2)
        "terminal_open" -> terminalOpen(prepared.arguments)
        "terminal_list" -> processManager.terminalSnapshots().toString(2)
        "terminal_read" -> terminalRead(prepared.arguments)
        "terminal_write" -> terminalWrite(prepared.arguments)
        "terminal_resize" -> terminalResize(prepared.arguments)
        "terminal_close" -> JSONObject().put("closed", processManager.closeTerminal(prepared.arguments.requiredString("id", 100))).toString(2)
        "termux_file_list" -> termuxFileList(prepared.arguments)
        "termux_file_read" -> termuxFileRead(prepared.arguments)
        "termux_file_write" -> termuxFileWrite(prepared.arguments)
        "termux_file_apply_patch" -> termuxFileApplyPatch(prepared.arguments)
        "termux_file_mkdir" -> termuxFileMkdir(prepared.arguments)
        "termux_file_trash" -> termuxFileTrash(prepared.arguments)
        "termux_project_detect" -> termuxProjectDetect()
        "verification_start" -> internalVerificationStart(prepared.arguments)
        "android_shell" -> executeShell(prepared, permit)
        "adb_shell" -> executeAdbShell(prepared, permit)
        else -> throw ToolValidationException("Ferramenta não implementada.")
    }

    private suspend fun browserTabOpen(arguments: JSONObject): String {
        val profileId = arguments.optString("profile_id", "default")
        val profile = browserWorkspace.profile(profileId)
            ?: throw ToolValidationException("Perfil de navegador não encontrado.")
        val tab = browserWorkspace.addTab(workspaceId, profile.id)
        browserWorkspace.select(workspaceId, tab.id)
        val session = AgentBrowserSession.get(
            activity ?: appContext.applicationContext,
            workspace,
            tab.id,
            profile.webViewProfileName,
        )
        val url = arguments.requiredString("url", 2_048)
        val snapshot = session.open(url, 500)
        val state = JSONObject(session.state())
        browserWorkspace.update(
            tab.copy(
                url = state.optString("url", url),
                title = state.optString("title", "Nova aba").ifBlank { "Nova aba" },
                selected = true,
            ),
        )
        return JSONObject().put("tab_id", tab.id).put("snapshot", JSONObject(snapshot)).toString(2)
    }

    private suspend fun createAutomaticCheckpoint(toolName: String) {
        val conversation = conversationId ?: "unbound"
        val metadata = editor.checkpoint("Automático antes de $toolName")
        workbenchDao.addWorkspaceCheckpoint(
            WorkspaceCheckpoint(
                id = metadata.getString("id"),
                workspaceId = workspaceId,
                conversationId = conversation,
                branchId = if (conversation == "unbound") "unbound" else "$conversation:main",
                messageId = null,
                manifestJson = metadata.toString(),
                reversible = true,
                byteCount = metadata.optLong("bytes", 0),
                createdAtMillis = metadata.optLong("created_at", System.currentTimeMillis()),
            ),
        )
    }

    private suspend fun browserTabSelect(arguments: JSONObject): String {
        val id = arguments.requiredString("tab_id", 80)
        val tabs = browserWorkspace.tabs(workspaceId)
        requireTool(tabs.any { it.id == id }, "Aba não encontrada.")
        browserWorkspace.select(workspaceId, id)
        return JSONObject().put("selected", true).put("tab_id", id).toString(2)
    }

    private suspend fun browserTabClose(arguments: JSONObject): String {
        val id = arguments.requiredString("tab_id", 80)
        val tabs = browserWorkspace.tabs(workspaceId)
        requireTool(tabs.size > 1, "A última aba não pode ser fechada.")
        requireTool(tabs.any { it.id == id }, "Aba não encontrada.")
        val tab = tabs.first { it.id == id }
        requireTool(tab.controlOwner == "agent", "Browser tab is under user control.")
        val profileName = browserWorkspace.profile(tab.profileId)?.webViewProfileName
            ?: AgentBrowserSession.DEFAULT_PROFILE_NAME
        AgentBrowserSession.get(
            activity ?: appContext.applicationContext,
            workspace,
            id,
            profileName,
        ).close()
        browserWorkspace.close(id)
        val remaining = browserWorkspace.tabs(workspaceId)
        if (remaining.none { it.selected }) browserWorkspace.select(workspaceId, remaining.first().id)
        return JSONObject().put("closed", true).put("tab_id", id).toString(2)
    }

    private suspend fun browserTabs(): String {
        val profiles = browserWorkspace.ensureProfiles().associateBy(BrowserProfileEntity::id)
        return JSONArray(
            browserWorkspace.tabs(workspaceId).map { tab ->
                JSONObject()
                    .put("id", tab.id)
                    .put("title", tab.title)
                    .put("url", safeObservedUrl(tab.url))
                    .put("selected", tab.selected)
                    .put("profile", profiles[tab.profileId]?.name ?: tab.profileId)
                    .put("control_owner", tab.controlOwner)
                    .put("frozen", tab.frozen)
            },
        ).toString(2)
    }

    private suspend fun memoryList(): String = JSONArray(
        contextMemory.memories().map { memory ->
            JSONObject()
                .put("id", memory.id)
                .put("scope", memory.scopeType.lowercase())
                .put("category", memory.category)
                .put("body", memory.body)
                .put("confidence", memory.confidence)
                .put("state", memory.state)
                .put("pinned", memory.pinned)
                .put("source_conversation_id", memory.sourceConversationId ?: JSONObject.NULL)
                .put("updated_at", memory.updatedAtMillis)
        },
    ).toString(2)

    private suspend fun agentProfiles(): String {
        val selected = agentPlatform.selectedProfileId()
        return JSONArray(agentPlatform.profiles().map { profile ->
            JSONObject()
                .put("id", profile.id)
                .put("name", profile.name)
                .put("description", profile.description)
                .put("provider", profile.providerPreset ?: JSONObject.NULL)
                .put("model", profile.modelId ?: JSONObject.NULL)
                .put("reasoning", profile.reasoningLevel)
                .put("skills", JSONArray(profile.skillIdsJson))
                .put("mcp_servers", JSONArray(profile.mcpServerIdsJson))
                .put("browser_profile_id", profile.browserProfileId ?: JSONObject.NULL)
                .put("max_delegates", profile.maxDelegates)
                .put("selected", profile.id == selected)
        }).toString(2)
    }

    private suspend fun mcpServers(): String = JSONArray(agentPlatform.mcpServers().map { server ->
        JSONObject()
            .put("id", server.id)
            .put("name", server.name)
            .put("transport", server.transport)
            .put(
                "endpoint",
                if (server.transport == McpTransportKind.STDIO.name) "local executable" else server.commandOrUrl,
            )
            .put("workspace_id", server.workspaceId ?: JSONObject.NULL)
            .put("enabled", server.enabled)
    }).toString(2)

    private suspend fun commandTemplates(): String = JSONArray(agentPlatform.commands().map { command ->
        JSONObject()
            .put("id", command.id)
            .put("name", command.name)
            .put("description", command.description)
            .put("arguments_schema", JSONObject(command.argumentsSchemaJson))
            .put("default_agent_profile_id", command.defaultAgentProfileId ?: JSONObject.NULL)
    }).toString(2)

    private suspend fun memorySave(arguments: JSONObject): String {
        val scopeName = arguments.optString("scope", "chat")
        val scope = when (scopeName) {
            "global" -> InstructionScope.GLOBAL
            "workspace" -> InstructionScope.WORKSPACE
            "chat" -> InstructionScope.CHAT
            else -> throw ToolValidationException("Escopo de memória inválido.")
        }
        val scopeId = when (scope) {
            InstructionScope.GLOBAL -> ContextMemoryRepository.GLOBAL_SCOPE_ID
            InstructionScope.WORKSPACE -> workspaceId
            InstructionScope.CHAT -> conversationId
                ?: throw ToolValidationException("Não há conversa ativa para uma memória de chat.")
        }
        val value = contextMemory.addMemory(
            scope = scope,
            scopeId = scopeId,
            category = arguments.optString("category", "learning"),
            body = arguments.requiredString("body", 8_192),
            sourceConversationId = conversationId,
            confidence = arguments.optDouble("confidence", 0.8),
        )
        return JSONObject()
            .put("saved", true)
            .put("id", value.id)
            .put("scope", value.scopeType.lowercase())
            .put("category", value.category)
            .put("confidence", value.confidence)
            .toString(2)
    }

    private suspend fun memoryUpdate(arguments: JSONObject): String {
        val id = arguments.requiredString("id", 80)
        val current = contextMemory.memory(id)
            ?: throw ToolValidationException("Memória não encontrada.")
        val updated = contextMemory.updateMemory(
            current = current,
            body = arguments.requiredString("body", 8_192),
            reason = arguments.requiredString("reason", 240),
            author = "agent",
            state = MemoryState.valueOf(arguments.optString("state", MemoryState.ACTIVE.name)),
        )
        return JSONObject()
            .put("updated", true)
            .put("id", updated.id)
            .put("state", updated.state)
            .put("updated_at", updated.updatedAtMillis)
            .toString(2)
    }

    private fun deviceInfo(): String {
        val stat = StatFs(workspace.path)
        return JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("android", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("processors", Runtime.getRuntime().availableProcessors())
            .put("workspace", workspace.path)
            .put("workspace_free_bytes", stat.availableBytes)
            .put("distribution", BuildConfig.DISTRIBUTION)
            .toString(2)
    }

    private fun WorkspacePatchResult.toJson(): JSONObject = JSONObject()
        .put("path", path)
        .put("added_lines", addedLines)
        .put("removed_lines", removedLines)
        .put("original_sha256", originalSha256)
        .put("updated_sha256", updatedSha256)

    private fun requirePythonRuntime(): PythonRuntimeBridge = pythonRuntime

    private suspend fun requireBrowser(): AgentBrowserSession {
        val host = activity ?: appContext.applicationContext
        val profiles = browserWorkspace.ensureProfiles()
        val tabs = browserWorkspace.tabs(workspaceId)
        val selected = tabs.firstOrNull { it.selected } ?: tabs.firstOrNull() ?: return browser
        val profileName = profiles.firstOrNull { it.id == selected.profileId }?.webViewProfileName
            ?: AgentBrowserSession.DEFAULT_PROFILE_NAME
        return AgentBrowserSession.get(host, workspace, selected.id, profileName).also { session ->
            // Persisted handoff survives Activity/process recreation. Recreate the in-memory lock
            // before returning the session to any browser tool.
            if (selected.controlOwner == "user") session.handoff()
        }
    }

    private suspend fun browserHandoff(): String {
        val tabs = browserWorkspace.tabs(workspaceId)
        val selected = tabs.firstOrNull { it.selected } ?: tabs.first()
        val result = requireBrowser().handoff()
        browserWorkspace.update(selected.copy(controlOwner = "user"))
        return result
    }

    private suspend fun browserResumeControl(): String {
        val tabs = browserWorkspace.tabs(workspaceId)
        val selected = tabs.firstOrNull { it.selected } ?: tabs.first()
        val result = requireBrowser().resumeControl()
        browserWorkspace.update(selected.copy(controlOwner = "agent"))
        return result
    }

    private fun requireActivity(): Activity = activity
        ?: throw ToolValidationException("Esta ferramenta exige a Activity visível.")

    private suspend fun runtimeExec(arguments: JSONObject): String {
        val request = RuntimeCommandRequest(
            id = UUID.randomUUID().toString(),
            command = arguments.requiredString("command", 65_536),
            workingDirectory = arguments.optString("working_directory", ".").ifBlank { "." },
            timeoutMillis = arguments.optLong("timeout_ms", 30_000).coerceIn(1_000, 1_800_000),
            outputLimitBytes = arguments.intIn("output_limit_bytes", 131_072, 1_024, 4_194_304),
            environment = runtimeEnvironment(arguments),
        )
        val result = internalRuntime.execute(request)
        return JSONObject()
            .put("id", request.id)
            .put("backend", "internal_android_runtime")
            .put("exit_code", result.exitCode ?: JSONObject.NULL)
            .put("timed_out", result.timedOut)
            .put("truncated", result.truncated)
            .put("duration_ms", result.durationMillis)
            .put("output", result.output)
            .toString(2)
    }

    private suspend fun internalProcessStart(arguments: JSONObject): String = processManager.startInternal(
        runtime = internalRuntime,
        command = arguments.requiredString("command", 65_536),
        workingDirectory = arguments.optString("working_directory", ".").ifBlank { "." },
        timeoutMillis = arguments.optLong("timeout_ms", 30_000).coerceIn(1_000, 1_800_000),
        outputLimitBytes = arguments.intIn("output_limit_bytes", 131_072, 1_024, 4_194_304),
        environment = runtimeEnvironment(arguments),
    ).toJson().toString(2)

    private fun runtimeEnvironment(arguments: JSONObject): Map<String, String> {
        val value = arguments.optJSONObject("environment") ?: return emptyMap()
        requireTool(value.length() <= 32, "Variaveis de ambiente demais.")
        return value.keys().asSequence().associateWith { key ->
            requireTool(key.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")), "Nome de variavel invalido: $key")
            value.getString(key).also {
                requireTool(it.length <= 4_096 && '\u0000' !in it, "Valor de ambiente invalido.")
            }
        }
    }

    private fun validateRuntimePackageArguments(arguments: JSONObject) {
        runtimePackageRequest(arguments)
    }

    private fun runtimePackageRequest(arguments: JSONObject): RuntimePackageRequest {
        val entrypointObject = arguments.optJSONObject("entrypoints")
            ?: throw ToolValidationException("Campo obrigatorio ausente: entrypoints")
        requireTool(entrypointObject.length() in 1..128, "entrypoints deve conter de 1 a 128 comandos.")
        val entrypoints = entrypointObject.keys().asSequence().associateWith { command ->
            val value = entrypointObject.optJSONObject(command)
                ?: throw ToolValidationException("Entrypoint $command deve ser um objeto.")
            val path = value.requiredString("path", 512)
            val argumentArray = value.optJSONArray("arguments") ?: JSONArray()
            requireTool(argumentArray.length() <= 16, "Entrypoint $command possui argumentos demais.")
            val prefixArguments = (0 until argumentArray.length()).map { index ->
                argumentArray.getString(index).also {
                    requireTool(it.length <= 1_024 && '\u0000' !in it, "Argumento prefixado invalido.")
                }
            }
            RuntimeEntrypoint(path, prefixArguments)
        }
        val maxDownloadBytes = arguments.optLong("max_download_bytes", 64L * 1024 * 1024)
        requireTool(maxDownloadBytes in 1_024..256L * 1024 * 1024, "max_download_bytes fora do intervalo permitido.")
        return RuntimePackageRequest(
            name = arguments.requiredString("name", 64),
            version = arguments.requiredString("version", 64),
            url = arguments.requiredString("url", 2_048),
            sha256 = arguments.requiredString("sha256", 64),
            format = arguments.optString("format", "zip"),
            entrypoints = entrypoints,
            maxDownloadBytes = maxDownloadBytes,
        )
    }

    private suspend fun internalVerificationStart(arguments: JSONObject): String {
        val profiles = ProjectDetector.detect(workspace)
        val requestedKind = arguments.optString("project_kind", "auto")
        val profile = if (requestedKind == "auto") {
            profiles.firstOrNull()
        } else {
            profiles.firstOrNull { it.kind == requestedKind }
        } ?: throw ToolValidationException("Nenhum projeto compativel foi detectado no workspace interno.")
        val requestedPhases = arguments.optJSONArray("phases")?.let { array ->
            (0 until array.length()).map { array.getString(it) }.toSet()
        }.orEmpty()
        val allowFormatWrite = arguments.optBoolean("allow_format_write", false)
        val steps = profile.commands.filter { step ->
            (requestedPhases.isEmpty() || step.phase in requestedPhases) &&
                (allowFormatWrite || !step.mutatesFiles || step.phase != "format")
        }
        requireTool(steps.isNotEmpty(), "Nenhuma etapa de verificacao aplicavel.")
        val selected = profile.copy(commands = steps, confidence = "internal-runtime")
        val command = VerificationEngine.command(selected, requestedPhases, allowFormatWrite)
        val job = processManager.startInternal(
            runtime = internalRuntime,
            command = command,
            workingDirectory = profile.root,
            timeoutMillis = arguments.optLong("timeout_ms", 900_000).coerceIn(1_000, 1_800_000),
            outputLimitBytes = 4_194_304,
        )
        return job.toJson()
            .put("project_kind", profile.kind)
            .put("phases", JSONArray(steps.map { it.phase }))
            .put("backend", "internal_android_runtime")
            .toString(2)
    }

    private fun requireTermux(): Pair<TermuxBridge, TermuxBridgeConfig> {
        val bridge = termuxBridge ?: throw ToolValidationException("Bridge Termux indisponível nesta edição.")
        val config = termuxConfigRepository.load()
        requireTool(config.configured, "Bridge Termux ainda não foi pareado nas Ferramentas.")
        return bridge to config
    }

    private fun termuxStatus(): String {
        val bridge = termuxBridge ?: throw ToolValidationException("Bridge Termux indisponível nesta edição.")
        val config = termuxConfigRepository.load()
        val snapshot = bridge.snapshot(config)
        return JSONObject()
            .put("supported", snapshot.supported)
            .put("termux_installed", snapshot.termuxInstalled)
            .put("configured", snapshot.configured)
            .put("ready_for_connection", snapshot.readyForConnection)
            .put("host", snapshot.loopbackHost)
            .put("port", snapshot.port)
            .put("username", snapshot.username)
            .put("workspace", snapshot.workspace)
            .put("host_key_pinned", snapshot.hostKeyPinned)
            .put("public_key", snapshot.publicKey ?: JSONObject.NULL)
            .put("detail", snapshot.detail)
            .toString(2)
    }

    private fun termuxEnvironment(arguments: JSONObject): Map<String, String> {
        val value = arguments.optJSONObject("environment") ?: return emptyMap()
        requireTool(value.length() <= 32, "Variáveis de ambiente demais.")
        return value.keys().asSequence().associateWith { key ->
            requireTool(key.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")), "Nome de variável inválido: $key")
            value.getString(key).also { requireTool(it.length <= 4_096 && '\u0000' !in it, "Valor de ambiente inválido.") }
        }
    }

    private suspend fun termuxExec(arguments: JSONObject): String {
        val (bridge, config) = requireTermux()
        val request = TermuxCommandRequest(
            id = UUID.randomUUID().toString(),
            command = arguments.requiredString("command", 32_768),
            workingDirectory = arguments.optString("working_directory", config.workspace).ifBlank { config.workspace },
            timeoutMillis = arguments.optLong("timeout_ms", 30_000).coerceIn(1_000, 1_800_000),
            outputLimitBytes = arguments.intIn("output_limit_bytes", 131_072, 1_024, 4_194_304),
            environment = termuxEnvironment(arguments),
            allocatePty = arguments.optBoolean("allocate_pty", false),
        )
        val result = bridge.execute(config, request)
        return JSONObject()
            .put("id", request.id)
            .put("exit_code", result.exitCode ?: JSONObject.NULL)
            .put("timed_out", result.timedOut)
            .put("truncated", result.truncated)
            .put("duration_ms", result.durationMillis)
            .put("output", result.output)
            .toString(2)
    }

    private suspend fun processStart(arguments: JSONObject): String {
        val (bridge, config) = requireTermux()
        return processManager.startTermux(
            bridge = bridge,
            config = config,
            command = arguments.requiredString("command", 32_768),
            workingDirectory = arguments.optString("working_directory", config.workspace).ifBlank { config.workspace },
            timeoutMillis = arguments.optLong("timeout_ms", 30_000).coerceIn(1_000, 1_800_000),
            outputLimitBytes = arguments.intIn("output_limit_bytes", 131_072, 1_024, 4_194_304),
            environment = termuxEnvironment(arguments),
        ).toJson().toString(2)
    }

    private suspend fun processWait(arguments: JSONObject): String {
        val id = arguments.requiredString("id", 100)
        val timeout = arguments.optLong("timeout_ms", 10_000).coerceIn(0, 30_000)
        val deadline = System.currentTimeMillis() + timeout
        var snapshot = processManager.load(id) ?: throw ToolValidationException("Job não encontrado.")
        val afterStatus = arguments.optNullableString("after_status") ?: snapshot.status.name
        while (System.currentTimeMillis() < deadline &&
            snapshot.status.name.equals(afterStatus, ignoreCase = true)
        ) {
            delay(200)
            snapshot = processManager.load(id) ?: break
        }
        val output = processManager.output(
            id,
            arguments.optLong("offset", 0).coerceAtLeast(0),
            131_072,
        )
        return JSONObject()
            .put("job", snapshot.toJson())
            .put("log", output)
            .put("waited_for_status_change_from", afterStatus.lowercase())
            .toString(2)
    }

    private suspend fun terminalOpen(arguments: JSONObject): String {
        val (bridge, config) = requireTermux()
        val terminal = processManager.openTerminal(
            bridge,
            config,
            arguments.optString("working_directory", config.workspace).ifBlank { config.workspace },
            arguments.intIn("columns", 100, 20, 400),
            arguments.intIn("rows", 32, 5, 200),
        )
        return JSONObject().put("id", terminal.id).put("state", terminal.state.value.name.lowercase()).toString(2)
    }

    private fun terminalRead(arguments: JSONObject): String {
        val terminal = processManager.terminal(arguments.requiredString("id", 100))
            ?: throw ToolValidationException("Terminal não encontrado.")
        return JSONObject()
            .put("id", terminal.id)
            .put("state", terminal.state.value.name.lowercase())
            .put("output", terminal.output.value)
            .put("failure", terminal.failure.value ?: JSONObject.NULL)
            .toString(2)
    }

    private suspend fun terminalWrite(arguments: JSONObject): String {
        val terminal = processManager.terminal(arguments.requiredString("id", 100))
            ?: throw ToolValidationException("Terminal não encontrado.")
        val input = arguments.requiredString("input", 16_384, allowEmpty = true)
        terminal.write(input)
        return JSONObject().put("written_chars", input.length).toString(2)
    }

    private suspend fun terminalResize(arguments: JSONObject): String {
        val terminal = processManager.terminal(arguments.requiredString("id", 100))
            ?: throw ToolValidationException("Terminal não encontrado.")
        val columns = arguments.intIn("columns", 100, 20, 400)
        val rows = arguments.intIn("rows", 32, 5, 200)
        terminal.resize(columns, rows)
        return JSONObject().put("columns", columns).put("rows", rows).toString(2)
    }

    private suspend fun termuxFileList(arguments: JSONObject): String {
        val (bridge, config) = requireTermux()
        val entries = bridge.listFiles(
            config,
            arguments.optString("path", "."),
            arguments.intIn("depth", 2, 0, 8),
            arguments.intIn("max_entries", 500, 1, 2_000),
        )
        return JSONArray(entries.map { entry ->
            JSONObject()
                .put("path", entry.path)
                .put("type", when { entry.symbolicLink -> "symlink"; entry.directory -> "directory"; else -> "file" })
                .put("size", entry.size)
                .put("modified_at", entry.modifiedAtMillis ?: JSONObject.NULL)
        }).toString(2)
    }

    private suspend fun termuxFileRead(arguments: JSONObject): String {
        val (bridge, config) = requireTermux()
        val content = bridge.readTextFile(
            config,
            arguments.requiredString("path"),
            arguments.intIn("max_bytes", 131_072, 1, 2_097_152),
        )
        return JSONObject().put("path", content.path).put("size", content.size)
            .put("sha256", content.sha256).put("content", content.text).toString(2)
    }

    private suspend fun termuxFileWrite(arguments: JSONObject): String {
        val (bridge, config) = requireTermux()
        return bridge.writeTextFile(
            config,
            arguments.requiredString("path"),
            arguments.requiredString("content", 2_097_152, allowEmpty = true),
            arguments.optNullableString("expected_sha256"),
            arguments.optBoolean("create_only", false),
        ).toJson().toString(2)
    }

    private suspend fun termuxFileApplyPatch(arguments: JSONObject): String {
        val (bridge, config) = requireTermux()
        val path = arguments.requiredString("path")
        val existing = bridge.readTextFile(config, path, 2_097_152)
        val expected = arguments.optNullableString("expected_sha256")
        if (expected != null) requireTool(existing.sha256.equals(expected, true), "Arquivo remoto mudou desde a leitura.")
        val patch = UnifiedPatch.apply(existing.text, arguments.requiredString("patch", 1_048_576))
        val mutation = bridge.writeTextFile(config, path, patch.text, existing.sha256, createOnly = false)
        return mutation.toJson().put("added_lines", patch.added).put("removed_lines", patch.removed).toString(2)
    }

    private suspend fun termuxFileMkdir(arguments: JSONObject): String {
        val (bridge, config) = requireTermux()
        val path = arguments.requiredString("path")
        bridge.createDirectory(config, path)
        return JSONObject().put("path", path).put("created", true).toString(2)
    }

    private suspend fun termuxFileTrash(arguments: JSONObject): String {
        val (bridge, config) = requireTermux()
        val destination = bridge.moveToTrash(config, arguments.requiredString("path"))
        return JSONObject().put("trash_path", destination).put("recoverable", true).toString(2)
    }

    private fun TermuxFileMutation.toJson(): JSONObject = JSONObject()
        .put("path", path)
        .put("previous_sha256", previousSha256 ?: JSONObject.NULL)
        .put("sha256", sha256)
        .put("size", size)
        .put("created", created)

    private suspend fun remoteProjectMarkers(): List<String> {
        val (bridge, config) = requireTermux()
        val script = """
            for awb_file in gradlew build.gradle build.gradle.kts settings.gradle settings.gradle.kts pom.xml package.json pnpm-lock.yaml yarn.lock package-lock.json pyproject.toml requirements.txt setup.py Cargo.toml go.mod CMakeLists.txt pubspec.yaml; do
              test -f "${'$'}awb_file" && printf '%s\\n' "${'$'}awb_file"
            done
        """.trimIndent()
        val result = bridge.execute(
            config,
            TermuxCommandRequest(UUID.randomUUID().toString(), script, config.workspace, 20_000, 65_536),
        )
        requireTool(!result.timedOut && result.exitCode == 0, "Falha ao detectar projeto remoto: ${result.output.take(500)}")
        return result.output.lineSequence().map(String::trim).filter(String::isNotEmpty).distinct().toList()
    }

    private suspend fun termuxProjectDetect(): String {
        val markers = remoteProjectMarkers()
        return JSONArray(remoteProjectKinds(markers).map { kind ->
            JSONObject().put("kind", kind).put("markers", JSONArray(markersForKind(kind, markers)))
        }).toString(2)
    }

    private suspend fun verificationStart(arguments: JSONObject): String {
        val (bridge, config) = requireTermux()
        val markers = remoteProjectMarkers()
        val detected = remoteProjectKinds(markers)
        val requested = arguments.optString("project_kind", "auto")
        val kind = (if (requested == "auto") detected.firstOrNull() else requested)
            ?: throw ToolValidationException("Nenhum projeto suportado foi detectado no workspace Termux.")
        requireTool(kind in detected, "Projeto solicitado não foi detectado no workspace Termux: ${detected.joinToString()}.")
        val requestedPhases = arguments.optJSONArray("phases")?.let { array ->
            (0 until array.length()).map { array.getString(it) }.toSet()
        }.orEmpty()
        val allowFormatWrite = arguments.optBoolean("allow_format_write", false)
        val steps = remoteVerificationCommands(kind, markers).filter { step ->
            (requestedPhases.isEmpty() || step.phase in requestedPhases) && (allowFormatWrite || !step.mutatesFiles)
        }
        requireTool(steps.isNotEmpty(), "Nenhuma etapa de verificação aplicável.")
        val command = VerificationEngine.command(
            ProjectProfile(kind, ".", markersForKind(kind, markers), steps, "remote"),
            emptySet(),
            includeMutatingFormat = allowFormatWrite,
        )
        val job = processManager.startTermux(
            bridge, config, command, config.workspace,
            arguments.optLong("timeout_ms", 900_000).coerceIn(1_000, 1_800_000),
            4_194_304,
        )
        return job.toJson().put("project_kind", kind).put("phases", JSONArray(steps.map { it.phase })).toString(2)
    }

    private fun remoteProjectKinds(markers: List<String>): List<String> = buildList {
        if (markers.any { it == "gradlew" || it.startsWith("build.gradle") || it.startsWith("settings.gradle") }) add("gradle")
        if ("pom.xml" in markers) add("maven")
        if ("package.json" in markers) add("node")
        if (markers.any { it in setOf("pyproject.toml", "requirements.txt", "setup.py") }) add("python")
        if ("Cargo.toml" in markers) add("rust")
        if ("go.mod" in markers) add("go")
        if ("CMakeLists.txt" in markers) add("cmake")
        if ("pubspec.yaml" in markers) add("flutter")
    }

    private fun markersForKind(kind: String, markers: List<String>): List<String> = when (kind) {
        "gradle" -> markers.filter { "gradle" in it }
        "maven" -> markers.filter { it == "pom.xml" }
        "node" -> markers.filter { it == "package.json" || "lock" in it }
        "python" -> markers.filter { it in setOf("pyproject.toml", "requirements.txt", "setup.py") }
        "rust" -> markers.filter { it == "Cargo.toml" }
        "go" -> markers.filter { it == "go.mod" }
        "cmake" -> markers.filter { it == "CMakeLists.txt" }
        "flutter" -> markers.filter { it == "pubspec.yaml" }
        else -> emptyList()
    }

    private fun remoteVerificationCommands(kind: String, markers: List<String>): List<ProjectCommand> = when (kind) {
        "gradle" -> listOf(ProjectCommand("build", "./gradlew --no-daemon assemble", true), ProjectCommand("test", "./gradlew --no-daemon test", true))
        "maven" -> listOf(ProjectCommand("build", "mvn -B -DskipTests package", true), ProjectCommand("test", "mvn -B test", true))
        "node" -> {
            val manager = when { "pnpm-lock.yaml" in markers -> "pnpm"; "yarn.lock" in markers -> "yarn"; else -> "npm" }
            listOf(ProjectCommand("lint", "$manager run lint", false), ProjectCommand("build", "$manager run build", true), ProjectCommand("test", "$manager run test", false))
        }
        "python" -> listOf(ProjectCommand("analyze", "python -m compileall -q .", true), ProjectCommand("test", "python -m pytest", false))
        "rust" -> listOf(ProjectCommand("format_check", "cargo fmt --check", false), ProjectCommand("build", "cargo build", true), ProjectCommand("test", "cargo test", true))
        "go" -> listOf(ProjectCommand("format_check", "test -z \"${'$'}(gofmt -l .)\"", false), ProjectCommand("build", "go build ./...", true), ProjectCommand("test", "go test ./...", true))
        "cmake" -> listOf(ProjectCommand("configure", "cmake -S . -B build", true), ProjectCommand("build", "cmake --build build", true), ProjectCommand("test", "ctest --test-dir build --output-on-failure", false))
        "flutter" -> listOf(ProjectCommand("analyze", "flutter analyze", false), ProjectCommand("test", "flutter test", true))
        else -> emptyList()
    }

    private fun shadowStatus(): String {
        val state = requireShadowDisplay().snapshot()
        return JSONObject()
            .put("supported", state.supported)
            .put("active", state.active)
            .put("display_id", state.displayId ?: JSONObject.NULL)
            .put("width", state.width)
            .put("height", state.height)
            .put("density_dpi", state.densityDpi)
            .put("last_frame_at", state.lastFrameAtMillis ?: JSONObject.NULL)
            .put("detail", state.detail)
            .put("physical_display_untouched", true)
            .put("flag_secure_respected", true)
            .toString(2)
    }

    private fun shadowApps(arguments: JSONObject): String {
        val query = arguments.optString("query", "").trim().lowercase()
        val limit = arguments.intIn("limit", 100, 1, 200)
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val values = appContext.packageManager.queryIntentActivities(
            intent,
            android.content.pm.PackageManager.MATCH_ALL,
        ).asSequence()
            .map { info ->
                val label = info.loadLabel(appContext.packageManager).toString()
                label to info.activityInfo.packageName
            }
            .filter { (label, packageName) ->
                query.isEmpty() || label.lowercase().contains(query) || packageName.lowercase().contains(query)
            }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
            .take(limit)
            .map { (label, packageName) -> JSONObject().put("label", label).put("package_name", packageName) }
            .toList()
        return JSONArray(values).toString(2)
    }

    private suspend fun shadowStart(arguments: JSONObject): String {
        requireShadowDisplay().start(
            width = arguments.intIn("width", 720, 360, 1920),
            height = arguments.intIn("height", 1600, 640, 3200),
            densityDpi = arguments.intIn("density_dpi", 280, 120, 640),
        )
        return shadowStatus()
    }

    private suspend fun shadowStop(): String {
        requireShadowDisplay().stop()
        return JSONObject().put("stopped", true).toString()
    }

    private suspend fun shadowScreenshot(arguments: JSONObject): String {
        val destination = resolve(arguments.optString("path", "shadow/latest.png"), mustExist = false)
        requireTool(destination.extension.lowercase() == "png", "O screenshot precisa usar extensão .png.")
        val saved = requireShadowDisplay().saveScreenshot(destination)
        return JSONObject()
            .put("path", relative(saved))
            .put("bytes", saved.length())
            .put("sha256", saved.sha256File())
            .toString(2)
    }

    private fun shadowUi(arguments: JSONObject): String {
        val state = requireShadowDisplay().snapshot()
        val id = state.displayId ?: throw ToolValidationException("ShadowDisplay não está ativo.")
        return accessibility.snapshotDisplay(
            id,
            arguments.intIn("max_nodes", 160, 1, 300),
        )
    }

    private suspend fun shadowWait(arguments: JSONObject): String {
        val seconds = arguments.intIn("seconds", 10, 1, 600)
        delay(seconds * 1_000L)
        return shadowStatus()
    }

    private fun requireShadowDisplay(): ShadowDisplayBridge = shadowDisplay

    private fun accessMatrix(): String {
        val tree = documentTree.status()
        val shizuku = privilegedShell.snapshot()
        return JSONObject()
            .put(
                "app_sandbox",
                JSONObject()
                    .put("ready", true)
                    .put("uid", android.os.Process.myUid())
                    .put("workspace", workspace.path),
            )
            .put(
                "user_selected_files",
                JSONObject()
                    .put("ready", tree.granted && tree.canRead)
                    .put("name", tree.displayName ?: JSONObject.NULL)
                    .put("can_read", tree.canRead)
                    .put("can_write", tree.canWrite)
                    .put("requires_android_picker", !tree.granted),
            )
            .put(
                "shizuku",
                shizuku.toJson(),
            )
            .put("internal_runtime", internalRuntime.status())
            .put(
                "root",
                JSONObject()
                    .put("ready", shizuku.uid == 0)
                    .put("source", if (shizuku.uid == 0) "Shizuku/Sui UID 0" else JSONObject.NULL)
                    .put("required_for_product", false)
                    .put("note", "Root é opcional e nunca deve ser presumido."),
            )
            .toString(2)
    }

    private fun listWorkspace(arguments: JSONObject): String {
        val root = resolve(arguments.optString("path", "."), mustExist = true)
        val depth = arguments.intIn("depth", 2, 0, 4)
        val lines = mutableListOf<String>()
        fun visit(file: File, level: Int) {
            if (lines.size >= MAX_LIST_ENTRIES) return
            val label = if (file == root) relative(file) else relative(file)
            lines += "${"  ".repeat(level)}${if (file.isDirectory) "d" else "f"} $label" +
                if (file.isFile) " (${file.length()} B)" else ""
            if (file.isDirectory && level < depth) {
                file.listFiles()
                    ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
                    ?.forEach { visit(it, level + 1) }
            }
        }
        visit(root, 0)
        if (lines.size >= MAX_LIST_ENTRIES) lines += "… limite de $MAX_LIST_ENTRIES entradas"
        return lines.joinToString("\n")
    }

    private fun readWorkspace(arguments: JSONObject): String {
        val file = resolve(arguments.requiredString("path"), mustExist = true)
        val max = arguments.intIn("max_bytes", 65_536, 1, MAX_TEXT_BYTES)
        requireTool(file.length() <= max, "Arquivo possui ${file.length()} bytes; limite solicitado é $max.")
        return file.readText(Charsets.UTF_8)
    }

    private fun searchWorkspace(arguments: JSONObject): String {
        val query = arguments.requiredString("query", MAX_QUERY_CHARS)
        val root = resolve(arguments.optString("path", "."), mustExist = true)
        val max = arguments.intIn("max_results", 30, 1, 100)
        val matches = mutableListOf<String>()
        root.walkTopDown()
            .onEnter { matches.size < max && it.name != ".git" }
            .filter { it.isFile && it.length() in 1..MAX_SEARCH_FILE_BYTES }
            .takeWhile { matches.size < max }
            .forEach { file ->
                runCatching {
                    file.useLines(Charsets.UTF_8) { lines ->
                        lines.forEachIndexed { index, line ->
                            if (matches.size < max && line.contains(query, ignoreCase = true)) {
                                matches += "${relative(file)}:${index + 1}: ${line.take(240)}"
                            }
                        }
                    }
                }
            }
        return matches.joinToString("\n").ifBlank { "Nenhuma ocorrência encontrada." }
    }

    private fun writeWorkspace(arguments: JSONObject): String {
        val target = resolve(arguments.requiredString("path"), mustExist = false)
        val content = arguments.requiredString("content", MAX_TEXT_BYTES)
        val overwrite = arguments.optBoolean("overwrite", false)
        requireTool(overwrite || !target.exists(), "Arquivo já existe.")
        target.parentFile?.mkdirs()
        val temporary = File.createTempFile(".agent-write-", ".tmp", target.parentFile)
        try {
            temporary.writeText(content, Charsets.UTF_8)
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            temporary.delete()
        }
        return "Arquivo salvo: ${relative(target)} (${target.length()} bytes)."
    }

    private fun externalTreeStatus(): String {
        val status = documentTree.status()
        return JSONObject()
            .put("granted", status.granted)
            .put("display_name", status.displayName ?: JSONObject.NULL)
            .put("uri", status.uri ?: JSONObject.NULL)
            .put("can_read", status.canRead)
            .put("can_write", status.canWrite)
            .toString(2)
    }

    private fun externalTreeList(arguments: JSONObject): String = documentTree
        .list(
            path = arguments.optString("path", ""),
            depth = arguments.intIn("depth", 2, 0, 4),
        )
        .joinToString("\n")
        .ifBlank { "Pasta externa vazia." }

    private fun externalTreeRead(arguments: JSONObject): String = documentTree.read(
        path = arguments.requiredString("path", 1_024),
        maxBytes = arguments.intIn("max_bytes", 65_536, 1, MAX_TEXT_BYTES),
    )

    private fun externalTreeWrite(arguments: JSONObject): String {
        val result = documentTree.write(
            path = arguments.requiredString("path", 1_024),
            content = arguments.requiredString("content", MAX_TEXT_BYTES),
            overwrite = arguments.optBoolean("overwrite", false),
        )
        return JSONObject()
            .put("path", result.path)
            .put("bytes", result.bytes)
            .put("uri", result.uri)
            .toString(2)
    }

    private fun gitStatus(): String = openGit().use { git ->
        val status = git.status().call()
        val branch = git.repository.branch
        buildString {
            appendLine("branch: $branch")
            fun appendSet(label: String, values: Set<String>) {
                if (values.isNotEmpty()) appendLine("$label: ${values.sorted().joinToString()}")
            }
            appendSet("added", status.added)
            appendSet("changed", status.changed)
            appendSet("modified", status.modified)
            appendSet("missing", status.missing)
            appendSet("removed", status.removed)
            appendSet("untracked", status.untracked)
            appendSet("conflicting", status.conflicting)
            if (status.isClean) append("working tree clean")
        }
    }

    private fun gitLog(arguments: JSONObject): String = openGit().use { git ->
        val max = arguments.intIn("max_count", 10, 1, 50)
        runCatching {
            git.log().setMaxCount(max).call().joinToString("\n") { commit ->
                "${commit.name.take(12)} ${commit.authorIdent.name}: ${commit.shortMessage}"
            }
        }.getOrElse { error ->
            if (error.message?.contains("HEAD") == true) "Repositório sem commits." else throw error
        }
    }

    private fun gitDiff(): String = openGit().use { git ->
        val output = ByteArrayOutputStream()
        git.diff().setOutputStream(output).call()
        val bytes = output.toByteArray()
        requireTool(bytes.size <= MAX_TEXT_BYTES, "Diff excede 128 KiB.")
        bytes.toString(Charsets.UTF_8).ifBlank { "Nenhuma alteração não staged." }
    }

    private fun gitInit(): String {
        requireTool(!File(workspace, ".git").exists(), "O workspace já possui um repositório Git.")
        Git.init().setDirectory(workspace).call().use { git ->
            return "Repositório inicializado em ${git.repository.directory.path}."
        }
    }

    private fun gitCommit(arguments: JSONObject): String = openGit().use { git ->
        val message = arguments.requiredString("message", 200)
        git.add().addFilepattern(".").call()
        val commit = git.commit()
            .setMessage(message)
            .setAuthor("Refrator", "refrator@localhost")
            .setCommitter("Refrator", "refrator@localhost")
            .call()
        "Commit criado: ${commit.name.take(12)} ${commit.shortMessage}"
    }

    private suspend fun captureAppScreen(arguments: JSONObject): String {
        val visibleActivity = requireActivity()
        val stem = safeFileStem(arguments.optString("name", "agent-screen"))
        val directory = resolve("artifacts/screenshots", mustExist = false).apply { mkdirs() }
        val target = File(directory, "$stem-${System.currentTimeMillis()}.png")
        val bitmap = withContext(Dispatchers.Main.immediate) {
            val view = visibleActivity.window.decorView.rootView
            requireTool(view.width > 0 && view.height > 0, "A janela ainda não possui dimensões.")
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                view.draw(Canvas(it))
            }
        }
        try {
            target.outputStream().buffered().use { output ->
                requireTool(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output), "Falha ao codificar PNG.")
            }
        } finally {
            bitmap.recycle()
        }
        return JSONObject()
            .put("path", relative(target))
            .put("mime_type", "image/png")
            .put("scope", "refrator_window_only")
            .put("bytes", target.length())
            .toString(2)
    }

    private fun ocrImage(arguments: JSONObject): String {
        val source = resolve(arguments.requiredString("path"), mustExist = true)
        val languages = language(arguments.optString("languages", "eng+por"))
        installTessData()
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, options)
        requireTool(options.outWidth > 0 && options.outHeight > 0, "Formato de imagem inválido.")
        var sample = 1
        while (
            options.outWidth / sample > MAX_OCR_DIMENSION ||
            options.outHeight / sample > MAX_OCR_DIMENSION
        ) {
            sample *= 2
        }
        val bitmap = BitmapFactory.decodeFile(
            source.path,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: throw ToolValidationException("Não foi possível decodificar a imagem.")
        val api = TessBaseAPI()
        try {
            requireTool(api.init(File(appContext.filesDir, "tesseract").path, languages), "Falha ao iniciar Tesseract.")
            api.setImage(bitmap)
            val text = api.utF8Text.orEmpty().trim()
            val confidence = api.meanConfidence()
            return JSONObject()
                .put("engine", "Tesseract 5 / Tesseract4Android")
                .put("languages", languages)
                .put("mean_confidence", confidence)
                .put("text", text)
                .toString(2)
        } finally {
            api.recycle()
            bitmap.recycle()
        }
    }

    private fun weatherForecast(arguments: JSONObject): String {
        val locationQuery = arguments.requiredString("location", 120)
        val days = arguments.intIn("days", 3, 1, 7)
        val encoded = URLEncoder.encode(locationQuery, StandardCharsets.UTF_8.name())
        val geocoding = httpsGet(
            "https://geocoding-api.open-meteo.com/v1/search" +
                "?name=$encoded&count=1&language=pt&format=json",
        )
        requireTool(geocoding.status in 200..299, "Geocodificação retornou HTTP ${geocoding.status}.")
        val location = JSONObject(geocoding.body)
            .optJSONArray("results")
            ?.optJSONObject(0)
            ?: throw ToolValidationException("Local não encontrado: $locationQuery")
        val latitude = location.optDouble("latitude", Double.NaN)
        val longitude = location.optDouble("longitude", Double.NaN)
        requireTool(latitude.isFinite() && longitude.isFinite(), "Coordenadas inválidas.")

        val forecastUrl = buildString {
            append("https://api.open-meteo.com/v1/forecast")
            append("?latitude=")
            append(latitude)
            append("&longitude=")
            append(longitude)
            append("&current=temperature_2m,apparent_temperature,relative_humidity_2m,")
            append("precipitation,weather_code,wind_speed_10m")
            append("&daily=weather_code,temperature_2m_max,temperature_2m_min,")
            append("precipitation_probability_max")
            append("&timezone=auto&forecast_days=")
            append(days)
        }
        val forecast = httpsGet(forecastUrl)
        requireTool(forecast.status in 200..299, "Previsão retornou HTTP ${forecast.status}.")
        val forecastJson = JSONObject(forecast.body)
        return JSONObject()
            .put("source", "Open-Meteo")
            .put(
                "location",
                JSONObject()
                    .put("name", location.optString("name"))
                    .put("admin1", location.optString("admin1"))
                    .put("country", location.optString("country"))
                    .put("latitude", latitude)
                    .put("longitude", longitude)
                    .put("timezone", location.optString("timezone")),
            )
            .put("current_units", forecastJson.optJSONObject("current_units"))
            .put("current", forecastJson.optJSONObject("current"))
            .put("daily_units", forecastJson.optJSONObject("daily_units"))
            .put("daily", forecastJson.optJSONObject("daily"))
            .toString(2)
    }

    private fun httpsFetch(arguments: JSONObject): String {
        val url = arguments.requiredString("url", 2_048)
        val response = httpsGet(url)
        return JSONObject()
            .put("url", url)
            .put("status", response.status)
            .put("content_type", response.contentType)
            .put("body", response.body)
            .toString(2)
    }

    private fun webSearch(arguments: JSONObject): String {
        val query = arguments.requiredString("query", MAX_QUERY_CHARS).trim()
        val count = arguments.intIn("count", 5, 1, 10)
        val url = "https://www.bing.com/search?format=rss&q=" +
            URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val opened = openHttpsFollowingRedirects(url, "GET")
        return try {
            requireTool(opened.status in 200..299, "Pesquisa web retornou HTTP ${opened.status}.")
            val xml = opened.connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                readBoundedText(reader, MAX_NETWORK_RESPONSE_CHARS)
            }
            val results = parseSearchRss(xml, count)
            requireTool(results.length() > 0, "A pesquisa web nao retornou resultados.")
            JSONObject()
                .put("query", query)
                .put("engine", "Bing RSS")
                .put("results", results)
                .toString(2)
        } finally {
            opened.connection.disconnect()
        }
    }

    private fun parseSearchRss(xml: String, limit: Int): JSONArray {
        val output = JSONArray()
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(StringReader(xml))
        }
        var insideItem = false
        var field: String? = null
        var title = StringBuilder()
        var link = StringBuilder()
        var description = StringBuilder()
        while (parser.eventType != XmlPullParser.END_DOCUMENT && output.length() < limit) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                    "item" -> {
                        insideItem = true
                        title = StringBuilder()
                        link = StringBuilder()
                        description = StringBuilder()
                    }
                    "title", "link", "description" -> if (insideItem) field = parser.name.lowercase()
                }
                XmlPullParser.TEXT -> if (insideItem) when (field) {
                    "title" -> title.append(parser.text)
                    "link" -> link.append(parser.text)
                    "description" -> description.append(parser.text)
                }
                XmlPullParser.END_TAG -> when (parser.name.lowercase()) {
                    "item" -> {
                        val resultUrl = link.toString().trim()
                        if (resultUrl.startsWith("https://")) {
                            output.put(
                                JSONObject()
                                    .put("title", htmlToText(title.toString()).take(300))
                                    .put("url", resultUrl.take(2_048))
                                    .put("snippet", htmlToText(description.toString()).take(1_000)),
                            )
                        }
                        insideItem = false
                        field = null
                    }
                    "title", "link", "description" -> field = null
                }
            }
            parser.next()
        }
        return output
    }

    private fun webOpen(arguments: JSONObject): String {
        val maxChars = arguments.intIn(
            "max_chars",
            65_536,
            1_000,
            MAX_NETWORK_RESPONSE_CHARS,
        )
        val requestedUrl = arguments.requiredString("url", 2_048)
        val opened = openHttpsFollowingRedirects(requestedUrl, "GET")
        return try {
            requireTool(opened.status in 200..299, "Pagina retornou HTTP ${opened.status}.")
            val body = opened.connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                readBoundedText(reader, MAX_NETWORK_RESPONSE_CHARS)
            }
            val contentType = opened.connection.contentType.orEmpty().take(160)
            val isHtml = contentType.contains("html", ignoreCase = true) ||
                body.trimStart().startsWith("<")
            val title = if (isHtml) {
                Regex("(?is)<title[^>]*>(.*?)</title>")
                    .find(body)?.groupValues?.get(1)?.let(::htmlToText).orEmpty().take(500)
            } else {
                ""
            }
            val links = JSONArray()
            if (isHtml) {
                Regex("""(?is)<a\b[^>]*\bhref\s*=\s*["']([^"'#]+)["']""")
                    .findAll(body)
                    .mapNotNull { match ->
                        runCatching { opened.finalUri.resolve(match.groupValues[1]) }.getOrNull()
                    }
                    .filter { it.scheme.equals("https", true) && it.host != null }
                    .distinctBy(URI::toString)
                    .take(50)
                    .forEach { links.put(it.toString()) }
            }
            val text = if (isHtml) htmlToText(body) else body
            JSONObject()
                .put("requested_url", requestedUrl)
                .put("final_url", opened.finalUri.toString())
                .put("status", opened.status)
                .put("content_type", contentType)
                .put("title", title)
                .put("text", text.take(maxChars))
                .put("text_truncated", text.length > maxChars || body.length >= MAX_NETWORK_RESPONSE_CHARS)
                .put("links", links)
                .toString(2)
        } finally {
            opened.connection.disconnect()
        }
    }

    private fun htmlToText(value: String): String {
        val cleaned = value
            .replace(Regex("""(?is)<(script|style|noscript)\b[^>]*>.*?</\1>"""), " ")
            .replace(Regex("""(?i)</?(p|div|section|article|main|header|footer|li|h[1-6]|br)\b[^>]*>"""), "\n")
        return android.text.Html.fromHtml(cleaned, android.text.Html.FROM_HTML_MODE_LEGACY)
            .toString()
            .replace('\u00a0', ' ')
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun readBoundedText(reader: java.io.Reader, maxChars: Int): String {
        val output = StringBuilder()
        val buffer = CharArray(4_096)
        while (output.length < maxChars) {
            val count = reader.read(buffer, 0, minOf(buffer.size, maxChars - output.length))
            if (count < 0) break
            output.append(buffer, 0, count)
        }
        return output.toString()
    }

    private fun curl(arguments: JSONObject): String {
        val method = httpMethod(arguments.optString("method", "GET"))
        val maxChars = arguments.intIn(
            "max_chars",
            65_536,
            1,
            MAX_NETWORK_RESPONSE_CHARS,
        )
        val opened = openHttpsFollowingRedirects(
            url = arguments.requiredString("url", 2_048),
            method = method,
        )
        val connection = opened.connection
        return try {
            val input = if (opened.status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val body = if (method == "HEAD") {
                ""
            } else {
                input?.bufferedReader(StandardCharsets.UTF_8)?.use { reader ->
                    val output = StringBuilder()
                    val buffer = CharArray(4_096)
                    while (output.length < maxChars) {
                        val count = reader.read(
                            buffer,
                            0,
                            minOf(buffer.size, maxChars - output.length),
                        )
                        if (count < 0) break
                        output.append(buffer, 0, count)
                    }
                    output.toString()
                }.orEmpty()
            }
            JSONObject()
                .put("requested_url", arguments.optString("url"))
                .put("final_url", opened.finalUri.toString())
                .put("method", method)
                .put("status", opened.status)
                .put("content_type", connection.contentType.orEmpty().take(160))
                .put("content_length", connection.contentLengthLong)
                .put("etag", connection.getHeaderField("ETag").orEmpty().take(256))
                .put("last_modified", connection.getHeaderField("Last-Modified").orEmpty().take(256))
                .put("body", body)
                .put("body_truncated", body.length >= maxChars)
                .toString(2)
        } finally {
            connection.disconnect()
        }
    }

    private fun download(arguments: JSONObject, toolName: String): String {
        val destination = resolve(arguments.requiredString("path"), mustExist = false)
        val overwrite = arguments.optBoolean("overwrite", false)
        requireTool(overwrite || !destination.exists(), "Arquivo já existe.")
        destination.parentFile?.let { parent ->
            requireTool(parent.mkdirs() || parent.isDirectory, "Não foi possível criar o diretório.")
        }
        val requestedLimit = downloadLimit(arguments)
        val storageBudget = (destination.parentFile?.usableSpace ?: workspace.usableSpace)
            .minus(DOWNLOAD_FREE_SPACE_RESERVE)
            .coerceAtLeast(0L)
        requireTool(
            storageBudget >= 1_024,
            "Espaço livre insuficiente; preserve ao menos 256 MiB para o Android.",
        )
        val effectiveLimit = if (requestedLimit == 0L) {
            storageBudget
        } else {
            minOf(requestedLimit, storageBudget)
        }
        val opened = openHttpsFollowingRedirects(
            url = arguments.requiredString("url", 2_048),
            method = "GET",
        )
        val connection = opened.connection
        val temporary = File.createTempFile(".agent-download-", ".tmp", destination.parentFile)
        try {
            requireTool(opened.status in 200..299, "Download retornou HTTP ${opened.status}.")
            val declared = connection.contentLengthLong
            requireTool(
                declared < 0 || declared <= effectiveLimit,
                "Servidor declarou $declared bytes; espaço seguro disponível é $effectiveLimit.",
            )
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            connection.inputStream.buffered().use { input ->
                DigestOutputStream(temporary.outputStream().buffered(), digest).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total = Math.addExact(total, count.toLong())
                        requireTool(
                            total <= effectiveLimit,
                            "Download excedeu o espaço seguro disponível ($effectiveLimit bytes).",
                        )
                        output.write(buffer, 0, count)
                    }
                }
            }
            requireTool(total > 0, "O download retornou um arquivo vazio.")
            try {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            return JSONObject()
                .put("tool", toolName)
                .put("requested_url", arguments.optString("url"))
                .put("final_url", opened.finalUri.toString())
                .put("path", relative(destination))
                .put("bytes", total)
                .put("content_type", connection.contentType.orEmpty().take(160))
                .put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
                .toString(2)
        } finally {
            connection.disconnect()
            temporary.delete()
        }
    }

    private fun downloadToExternal(arguments: JSONObject): String {
        val path = arguments.requiredString("path", 1_024)
        val requestedLimit = downloadLimit(arguments)
        val storageBudget = publicDownloads.safeWritableBytes(DOWNLOAD_FREE_SPACE_RESERVE)
        requireTool(
            storageBudget >= 1_024L,
            "Espaco livre insuficiente; preserve ao menos 256 MiB para o Android.",
        )
        val effectiveLimit = if (requestedLimit == 0L) storageBudget else minOf(requestedLimit, storageBudget)
        val opened = openHttpsFollowingRedirects(
            url = arguments.requiredString("url", 2_048),
            method = "GET",
        )
        val connection = opened.connection
        return try {
            requireTool(opened.status in 200..299, "Download retornou HTTP ${opened.status}.")
            val declared = connection.contentLengthLong
            requireTool(
                declared < 0L || declared <= effectiveLimit,
                "Servidor declarou $declared bytes; espaco seguro disponivel e $effectiveLimit.",
            )
            val mimeType = connection.contentType
                .orEmpty()
                .substringBefore(';')
                .trim()
                .ifBlank { "application/octet-stream" }
            val imported = connection.inputStream.buffered().use { input ->
                publicDownloads.writeFrom(
                    path = path,
                    mimeType = mimeType,
                    overwrite = arguments.optBoolean("overwrite", false),
                    input = input,
                    maxBytes = effectiveLimit,
                )
            }
            JSONObject()
                .put("tool", "download_to_external")
                .put("requested_url", arguments.optString("url"))
                .put("final_url", opened.finalUri.toString())
                .put("destination", "public_downloads")
                .put("path", "Downloads/${imported.path}")
                .put("uri", imported.uri)
                .put("bytes", imported.bytes)
                .put("content_type", mimeType)
                .put("sha256", imported.sha256)
                .toString(2)
        } finally {
            connection.disconnect()
        }
    }

    private fun publishToDownloads(arguments: JSONObject): String {
        val source = resolve(arguments.requiredString("source", 1_024), mustExist = true)
        val imported = publicDownloads.importFile(
                path = arguments.requiredString("path", 1_024),
                source = source,
                mimeType = arguments.optString("mime_type", "application/octet-stream"),
                overwrite = arguments.optBoolean("overwrite", false),
            )
        return JSONObject()
            .put("tool", "publish_to_downloads")
            .put("source", relative(source))
            .put("destination", "public_downloads")
            .put("path", "Downloads/${imported.path}")
            .put("uri", imported.uri)
            .put("bytes", imported.bytes)
            .put("sha256", imported.sha256)
            .toString(2)
    }

    private fun externalTreePublishToDownloads(arguments: JSONObject): String {
        val storageBudget = publicDownloads.safeWritableBytes(DOWNLOAD_FREE_SPACE_RESERVE)
        requireTool(storageBudget >= 1_024L, "Espaco livre insuficiente no armazenamento publico.")
        val imported = documentTree.publishToDownloads(
            sourcePath = arguments.requiredString("source", 1_024),
            destinationPath = arguments.requiredString("path", 1_024),
            downloads = publicDownloads,
            mimeType = arguments.optString("mime_type", ""),
            overwrite = arguments.optBoolean("overwrite", false),
            maxBytes = storageBudget,
        )
        return JSONObject()
            .put("tool", "external_tree_publish_to_downloads")
            .put("source", arguments.optString("source"))
            .put("destination", "public_downloads")
            .put("path", "Downloads/${imported.path}")
            .put("uri", imported.uri)
            .put("bytes", imported.bytes)
            .put("sha256", imported.sha256)
            .toString(2)
    }

    private fun downloadLimit(arguments: JSONObject): Long {
        val value = if (arguments.has("max_bytes")) {
            arguments.optLong("max_bytes", -1L)
        } else {
            0L
        }
        requireTool(
            value >= 0L,
            "max_bytes deve ser zero (sem teto fixo) ou um número positivo.",
        )
        return value
    }

    private fun runtimeInventory(): String {
        val pathDirectories = System.getenv("PATH")
            .orEmpty()
            .split(File.pathSeparatorChar)
            .filter(String::isNotBlank)
            .map(::File)
        val searchDirectories = (
            listOf(internalRuntime.binDirectory) + pathDirectories + listOf(
                File("/system/bin"),
                File("/system/xbin"),
                File("/vendor/bin"),
                File(appContext.applicationInfo.nativeLibraryDir),
            )
        ).distinctBy { it.path }
        val candidates = linkedMapOf(
            "shell" to listOf("sh"),
            "toolbox" to listOf("toybox"),
            "bash" to listOf("bash"),
            "python" to listOf("python3", "python"),
            "node" to listOf("node"),
            "java" to listOf("java"),
            "javac" to listOf("javac"),
            "clang" to listOf("clang"),
            "gcc" to listOf("gcc"),
            "make" to listOf("make"),
            "cmake" to listOf("cmake"),
            "ninja" to listOf("ninja"),
            "git_cli" to listOf("git"),
        )
        val detected = JSONObject()
        candidates.forEach { (runtime, names) ->
            val executable = names.firstNotNullOfOrNull { name ->
                searchDirectories
                    .asSequence()
                    .map { directory -> File(directory, name) }
                    .firstOrNull { file -> file.isFile && file.canExecute() }
            }
            detected.put(
                runtime,
                if (executable == null) {
                    JSONObject().put("available", false)
                } else {
                    JSONObject()
                        .put("available", true)
                        .put("executable", executable.path)
                },
            )
        }
        val shizuku = privilegedShell.snapshot()
        return JSONObject()
            .put("android_runtime", "ART")
            .put("android", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("javascript_sandbox", JavaScriptSandbox.isSupported())
            .put("embedded_git", "JGit ${org.eclipse.jgit.lib.Constants.HEAD.substringBefore('/').let { "7.x" }}")
            .put("embedded_ocr", "Tesseract 5")
            .put("external_tree", JSONObject(externalTreeStatus()))
            .put(
                "shizuku_bridge",
                shizuku.toJson(),
            )
            .put("detected", detected)
            .put("internal_runtime", internalRuntime.status())
            .put(
                "note",
                "O inventário descreve o runtime interno. adb_shell, quando autorizado, executa separadamente como UID shell.",
            )
            .toString(2)
    }

    private fun shizukuStatus(): String = privilegedShell.snapshot().toJson().toString(2)

    private fun javascriptRun(arguments: JSONObject): String {
        val code = arguments.requiredString("code", MAX_JAVASCRIPT_CHARS)
        requireTool(JavaScriptSandbox.isSupported(), "JavaScriptSandbox não é suportado pelo WebView deste aparelho.")
        val sandbox = JavaScriptSandbox
            .createConnectedInstanceAsync(appContext)
            .get(JAVASCRIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        try {
            val isolate = sandbox.createIsolate()
            try {
                val result = isolate
                    .evaluateJavaScriptAsync(code)
                    .get(JAVASCRIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                requireTool(
                    result.length <= MAX_JAVASCRIPT_RESULT_CHARS,
                    "Resultado JavaScript excedeu 64 KiB.",
                )
                return JSONObject()
                    .put("runtime", "AndroidX JavaScriptSandbox")
                    .put("isolated_process", true)
                    .put("filesystem_access", false)
                    .put("network_access", false)
                    .put("result", result)
                    .toString(2)
            } finally {
                isolate.close()
            }
        } finally {
            sandbox.close()
        }
    }

    private fun fileHash(arguments: JSONObject): String {
        val source = resolve(arguments.requiredString("path"), mustExist = true)
        val algorithm = hashAlgorithm(arguments.optString("algorithm", "SHA-256"))
        val digest = MessageDigest.getInstance(algorithm)
        source.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return JSONObject()
            .put("path", relative(source))
            .put("bytes", source.length())
            .put("algorithm", algorithm)
            .put("digest", digest.digest().joinToString("") { "%02x".format(it) })
            .toString(2)
    }

    private fun archiveList(arguments: JSONObject): String {
        val source = resolve(arguments.requiredString("path"), mustExist = true)
        ZipFile(source).use { zip ->
            val entries = JSONArray()
            var totalUncompressed = 0L
            val enumeration = zip.entries()
            var count = 0
            while (enumeration.hasMoreElements()) {
                requireTool(count < MAX_ARCHIVE_ENTRIES, "ZIP excede $MAX_ARCHIVE_ENTRIES entradas.")
                val entry = enumeration.nextElement()
                requireSafeArchiveEntry(entry.name)
                val size = entry.size.coerceAtLeast(0)
                totalUncompressed = Math.addExact(totalUncompressed, size)
                requireTool(
                    totalUncompressed <= MAX_ARCHIVE_EXPANDED_BYTES,
                    "ZIP declara mais de 128 MiB descompactados.",
                )
                entries.put(
                    JSONObject()
                        .put("name", entry.name)
                        .put("directory", entry.isDirectory)
                        .put("compressed_bytes", entry.compressedSize)
                        .put("uncompressed_bytes", entry.size),
                )
                count += 1
            }
            return JSONObject()
                .put("path", relative(source))
                .put("entries", entries)
                .put("entry_count", count)
                .put("declared_uncompressed_bytes", totalUncompressed)
                .toString(2)
        }
    }

    private fun archiveExtract(arguments: JSONObject): String {
        val source = resolve(arguments.requiredString("path"), mustExist = true)
        val destination = resolve(arguments.requiredString("destination"), mustExist = false)
        requireTool(!destination.exists() || destination.list()?.isEmpty() == true, "Destino deve estar vazio.")
        requireTool(destination.mkdirs() || destination.isDirectory, "Não foi possível criar o destino.")
        val destinationPath = destination.canonicalPath + File.separator
        var count = 0
        var totalBytes = 0L
        try {
            ZipInputStream(source.inputStream().buffered()).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    requireTool(count < MAX_ARCHIVE_ENTRIES, "ZIP excede $MAX_ARCHIVE_ENTRIES entradas.")
                    requireSafeArchiveEntry(entry.name)
                    val target = File(destination, entry.name).canonicalFile
                    requireTool(
                        target.path.startsWith(destinationPath),
                        "Entrada ZIP escaparia do destino: ${entry.name}",
                    )
                    if (entry.isDirectory) {
                        requireTool(target.mkdirs() || target.isDirectory, "Falha ao criar ${entry.name}.")
                    } else {
                        target.parentFile?.let { parent ->
                            requireTool(parent.mkdirs() || parent.isDirectory, "Falha ao criar diretório.")
                        }
                        requireTool(!target.exists(), "ZIP tentaria sobrescrever ${entry.name}.")
                        FileOutputStream(target).buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                totalBytes = Math.addExact(totalBytes, read.toLong())
                                requireTool(
                                    totalBytes <= MAX_ARCHIVE_EXPANDED_BYTES,
                                    "ZIP excedeu 128 MiB durante a extração.",
                                )
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    input.closeEntry()
                    count += 1
                }
            }
            return JSONObject()
                .put("source", relative(source))
                .put("destination", relative(destination))
                .put("entries_extracted", count)
                .put("bytes_written", totalBytes)
                .toString(2)
        } catch (error: Exception) {
            destination.deleteRecursively()
            throw error
        }
    }

    private fun httpsGet(url: String): BoundedHttpResponse {
        val uri = validateHttpsUrl(url)
        requirePublicResolution(uri.host)
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = false
        connection.connectTimeout = NETWORK_CONNECT_TIMEOUT_MS
        connection.readTimeout = NETWORK_READ_TIMEOUT_MS
        connection.useCaches = false
        connection.setRequestProperty("Accept", "application/json,text/plain;q=0.9")
        connection.setRequestProperty("User-Agent", "Refrator/${BuildConfig.VERSION_NAME} Android")
        val status = connection.responseCode
        val input = if (status in 200..299) connection.inputStream else connection.errorStream
        val body = input?.bufferedReader(StandardCharsets.UTF_8)?.use { reader ->
            val output = StringBuilder()
            val buffer = CharArray(4_096)
            while (output.length < MAX_NETWORK_RESPONSE_CHARS) {
                val count = reader.read(
                    buffer,
                    0,
                    minOf(buffer.size, MAX_NETWORK_RESPONSE_CHARS - output.length),
                )
                if (count < 0) break
                output.append(buffer, 0, count)
            }
            output.toString()
        }.orEmpty()
        val contentType = connection.contentType.orEmpty().take(160)
        connection.disconnect()
        return BoundedHttpResponse(status, contentType, body)
    }

    private fun validateHttpsUrl(value: String): URI {
        val uri = runCatching { URI(value) }
            .getOrElse { throw ToolValidationException("URL inválida.") }
        requireTool(uri.scheme.equals("https", ignoreCase = true), "Somente HTTPS é aceito.")
        requireTool(uri.userInfo == null, "Credenciais na URL não são aceitas.")
        requireTool(uri.fragment == null, "Fragmentos de URL não são aceitos.")
        requireTool(uri.port == -1 || uri.port == 443, "Somente a porta HTTPS padrão é aceita.")
        val host = uri.host?.lowercase()
            ?: throw ToolValidationException("Host HTTPS ausente.")
        requireTool(host in NETWORK_HOST_ALLOWLIST, "Host não permitido para https_fetch: $host")
        return uri
    }

    private fun validateGeneralHttpsUrl(value: String): URI {
        val uri = runCatching { URI(value) }
            .getOrElse { throw ToolValidationException("URL inválida.") }
        requireTool(uri.scheme.equals("https", ignoreCase = true), "Somente HTTPS é aceito.")
        requireTool(uri.userInfo == null, "Credenciais embutidas na URL não são aceitas.")
        requireTool(uri.fragment == null, "Fragmentos de URL não são aceitos.")
        requireTool(uri.port == -1 || uri.port in 1..65_535, "Porta HTTPS inválida.")
        val host = uri.host?.lowercase()
            ?: throw ToolValidationException("Host HTTPS ausente.")
        requireTool(
            host !in BLOCKED_HTTP_HOSTS &&
                !host.endsWith(".localhost") &&
                !host.endsWith(".local") &&
                !host.endsWith(".internal"),
            "Host local reservado não é aceito.",
        )
        return uri
    }

    private fun requirePublicResolution(host: String) {
        val addresses = runCatching { InetAddress.getAllByName(host) }
            .getOrElse { throw ToolValidationException("Não foi possível resolver o host HTTPS.") }
        requireTool(addresses.isNotEmpty(), "Host HTTPS sem endereço resolvido.")
        requireTool(
            addresses.all(RuntimePackageRules::isPublicAddress),
            "O host HTTPS resolveu para rede local, reservada ou não pública.",
        )
    }

    private fun openHttpsFollowingRedirects(
        url: String,
        method: String,
    ): OpenedHttpConnection {
        var current = validateGeneralHttpsUrl(url)
        repeat(MAX_HTTP_REDIRECTS + 1) { redirectCount ->
            requirePublicResolution(current.host)
            val connection = current.toURL().openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = NETWORK_CONNECT_TIMEOUT_MS
            connection.readTimeout = NETWORK_READ_TIMEOUT_MS
            connection.useCaches = false
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("User-Agent", "Refrator/${BuildConfig.VERSION_NAME} Android")
            val status = connection.responseCode
            if (status !in HTTP_REDIRECT_CODES) {
                return OpenedHttpConnection(current, status, connection)
            }
            if (redirectCount >= MAX_HTTP_REDIRECTS) {
                connection.disconnect()
                throw ToolValidationException("A URL excedeu $MAX_HTTP_REDIRECTS redirects.")
            }
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            requireTool(!location.isNullOrBlank(), "Redirect HTTP sem cabeçalho Location.")
            current = validateGeneralHttpsUrl(current.resolve(location).toString())
        }
        throw ToolValidationException("Falha ao resolver redirects HTTPS.")
    }

    private fun httpMethod(value: String): String {
        val normalized = value.uppercase()
        requireTool(normalized in setOf("GET", "HEAD"), "Método deve ser GET ou HEAD.")
        return normalized
    }

    private suspend fun executeShell(
        prepared: PreparedAgentTool,
        permit: ExecutionPermit?,
    ): String {
        val runner = powerRunner ?: throw ToolValidationException("Shell local indisponível.")
        val command = prepared.command ?: throw ToolValidationException("Comando ausente.")
        val issued = permit ?: throw ToolValidationException("Aprovação forte ausente.")
        val authorization = approvals.bind(issued, prepared.request, command)
            ?: throw ToolValidationException("Aprovação não corresponde ao comando.")
        val output = StringBuilder()
        var terminal = false
        runner.execute(command.copy(authorization = authorization)).collect { event ->
            when (event) {
                is RunnerEvent.Started -> Unit
                is RunnerEvent.StandardOutput -> output.append(event.bytes.toString(Charsets.UTF_8))
                is RunnerEvent.StandardError -> output.append("[stderr] ${event.bytes.toString(Charsets.UTF_8)}")
                is RunnerEvent.Completed -> {
                    output.appendLine("\n[exit ${event.exitCode}]")
                    terminal = true
                }
                is RunnerEvent.Rejected -> {
                    output.appendLine("[rejected] ${event.reason}")
                    terminal = true
                }
                is RunnerEvent.Interrupted -> {
                    output.appendLine("[interrupted] ${event.reason}")
                    terminal = true
                }
            }
        }
        requireTool(terminal, "Runner terminou sem evento terminal.")
        return output.toString().take(MAX_TEXT_BYTES)
    }

    private suspend fun executeAdbShell(
        prepared: PreparedAgentTool,
        permit: ExecutionPermit?,
    ): String {
        requireTool(permit != null, "Aprovação forte ausente.")
        val bridge = privilegedShell
        val script = prepared.arguments.requiredString("script", 8_192)
        val timeout = prepared.arguments.intIn("timeout_ms", 20_000, 1_000, 30_000)
        return bridge.execute(
            script = script,
            timeoutMs = timeout,
            maxOutputBytes = MAX_TEXT_BYTES,
        )
    }

    private fun shellEffect(script: String): ToolEffect =
        if (isCriticalSystemScript(script)) {
            ToolEffect.DESTRUCTIVE
        } else {
            ToolEffect.EXTERNAL_MUTATION
        }

    private fun rejectPhysicalDisplayOverride(script: String) {
        val normalized = script.lowercase()
        val changesDeveloperOverlay = Regex(
            """settings\s+(put|delete)\s+global\s+overlay_display_devices""",
        ).containsMatchIn(normalized)
        val changesPhysicalMetrics = Regex(
            """(^|[;&|]\s*)wm\s+(size|density)\b""",
        ).containsMatchIn(normalized)
        requireTool(
            !changesDeveloperOverlay && !changesPhysicalMetrics,
            "Nao altere overlay_display_devices, wm size ou wm density. Isso afeta a tela fisica; use somente shadow_display_* e shadow_* para a tela paralela.",
        )
    }

    private fun isCriticalSystemScript(script: String): Boolean {
        val normalized = script.lowercase()
        val criticalCommand = Regex(
            """(^|[;&|]\s*)(dd|mkfs(?:\.\w+)?|fastboot|flash_image|avbctl|setenforce|reboot)\b""",
        )
        val criticalPathMutation = Regex(
            """\b(rm|mv|truncate|chmod|chown|mount|umount)\b[^\n]*(/system|/vendor|/product|/odm|/boot|/metadata|/data)(/|\s|$)""",
        )
        val recoveryAction = Regex(
            """\b(wipe\s+data|factory\s*reset|reboot\s+(bootloader|recovery)|cmd\s+recovery)\b""",
        )
        return criticalCommand.containsMatchIn(normalized) ||
            criticalPathMutation.containsMatchIn(normalized) ||
            recoveryAction.containsMatchIn(normalized)
    }

    private fun installTessData() {
        val target = File(appContext.filesDir, "tesseract/tessdata").apply { mkdirs() }
        listOf("eng", "por").forEach { language ->
            val file = File(target, "$language.traineddata")
            if (!file.exists() || file.length() == 0L) {
                appContext.assets.open("tessdata/$language.traineddata").use { input ->
                    file.outputStream().buffered().use(input::copyTo)
                }
            }
        }
    }

    private fun openGit(): Git {
        val repository: Repository = FileRepositoryBuilder()
            .setWorkTree(workspace)
            .setGitDir(File(workspace, ".git"))
            .readEnvironment()
            .build()
        try {
            requireTool(repository.objectDatabase.exists(), "Workspace ainda não é um repositório Git.")
            return Git(repository)
        } catch (error: Exception) {
            repository.close()
            throw error
        }
    }

    private fun resolve(path: String, mustExist: Boolean): File {
        requireTool(path.isNotBlank(), "Caminho vazio.")
        requireTool('\u0000' !in path, "Caminho contém caractere inválido.")
        requireTool(!File(path).isAbsolute, "Caminhos absolutos não são aceitos.")
        val resolved = File(workspace, path).canonicalFile
        requireTool(
            resolved == workspace || resolved.path.startsWith(workspace.path + File.separator),
            "Caminho escaparia do workspace.",
        )
        if (mustExist) requireTool(resolved.exists(), "Caminho não existe: $path")
        return resolved
    }

    private fun relative(file: File): String =
        if (file == workspace) "." else file.relativeTo(workspace).invariantSeparatorsPath

    private fun rejected(
        invocation: AgentToolInvocation,
        message: String,
    ): AgentToolPreparation.Rejected = AgentToolPreparation.Rejected(
        AgentToolResult(invocation.callId, invocation.toolName, message, true),
    )

    private fun result(
        prepared: PreparedAgentTool,
        payload: String,
        error: Boolean,
    ): AgentToolResult = AgentToolResult(
        callId = prepared.invocation.callId,
        toolName = prepared.invocation.toolName,
        payload = payload,
        isError = error,
    )

    private fun tool(name: String, description: String, schema: String) =
        ToolDefinition(name, description, schema)

    private fun language(value: String): String {
        requireTool(value in setOf("eng", "por", "eng+por"), "Idiomas OCR não suportados.")
        return value
    }

    private fun hashAlgorithm(value: String): String {
        requireTool(value in setOf("SHA-256", "SHA-512"), "Algoritmo de hash não suportado.")
        return value
    }

    private fun requireSafeArchiveEntry(name: String) {
        requireTool(name.isNotBlank(), "ZIP contém entrada sem nome.")
        requireTool('\u0000' !in name, "ZIP contém nome inválido.")
        requireTool(!name.startsWith('/') && !name.startsWith('\\'), "ZIP contém caminho absoluto.")
        requireTool(!Regex("^[A-Za-z]:").containsMatchIn(name), "ZIP contém caminho absoluto Windows.")
        val components = name.replace('\\', '/').split('/')
        requireTool(components.none { it == ".." }, "ZIP contém travessia de diretório.")
    }

    private fun safeFileStem(value: String): String {
        requireTool(value.matches(Regex("[A-Za-z0-9._-]{1,64}")), "Nome de captura inválido.")
        return value
    }

    private fun browserSearchEngine(value: String): String {
        val normalized = value.lowercase()
        requireTool(
            normalized in setOf("duckduckgo", "google", "brave", "bing"),
            "Mecanismo de busca não suportado.",
        )
        return normalized
    }

    private fun browserElementId(value: String): String {
        requireTool(value.matches(Regex("e[1-9][0-9]{0,2}")), "ID de elemento inválido.")
        return value
    }

    private data class ToolPlan(
        val capabilities: Set<Capability>,
        val effect: ToolEffect,
        val reversible: Boolean,
        val summary: String,
    )

    private data class BoundedHttpResponse(
        val status: Int,
        val contentType: String,
        val body: String,
    )

    private data class OpenedHttpConnection(
        val finalUri: URI,
        val status: Int,
        val connection: HttpURLConnection,
    )

    private class ToolValidationException(message: String) : IllegalArgumentException(message)

    private fun requireTool(condition: Boolean, message: String) {
        if (!condition) throw ToolValidationException(message)
    }

    private fun JSONObject.requiredString(
        name: String,
        maxChars: Int = MAX_TEXT_BYTES,
        allowEmpty: Boolean = false,
    ): String {
        requireTool(has(name) && !isNull(name), "Campo obrigatório ausente: $name")
        val value = optString(name)
        requireTool(allowEmpty || value.isNotBlank(), "Campo vazio: $name")
        requireTool(value.length <= maxChars, "Campo $name excede $maxChars caracteres.")
        return value
    }

    private fun JSONObject.optNullableString(name: String): String? =
        if (!has(name) || isNull(name)) null else optString(name).takeIf(String::isNotBlank)

    private fun JSONObject.intIn(
        name: String,
        default: Int,
        minimum: Int,
        maximum: Int,
    ): Int {
        val value = if (has(name)) optInt(name, Int.MIN_VALUE) else default
        requireTool(value in minimum..maximum, "$name deve estar entre $minimum e $maximum.")
        return value
    }

    private fun String.sha256(): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun File.sha256File(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MAX_ARGUMENT_BYTES = 2_300_000
        const val MAX_TEXT_BYTES = 131_072
        const val MAX_QUERY_CHARS = 256
        const val MAX_LIST_ENTRIES = 200
        const val MAX_SEARCH_FILE_BYTES = 262_144L
        const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
        const val MAX_OCR_DIMENSION = 4_096
        const val MAX_NETWORK_RESPONSE_CHARS = 131_072
        const val DOWNLOAD_FREE_SPACE_RESERVE = 256L * 1024 * 1024
        const val MAX_HTTP_REDIRECTS = 5
        const val MAX_JAVASCRIPT_CHARS = 32_768
        const val MAX_JAVASCRIPT_RESULT_CHARS = 65_536
        const val JAVASCRIPT_TIMEOUT_SECONDS = 8L
        const val MAX_ARCHIVE_ENTRIES = 1_000
        const val MAX_ARCHIVE_BYTES = 64L * 1024 * 1024
        const val MAX_ARCHIVE_EXPANDED_BYTES = 128L * 1024 * 1024
        const val NETWORK_CONNECT_TIMEOUT_MS = 10_000
        const val NETWORK_READ_TIMEOUT_MS = 20_000
        val NETWORK_HOST_ALLOWLIST = setOf(
            "api.open-meteo.com",
            "geocoding-api.open-meteo.com",
            "api.github.com",
            "raw.githubusercontent.com",
            "api.duckduckgo.com",
        )
        val HTTP_REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val BLOCKED_HTTP_HOSTS = setOf(
            "localhost",
            "127.0.0.1",
            "0.0.0.0",
            "::1",
            "169.254.169.254",
            "metadata.google.internal",
        )
        const val DOWNLOAD_TOOL_SCHEMA =
            """{"type":"object","properties":{"url":{"type":"string","minLength":8,"maxLength":2048},"path":{"type":"string"},"max_bytes":{"type":"integer","minimum":0,"default":0,"description":"0 remove o teto fixo; usa o espaço disponível preservando a reserva do Android."},"overwrite":{"type":"boolean","default":false}},"required":["url","path"],"additionalProperties":false}"""
        const val PUBLISH_DOWNLOADS_SCHEMA =
            """{"type":"object","properties":{"source":{"type":"string","description":"Arquivo relativo ao workspace."},"path":{"type":"string","description":"Nome ou subpasta relativa dentro de Downloads."},"mime_type":{"type":"string","default":"application/octet-stream"},"overwrite":{"type":"boolean","default":false}},"required":["source","path"],"additionalProperties":false}"""
        const val EXTERNAL_PUBLISH_DOWNLOADS_SCHEMA =
            """{"type":"object","properties":{"source":{"type":"string","description":"Arquivo relativo a raiz SAF concedida."},"path":{"type":"string","description":"Nome ou subpasta relativa dentro de Downloads."},"mime_type":{"type":"string","default":""},"overwrite":{"type":"boolean","default":false}},"required":["source","path"],"additionalProperties":false}"""
        const val PATH_SCHEMA =
            """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"],"additionalProperties":false}"""
        const val ID_SCHEMA =
            """{"type":"object","properties":{"id":{"type":"string"}},"required":["id"],"additionalProperties":false}"""
        const val SOURCE_DESTINATION_SCHEMA =
            """{"type":"object","properties":{"source":{"type":"string"},"destination":{"type":"string"},"overwrite":{"type":"boolean","default":false}},"required":["source","destination"],"additionalProperties":false}"""
        const val PATCH_SCHEMA =
            """{"type":"object","properties":{"path":{"type":"string"},"patch":{"type":"string","maxLength":1048576},"expected_sha256":{"type":"string"}},"required":["path","patch"],"additionalProperties":false}"""
        const val REPLACE_LINES_SCHEMA =
            """{"type":"object","properties":{"path":{"type":"string"},"start_line":{"type":"integer","minimum":1},"end_line":{"type":"integer","minimum":0},"replacement":{"type":"string","maxLength":262144},"expected_sha256":{"type":"string"}},"required":["path","start_line","end_line","replacement"],"additionalProperties":false}"""
        const val SYMBOL_SCHEMA =
            """{"type":"object","properties":{"symbol":{"type":"string","minLength":1,"maxLength":200}},"required":["symbol"],"additionalProperties":false}"""
        const val TERMUX_COMMAND_SCHEMA =
            """{"type":"object","properties":{"command":{"type":"string","minLength":1,"maxLength":32768},"working_directory":{"type":"string"},"timeout_ms":{"type":"integer","minimum":1000,"maximum":1800000,"default":30000},"output_limit_bytes":{"type":"integer","minimum":1024,"maximum":4194304,"default":131072},"environment":{"type":"object","additionalProperties":{"type":"string","maxLength":4096},"maxProperties":32},"allocate_pty":{"type":"boolean","default":false}},"required":["command"],"additionalProperties":false}"""
        const val RUNTIME_COMMAND_SCHEMA =
            """{"type":"object","properties":{"command":{"type":"string","minLength":1,"maxLength":65536},"working_directory":{"type":"string","default":"."},"timeout_ms":{"type":"integer","minimum":1000,"maximum":1800000,"default":30000},"output_limit_bytes":{"type":"integer","minimum":1024,"maximum":4194304,"default":131072},"environment":{"type":"object","additionalProperties":{"type":"string","maxLength":4096},"maxProperties":32}},"required":["command"],"additionalProperties":false}"""
        const val RUNTIME_PACKAGE_SCHEMA =
            """{"type":"object","properties":{"name":{"type":"string","pattern":"^[a-z0-9][a-z0-9._-]{0,63}$"},"version":{"type":"string","minLength":1,"maxLength":64},"url":{"type":"string","minLength":8,"maxLength":2048},"sha256":{"type":"string","pattern":"^[A-Fa-f0-9]{64}$"},"format":{"type":"string","enum":["raw","zip"],"default":"zip"},"entrypoints":{"type":"object","minProperties":1,"maxProperties":128,"propertyNames":{"pattern":"^[A-Za-z_][A-Za-z0-9_]{0,63}$"},"additionalProperties":{"type":"object","properties":{"path":{"type":"string","minLength":1,"maxLength":512},"arguments":{"type":"array","items":{"type":"string","maxLength":1024},"maxItems":16}},"required":["path"],"additionalProperties":false}},"max_download_bytes":{"type":"integer","minimum":1024,"maximum":268435456,"default":67108864}},"required":["name","version","url","sha256","entrypoints"],"additionalProperties":false}"""
    }
}
