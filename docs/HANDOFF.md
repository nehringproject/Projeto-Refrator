# Refrator — resumo para quem for trabalhar no projeto

Leia isto antes de mexer no código. O app é um "workbench" Android de agentes de IA. A parte nova e
maior é a **Sociedade Prisma**: agentes com mascote ("Prismas") que conversam no privado, trabalham
juntos em grupos estilo WhatsApp, têm memória própria e um computador próprio no celular.

Estado em 2026-10-19 (branch `melhorias-composer-raciocinio-contexto`). Tudo abaixo compila e os testes
unitários passam. **"Verificado no aparelho" só vale onde está escrito.**

## Regras do dono do projeto

- Responda em português do Brasil, com acentuação correta. Textos do app também.
- Faça commit e push **só quando ele pedir**.
- Não coloque chaves, tokens ou senhas em código, logs ou commits. O keystore fica fora do repositório
  (`D:\AndroidDev\signing`).
- Não use assinatura Claude Pro/Max nem o Claude Code como "API escondida" dentro do chat do app
  (política da Anthropic). Para Claude, só chave de API ou o usuário rodando o Claude Code no terminal.
- Instale atualizações direto pelo ADB, mas não fique mexendo na tela do celular enquanto ele usa.
- Pacotes certos: Público `com.nehringproject.refrator`, Dev `dev.agentworkbench.power`.
  **`dev.agentworkbench` é um app antigo ("Agent Workbench"): nunca abra nem teste esse.**

## Compilar, testar e instalar (PC do dono, Windows, Git Bash)

```bash
cd /d/Projetos/Projeto-Refrator
source /d/AndroidDev/env.sh; source /d/AndroidDev/signing/signing.env
(py -3.13 -I D:/AndroidDev/dns-fallback-proxy.py >/dev/null 2>&1 &)   # proxy DNS em 127.0.0.1:8899
export HTTPS_PROXY=http://127.0.0.1:8899 HTTP_PROXY=http://127.0.0.1:8899 NO_PROXY=localhost,127.0.0.1
./gradlew.bat --no-daemon -q :app:compileDevDebugKotlin        # checagem rápida
./gradlew.bat --no-daemon -q :app:testDevDebugUnitTest         # testes
./gradlew.bat --no-daemon -q :app:assemblePublicRelease :app:assembleDevDebug
```

- APKs vão para `D:\Projetos\APKs` como `Refrator-vN-release.apk` e `Refrator-Dev-vN-debug.apk` (última: v21).
- ADB: `D:/AndroidDev/sdk/platform-tools/adb.exe`, celular por Wi-Fi (moto g35: Unisoc T760, 4 GB de RAM,
  Android 15, sem root, com Shizuku). Se cair, o dono precisa reativar a depuração sem fio.
- Ler dados de um grupo no aparelho: `adb exec-out run-as dev.agentworkbench.power cat files/groups/<id>.json`.
- **Nunca rode dois builds Gradle ao mesmo tempo** (dois agentes compilando juntos dá erro falso de cache/lock).
- Patches feitos por script Python: cuidado com `\n` dentro de strings Kotlin. Uma quebra de linha de verdade
  dentro de `"..."` quebra a compilação; isso já aconteceu três vezes.

## Flavors

- **Dev** (`dev.agentworkbench.power`, targetSdk 28): pode rodar proot e Ubuntu (execve direto), usa
  Acessibilidade e Shizuku à vontade. É o app que o dono usa.
- **Público** (`com.nehringproject.refrator`, targetSdk 36): sem proot (W^X). O app não vai para a Play Store
  por enquanto, então funções novas miram o Dev primeiro.

## Mapa do código (`app/src/main/kotlin/dev/agentworkbench`)

| Área | Arquivos |
|---|---|
| Prismas (modelo, cadastro) | `BotRepository.kt` (Bot: autonomia, limites, plugins, `providerPreset`/`modelId`), `PrismaModelPicker.kt` |
| Sociedade, grupos, rotinas, menções | `PrismaSociety.kt` (GroupStore, GroupMessage, tarefas, `Mentions`, DMs entre Prismas) |
| Motor dos grupos | `PrismaEngine.kt` (`GroupEngine`: um loop por membro, anti-coro, `[silêncio]`/`[ferramentas]`, watchdog, avisos de ambiente, retomada após morte do processo; `PrismaDispatcher`; `PrismaNotifier`) |
| Missões em segundo plano | `MissionEngine.kt` (tarefa do quadro trabalhando sozinha, `wake_me` via WorkManager, aprovação automática exceto ações sensíveis) |
| Ferramentas da sociedade | `SocietyTools.kt` (quadro, cadernos, DMs, criar Prisma/grupo/rotina, plugins nativos) |
| Memória | `PrismaMemory.kt` (`NotebookLogic` puro com 10 premissas; resumos do privado; caderno do time) |
| Computador próprio | `PrismaComputer.kt` (terminal), `PrismaBrowser.kt` (navegador), `PrismaComputerScreens.kt` (seção no perfil + tela de login) |
| Telas paralelas por Prisma | `PrismaDisplays.kt`, `ShizukuUserService.kt` (slots AIDL), `AgentTools.kt` (`shadow_*`) |
| Notificações | `PrismaNotifications.kt` (MessagingStyle, responder pela notificação, atalhos de conversa) |
| Manter vivo | `TeamKeepAlive.kt` (serviço em primeiro plano + wakelock + servidor local 127.0.0.1:8780) |
| Modelos e contas | `ProviderSettingsRepository.kt` (`settingsForPrisma`, `routesForPrisma`), `ProviderAccounts.kt`, `UsageTracking.kt` |
| UI | `HomeScreens.kt`, `GroupScreens.kt`, `GroupBoard.kt`, `AgendaScreens.kt`, `NotebookScreens.kt`, `PluginStore.kt`, `PrismaCity.kt`, `Mascots.kt`/`MascotMotion.kt`/`MascotActivities.kt`, `UiMotion.kt`, `ChatPanel.kt`, `MainActivity.kt` |
| Linux | `assets/terminal/bin/refrator-linux` (Ubuntu via proot), `EmbeddedRuntimePackManager.kt`, `TerminalPanel.kt` |

