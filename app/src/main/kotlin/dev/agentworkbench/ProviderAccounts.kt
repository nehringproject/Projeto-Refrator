package dev.agentworkbench

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private const val MANAGE_CHATGPT_USAGE_URL = "https://chatgpt.com/#settings"
private const val OPENCODE_ZEN_LOGIN_URL = "https://opencode.ai/auth"
private const val ANTHROPIC_KEYS_URL = "https://console.anthropic.com/settings/keys"

/**
 * "Modelo e contas": the main-screen way to connect a model provider through the browser.
 *  - ChatGPT: official Sign in with ChatGPT plan usage (OAuth, no key to copy).
 *  - OpenCode Zen: sign in on opencode.ai (Google/GitHub) and paste the generated key.
 *  - Claude: Anthropic does not allow Claude.ai plan logins in third-party apps, so this uses an
 *    API key from the Anthropic Console (which also accepts Google sign-in).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderAccountsSheet(
    settings: ProviderSettings,
    repository: ProviderSettingsRepository,
    allowLocalCleartext: Boolean,
    onSettingsChange: (ProviderSettings) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val auth = remember { ChatGptPlanAuth(context) }
    var account by remember { mutableStateOf(auth.account()) }
    var planModels by remember { mutableStateOf<List<ChatGptPlanAuth.PlanModel>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showWelcome by remember { mutableStateOf(false) }

    fun open(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Switches the active provider, keeping the user's mode and reasoning choices. */
    fun activate(preset: ProviderPreset, model: String? = null, apiKey: String = ""): Boolean {
        val target = repository.defaults(preset).let { base ->
            base.copy(
                modelId = model ?: base.modelId,
                executionMode = settings.executionMode,
                reasoningEffort = settings.reasoningEffort,
                continuousChat = settings.continuousChat,
            )
        }
        val error = repository.save(target, apiKey.trim(), allowLocalCleartext)
        message = error
        if (error == null) onSettingsChange(target)
        return error == null
    }

    LaunchedEffect(account) {
        if (account != null) planModels = auth.listModels()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = WorkbenchTokens.SurfaceRaised,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Modelo e contas", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                text = "Ativo: ${settings.preset.displayName} · ${settings.modelId}",
                style = MaterialTheme.typography.labelMedium,
                color = WorkbenchTokens.TextMuted,
            )

            AccountCard(title = "ChatGPT", subtitle = "Plano Go, Plus ou Pro · login oficial da OpenAI") {
                val current = account
                if (current == null) {
                    PrimaryPill(
                        text = if (busy) "Aguardando o navegador…" else "Continuar com ChatGPT",
                        enabled = !busy,
                    ) {
                        busy = true
                        message = null
                        scope.launch {
                            runCatching { auth.signIn { uri -> open(uri.toString()) } }
                                .onSuccess { signed ->
                                    account = signed
                                    if (activate(ProviderPreset.CHATGPT_PLAN)) showWelcome = auth.consumeWelcome()
                                }
                                .onFailure { message = it.message ?: "Não foi possível entrar com o ChatGPT." }
                            busy = false
                        }
                    }
                    Text(
                        text = "Abre o navegador para você entrar (inclusive com Google). Pedidos elegíveis usam " +
                            "seu plano, sem chave de API.",
                        style = MaterialTheme.typography.labelSmall,
                        color = WorkbenchTokens.TextFaint,
                    )
                } else {
                    Text(
                        text = "Conectado${current.email?.let { " como $it" }.orEmpty()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = WorkbenchTokens.Text,
                    )
                    if (settings.preset == ProviderPreset.CHATGPT_PLAN) {
                        Text("Usando plano do ChatGPT", style = MaterialTheme.typography.labelMedium, color = WorkbenchTokens.TextMuted)
                    }
                    if (planModels.isNotEmpty()) {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            planModels.forEach { model ->
                                val selected = settings.preset == ProviderPreset.CHATGPT_PLAN && settings.modelId == model.slug
                                ChoiceChip(model.displayName, selected) { activate(ProviderPreset.CHATGPT_PLAN, model.slug) }
                            }
                        }
                    } else if (settings.preset != ProviderPreset.CHATGPT_PLAN) {
                        PrimaryPill("Usar ChatGPT", enabled = true) { activate(ProviderPreset.CHATGPT_PLAN) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { open(MANAGE_CHATGPT_USAGE_URL) }) { Text("Gerenciar uso", color = WorkbenchTokens.Text) }
                        TextButton(onClick = {
                            auth.signOut()
                            account = null
                            planModels = emptyList()
                        }) { Text("Sair", color = WorkbenchTokens.TextMuted) }
                    }
                }
            }

            KeyAccountCard(
                title = "OpenCode Zen",
                subtitle = "Entre no opencode.ai (Google ou GitHub), gere uma chave e cole aqui.",
                loginLabel = "Entrar no OpenCode Zen",
                loginUrl = OPENCODE_ZEN_LOGIN_URL,
                connected = repository.hasApiKey(repository.defaults(ProviderPreset.OPENCODE_ZEN)),
                active = settings.preset == ProviderPreset.OPENCODE_ZEN,
                onOpen = ::open,
                onConnect = { key -> activate(ProviderPreset.OPENCODE_ZEN, apiKey = key) },
            )

            KeyAccountCard(
                title = "Claude",
                subtitle = "A Anthropic não permite usar o plano Pro/Max (login do claude.ai) em apps de terceiros. " +
                    "Use uma chave de API do Console (pago por uso; dá para entrar com Google).",
                loginLabel = "Abrir Console da Anthropic",
                loginUrl = ANTHROPIC_KEYS_URL,
                connected = repository.hasApiKey(repository.defaults(ProviderPreset.ANTHROPIC)),
                active = settings.preset == ProviderPreset.ANTHROPIC,
                onOpen = ::open,
                onConnect = { key -> activate(ProviderPreset.ANTHROPIC, apiKey = key) },
            )

            message?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error) }

            TextButton(onClick = onOpenSettings) {
                Text("Mais provedores e ajustes", color = WorkbenchTokens.TextMuted)
            }
        }
    }

    if (showWelcome) {
        AlertDialog(
            onDismissRequest = { showWelcome = false },
            containerColor = WorkbenchTokens.SurfaceRaised,
            title = { Text("Você está usando seu plano do ChatGPT") },
            text = {
                Text(
                    "Pedidos de IA elegíveis do Refrator usam o seu plano do ChatGPT. Você pode limitar ou " +
                        "acompanhar esse uso nas configurações do ChatGPT. O Refrator não cobra nada à parte.",
                    color = WorkbenchTokens.TextMuted,
                )
            },
            confirmButton = { TextButton(onClick = { showWelcome = false }) { Text("Entendi") } },
            dismissButton = { TextButton(onClick = { open(MANAGE_CHATGPT_USAGE_URL) }) { Text("Gerenciar uso") } },
        )
    }
}

@Composable
private fun AccountCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, WorkbenchTokens.BorderSoft, RoundedCornerShape(18.dp))
            .background(WorkbenchTokens.Surface)
            .padding(16.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = WorkbenchTokens.Text)
        Text(subtitle, style = MaterialTheme.typography.labelMedium, color = WorkbenchTokens.TextMuted)
        content()
    }
}

@Composable
private fun KeyAccountCard(
    title: String,
    subtitle: String,
    loginLabel: String,
    loginUrl: String,
    connected: Boolean,
    active: Boolean,
    onOpen: (String) -> Unit,
    onConnect: (String) -> Boolean,
) {
    val context = LocalContext.current
    var key by remember { mutableStateOf("") }
    AccountCard(title = title, subtitle = subtitle) {
        if (connected) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.size(7.dp).background(WorkbenchTokens.Green, CircleShape))
                Text(
                    text = if (active) "Conectado · em uso" else "Conectado",
                    style = MaterialTheme.typography.labelMedium,
                    color = WorkbenchTokens.Text,
                )
                if (!active) {
                    TextButton(onClick = { onConnect("") }) { Text("Usar", color = WorkbenchTokens.Text) }
                }
            }
        }
        PrimaryPill(loginLabel, enabled = true) { onOpen(loginUrl) }
        OutlinedTextField(
            value = key,
            onValueChange = { key = it.trim() },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(if (connected) "Trocar chave (opcional)" else "Cole a chave aqui", color = WorkbenchTokens.TextFaint) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = WorkbenchTokens.Border,
                unfocusedBorderColor = WorkbenchTokens.BorderSoft,
                cursorColor = WorkbenchTokens.Text,
            ),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = {
                val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                key = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
            }) { Text("Colar", color = WorkbenchTokens.TextMuted) }
            TextButton(
                enabled = key.isNotBlank(),
                onClick = { if (onConnect(key)) key = "" },
            ) { Text("Conectar", color = if (key.isNotBlank()) WorkbenchTokens.Text else WorkbenchTokens.TextFaint) }
        }
    }
}

@Composable
private fun PrimaryPill(text: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        color = if (enabled) WorkbenchTokens.Text else WorkbenchTokens.SurfaceHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!enabled) CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = WorkbenchTokens.TextMuted)
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) WorkbenchTokens.Canvas else WorkbenchTokens.TextMuted,
            )
        }
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) WorkbenchTokens.SurfaceHigh else WorkbenchTokens.Surface,
        border = BorderStroke(1.dp, if (selected) WorkbenchTokens.TextMuted else WorkbenchTokens.BorderSoft),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) WorkbenchTokens.Text else WorkbenchTokens.TextMuted,
        )
    }
}