Testes em `app/src/test/kotlin/dev/agentworkbench` (NotebookLogic, Mentions, MascotMotion, MissionApproval e os antigos).

## Decisões importantes (não desfazer sem falar com o dono)

- **Grupos são livres**: sem rodadas e sem botão "Continuar". Todos escutam ao mesmo tempo, veem as mensagens
  uns dos outros, delegam como um time, marcam o usuário, e param sozinhos com base no contexto de uso
  (tokens, tempo sem o usuário falar). Não existe "limite de fôlego".
- **Modelo por Prisma é obrigatório e fixo**: escolhido ao criar/editar e gravado. Mudar o modelo do app não
  muda o dos Prismas, e um Prisma com provedor próprio nunca troca em silêncio para outro provedor
  (`routesForPrisma`). No chat de um Prisma, o menu tem "Modelo e edição de <nome>", e não "Modelo e contas".
- **Computador próprio**: Ubuntu compartilhado (um só, pacotes valem para todos, instalação livre), mas cada
  Prisma tem `/root`, `/tmp` e `/workspace` (= `files/workspace/prismas/<handle>/`) próprios e `/times`
  com as pastas dos times. No máximo 3 terminais acordados; um parado há 10 min dorme e volta na mesma pasta.
  Sem limite de espaço por Prisma.
- **Navegador próprio**: WebView invisível com perfil por Prisma (`androidx.webkit` ProfileStore), lido como
  texto numerado. O modelo **nunca** lê nem preenche senha: `web_login` abre a página na tela do usuário,
  ele entra e o login fica salvo no perfil daquele Prisma.
- **Pasta externa (SAF)**: o acesso usa cliente de provider *instável* (`DocumentTreeAccess.kt`), para o app
  não morrer quando o dono da pasta (ex.: Termux) morre. Não volte para `DocumentFile`/`contentResolver.query`.
- **Depois que o processo morre**, os grupos religam sozinhos e quem foi cortado recebe um aviso ♻️ para continuar.

Ferramentas novas dos Prismas: `computer_shell` (comando com estado; `background=true` para coisas longas),
`web_open`, `web_read`, `web_act` (vários passos numa chamada), `web_login`. As antigas `browser_*` (navegador
interno compartilhado, com screenshot) continuam existindo.

## O que está verificado e o que não está

- Verificado no aparelho: grupos livres, memória, missões e telas paralelas funcionaram em versões anteriores.
  A v21 (notificações, animações, modelo por Prisma, retomada) foi instalada, mas o dono ainda não reportou testes.
- **Não testado no aparelho**: terminal e navegador próprios (`computer_shell`, `web_*`, tela de login,
  seção "Computador"), modo obrigatório de modelo no seletor e as mudanças de design da última rodada.
- As sugestões de design (`D:\AndroidDev\reports\refrator-design-opinioes.md`, itens 7–12, 17–20) foram
  feitas por um agente que parou no meio: parte está aplicada (ícones vetoriais novos em `res/drawable`, por
  exemplo), parte não. Revise o que falta antes de declarar pronto.

## Próximos passos combinados

1. Testar no g35 o terminal e o navegador próprios (o Ubuntu do Dev já está instalado em
   `no_backup/internal-runtime/linux-runtime/distros/ubuntu`). Atenção ao "phantom process killer" do Android
   com vários proot ao mesmo tempo: o dono pode desligar em Opções do desenvolvedor.
2. Terminar as sugestões de design escolhidas (toque 48 dp e ícones; "⋮" nas linhas, botões nos estados vazios,
   cabeçalho do chat e tarefas mais limpos; cores, durações e contraste).
3. Depois: servidor de tela residente no Shizuku (toques em ms, quadro sempre pronto, foco próprio por tela),
   log cru das respostas do modelo para não perder raciocínio, e só então a conexão com o PC Windows
   (Tailscale + ponte MCP).

Relatórios de pesquisa com fontes e planos: `D:\AndroidDev\reports\` ("PC próprio para os Prismas",
"Prismas rápidos na tela e navegador", `refrator-plano-prismas.md`, `refrator-destaque-pesquisa.md`).
