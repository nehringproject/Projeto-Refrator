package dev.agentworkbench

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.agentworkbench.provider.http.ModelCatalogResult
import dev.agentworkbench.provider.http.ProviderModelOption
import dev.agentworkbench.core.ExecutionMode
import kotlinx.coroutines.launch

@Composable
fun ProviderSettingsPanel(
    repository: ProviderSettingsRepository,
    contextMemoryRepository: ContextMemoryRepository,
    agentPlatformRepository: AgentPlatformRepository,
    workspaceId: String,
    conversationId: String?,
    settings: ProviderSettings,
    availableSkills: List<AgentSkill>,
    enabledSkillIds: Set<String>,
    onSkillToggled: (String, Boolean) -> Unit,
    allowLocalCleartext: Boolean,
    onAllowLocalCleartextChanged: (Boolean) -> Unit,
    onSaved: (ProviderSettings) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var draft by remember(settings) { mutableStateOf(settings) }
    var apiKeyDraft by remember(settings) { mutableStateOf("") }
    var managedPoolPreset by remember { mutableStateOf(ProviderPreset.NVIDIA) }
    var managedPoolMenuOpen by remember { mutableStateOf(false) }
    var keyRevision by remember { mutableIntStateOf(0) }
    var catalogLoading by remember { mutableStateOf(false) }
    var localImportLoading by remember { mutableStateOf(false) }
    var localInfo by remember { mutableStateOf(repository.localGgufInfo()) }
    var catalogQuery by remember(draft.preset, draft.endpoint) { mutableStateOf("") }
    var catalogModels by remember(draft.preset, draft.endpoint) {
        mutableStateOf<List<ProviderModelOption>>(emptyList())
    }
    var status by remember(settings) {
        mutableStateOf("${settings.preset.displayName} é o provider ativo.")
    }
    var globalPrompt by remember { mutableStateOf("") }
    var workspacePrompt by remember(workspaceId) { mutableStateOf("") }
    var chatPrompt by remember(conversationId) { mutableStateOf("") }
    var globalEnabled by remember { mutableStateOf(false) }
    var workspaceEnabled by remember(workspaceId) { mutableStateOf(false) }
    var chatEnabled by remember(conversationId) { mutableStateOf(false) }
    var contextStatus by remember { mutableStateOf("Carregando instruções…") }
    var memories by remember { mutableStateOf<List<MemoryEntry>>(emptyList()) }
    var memoryDraft by remember { mutableStateOf("") }
    var memorySearch by remember { mutableStateOf("") }
    var memoryScope by remember { mutableStateOf(InstructionScope.WORKSPACE) }

    LaunchedEffect(contextMemoryRepository, workspaceId, conversationId) {
        contextMemoryRepository.instruction(
            InstructionScope.GLOBAL,
            ContextMemoryRepository.GLOBAL_SCOPE_ID,
        )?.let { value ->
            globalPrompt = value.body
            globalEnabled = value.state == InstructionState.ENABLED.name
        }
        contextMemoryRepository.instruction(InstructionScope.WORKSPACE, workspaceId)
            ?.let { value ->
                workspacePrompt = value.body
                workspaceEnabled = value.state == InstructionState.ENABLED.name
            }
        conversationId?.let { id ->
            contextMemoryRepository.instruction(InstructionScope.CHAT, id)
                ?.let { value ->
                    chatPrompt = value.body
                    chatEnabled = value.state == InstructionState.ENABLED.name
                }
        }
        contextStatus = "Instruções carregadas."
        memories = contextMemoryRepository.memories()
    }

    val keyConfigured = remember(draft, keyRevision) {
        draft.preset != ProviderPreset.DEMO && repository.hasApiKey(draft)
    }
    val dirty = draft != settings || apiKeyDraft.isNotEmpty()
    val canLoadCatalog =
        !catalogLoading &&
            draft.preset == settings.preset &&
            draft.endpoint == settings.endpoint &&
            apiKeyDraft.isEmpty() &&
            (
                draft.preset == ProviderPreset.DEMO ||
                    !draft.preset.requiresApiKey ||
                    keyConfigured
                )
    val filteredModels = remember(catalogModels, catalogQuery) {
        val query = catalogQuery.trim()
        if (query.isEmpty()) {
            catalogModels
        } else {
            catalogModels.filter { model ->
                model.id.contains(query, ignoreCase = true) ||
                    model.name?.contains(query, ignoreCase = true) == true ||
                    model.owner?.contains(query, ignoreCase = true) == true
            }
        }
    }
    val managedPoolEntries = remember(draft, keyRevision) {
        repository.managedLiteLlmPoolEntries(draft)
    }
    fun saveDraft() {
        val normalized = draft.copy(
            endpoint = draft.endpoint.trim(),
            modelId = draft.modelId.trim(),
        )
        val error = repository.save(
            settings = normalized,
            apiKey = apiKeyDraft,
            allowLocalCleartext = allowLocalCleartext,
            managedPoolPreset = managedPoolPreset,
        )
        if (error == null) {
            draft = normalized
            apiKeyDraft = ""
            keyRevision += 1
            onSaved(normalized)
            status = if (
                normalized.preset == ProviderPreset.LITELLM &&
                normalized.endpoint == MANAGED_LITELLM_ENDPOINT
            ) {
                "LiteLLM salvo · ${repository.managedLiteLlmCredentialCount()} credenciais · " +
                    repository.managedLiteLlmPoolSummary() + "."
            } else {
                "${normalized.preset.displayName} salvo e ativo."
            }
        } else {
            status = error
        }
    }
    val ggufLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            localImportLoading = true
            status = "Importando e validando GGUF…"
            scope.launch {
                runCatching { repository.importLocalGguf(uri) }
                    .onSuccess { imported ->
                        localInfo = imported
                        draft = draft.copy(modelId = imported.displayName)
                        status = "GGUF pronto: ${imported.displayName} (${imported.bytes / 1_048_576} MiB)."
                    }
                    .onFailure { error ->
                        status = error.message ?: "Falha ao importar o GGUF."
                    }
                localImportLoading = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Provider",
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = "Escolha onde o modelo roda. O Chat mostra o modelo pedido e " +
                "o identificador confirmado pelo servidor.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ProviderPreset.entries.chunked(2).forEach { rowPresets ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowPresets.forEach { preset ->
                            val selected = draft.preset == preset
                            if (selected) {
                                Button(
                                    modifier = Modifier.weight(1f),
                                    onClick = {},
                                ) {
                                    Text(preset.displayName)
                                }
                            } else {
                                OutlinedButton(
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        draft = repository.defaults(preset).copy(
                                            executionMode = draft.executionMode,
                                            continuousChat = draft.continuousChat,
                                        )
                                        apiKeyDraft = ""
                                        status = preset.description
                                    },
                                ) {
                                    Text(preset.displayName)
                                }
                            }
                        }
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = draft.preset.displayName,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = draft.preset.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (
                    draft.preset == ProviderPreset.LITELLM &&
                    draft.endpoint.trim() == MANAGED_LITELLM_ENDPOINT
                ) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("Como funciona", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "O LiteLLM roda dentro do Refrator. Cada credencial vira um deployment " +
                                    "independente. O roteador distribui novas chamadas, aplica cooldown em " +
                                    "429/5xx e tenta os próximos deployments antes de devolver erro. O app " +
                                    "continua responsável por ferramentas, memória e recuperação.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                if (BuildConfig.DEVELOPER_BUILD) {
                                    "Android 9+ em ARM64; o Dev também inclui x86_64 para emuladores. " +
                                        "As chaves ficam no cofre Android e chegam ao worker somente em memória."
                                } else {
                                    "Android 9+ em ARM64. As chaves ficam no cofre Android e chegam " +
                                        "ao worker somente em memória."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "Pool atual: ${repository.managedLiteLlmCredentialCount()} credenciais · " +
                                    repository.managedLiteLlmPoolSummary(),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(
                                "Cada deployment usa o endpoint e o modelo salvos no perfil daquele " +
                                    "provider. Para trocar um modelo, abra o provider individual, carregue " +
                                    "o catálogo, selecione o modelo e salve; o pool passa a usá-lo.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            HorizontalDivider()
                            Text("Rotação ativa", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Estratégia simple-shuffle · 2 retries · até " +
                                    "${managedPoolEntries.sumOf { it.deploymentCount }.coerceAtLeast(1) - 1} " +
                                    "fallbacks · cooldown de 60 s após falha.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (managedPoolEntries.isEmpty()) {
                                Text(
                                    "Nenhuma rota pronta. Escolha um provider e adicione uma API key.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            } else {
                                managedPoolEntries.forEach { entry ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                        ),
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(10.dp),
                                            verticalArrangement = Arrangement.spacedBy(3.dp),
                                        ) {
                                            Text(
                                                "${entry.provider.displayName} · ${entry.deploymentCount} deployment(s)",
                                                style = MaterialTheme.typography.labelLarge,
                                            )
                                            Text(
                                                entry.modelId,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = FontFamily.Monospace,
                                            )
                                            Text(
                                                entry.endpointHost,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            if (entry.credentialFingerprints.isEmpty()) {
                                                Text(
                                                    "Sem chave · endpoint local ou autenticação opcional",
                                                    style = MaterialTheme.typography.labelSmall,
                                                )
                                            } else {
                                                entry.credentialFingerprints.forEachIndexed { index, fingerprint ->
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically,
                                                    ) {
                                                        Text(
                                                            "API ${index + 1}: $fingerprint",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            fontFamily = FontFamily.Monospace,
                                                        )
                                                        TextButton(
                                                            onClick = {
                                                                val removed = repository
                                                                    .removeManagedLiteLlmCredential(
                                                                        draft,
                                                                        entry.provider,
                                                                        fingerprint,
                                                                    )
                                                                keyRevision += 1
                                                                status = if (removed) {
                                                                    "Credencial ${entry.provider.displayName} removida do pool."
                                                                } else {
                                                                    "A credencial já não estava no pool."
                                                                }
                                                            },
                                                        ) {
                                                            Text("Remover")
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (draft.preset == ProviderPreset.LOCAL_GGUF) {
                    Text(
                        text = localInfo?.let {
                            "${it.displayName} · ${it.bytes / 1_048_576} MiB · SHA-256 ${it.sha256.take(12)}…"
                        } ?: "Nenhum modelo GGUF importado.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    localInfo?.let { info ->
                        Text(
                            text = buildString {
                                append("Arquitetura: ${info.architecture ?: "não informada"}")
                                info.contextLength?.let { append(" · contexto treinado: $it") }
                                info.fileType?.let { append(" · quantização GGML: $it") }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !localImportLoading,
                        onClick = { ggufLauncher.launch(arrayOf("*/*")) },
                    ) {
                        if (localImportLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text(if (localInfo == null) "Selecionar GGUF em Downloads" else "Trocar arquivo GGUF")
                        }
                    }
                    Text(
                        text = localInfo?.let { info ->
                            val profile = repository.localGgufRuntimeProfile(info)
                            "Perfil automático: ${profile.contextTokens} tokens neste aparelho. " +
                                "RAM e tamanho do modelo definem o contexto; aparelhos melhores liberam janelas maiores. " +
                                "O modelo é descarregado após 90 s ocioso."
                        } ?: "O app valida estrutura, arquitetura e tensores antes de importar e ajusta o contexto à RAM disponível.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (draft.preset != ProviderPreset.DEMO) {
                    OutlinedTextField(
                        value = draft.endpoint,
                        onValueChange = { draft = draft.copy(endpoint = it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Endpoint completo") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = draft.modelId,
                        onValueChange = { draft = draft.copy(modelId = it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Model ID solicitado") },
                        enabled = !(
                            draft.preset == ProviderPreset.LITELLM &&
                                draft.endpoint.trim() == MANAGED_LITELLM_ENDPOINT
                            ),
                        supportingText = if (
                            draft.preset == ProviderPreset.LITELLM &&
                            draft.endpoint.trim() == MANAGED_LITELLM_ENDPOINT
                        ) {
                            { Text("Gerenciado pelo pool: rotação e fallback automáticos.") }
                        } else {
                            null
                        },
                        singleLine = true,
                    )
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = canLoadCatalog,
                        onClick = {
                            catalogLoading = true
                            status = "Carregando modelos de ${draft.preset.displayName}…"
                            scope.launch {
                                when (
                                    val result = repository.loadModelCatalog(
                                        settings = draft,
                                        allowLocalCleartext = allowLocalCleartext,
                                    )
                                ) {
                                    is ModelCatalogResult.Success -> {
                                        catalogModels = result.models
                                        status = if (result.models.isEmpty()) {
                                            "O provider retornou um catálogo vazio."
                                        } else {
                                            "${result.models.size} modelos carregados."
                                        }
                                    }

                                    is ModelCatalogResult.Failure -> {
                                        catalogModels = emptyList()
                                        status = result.safeMessage
                                    }
                                }
                                catalogLoading = false
                            }
                        },
                    ) {
                        if (catalogLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("Carregar modelos do provider")
                        }
                    }
                    if (catalogModels.isNotEmpty()) {
                        OutlinedTextField(
                            value = catalogQuery,
                            onValueChange = { catalogQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Filtrar modelos") },
                            singleLine = true,
                        )
                        Text(
                            text = "${filteredModels.size} de ${catalogModels.size} modelos",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            filteredModels.take(MAX_VISIBLE_MODELS).forEach { model ->
                                ModelChoice(
                                    model = model,
                                    selected = draft.modelId == model.id,
                                    onClick = {
                                        draft = draft.copy(modelId = model.id)
                                        status = "Modelo selecionado: ${model.id}"
                                    },
                                )
                            }
                            if (filteredModels.size > MAX_VISIBLE_MODELS) {
                                Text(
                                    text = "Refine o filtro para ver os demais modelos.",
                                    modifier = Modifier.padding(12.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    if (
                        draft.preset == ProviderPreset.LITELLM &&
                        draft.endpoint.trim() == MANAGED_LITELLM_ENDPOINT
                    ) {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { managedPoolMenuOpen = true },
                            ) {
                                Text("Provider da credencial: ${managedPoolPreset.displayName}")
                            }
                            DropdownMenu(
                                expanded = managedPoolMenuOpen,
                                onDismissRequest = { managedPoolMenuOpen = false },
                            ) {
                                repository.managedLiteLlmSupportedPresets().forEach { preset ->
                                    DropdownMenuItem(
                                        text = { Text(preset.displayName) },
                                        onClick = {
                                            managedPoolPreset = preset
                                            managedPoolMenuOpen = false
                                            apiKeyDraft = ""
                                            status = "Adicione uma credencial ${preset.displayName} ao pool."
                                        },
                                    )
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = apiKeyDraft,
                        onValueChange = { apiKeyDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(
                                if (keyConfigured) {
                                    "Nova API key (vazio mantém a atual)"
                                } else if (!draft.preset.requiresApiKey) {
                                    if (
                                        draft.preset == ProviderPreset.LITELLM &&
                                        draft.endpoint.trim() == MANAGED_LITELLM_ENDPOINT
                                    ) {
                                        "API key ${managedPoolPreset.displayName} (adicionar ao pool)"
                                    } else {
                                        "API key (opcional)"
                                    }
                                } else {
                                    "API key"
                                },
                            )
                        },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = dirty,
                    onClick = ::saveDraft,
                ) {
                    Text(
                        if (draft.preset == ProviderPreset.DEMO) {
                            "Usar Demo local"
                        } else if (draft.preset == ProviderPreset.LOCAL_GGUF) {
                            "Usar modelo GGUF local"
                        } else {
                            "Salvar provider"
                        },
                    )
                }

                if (keyConfigured) {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            repository.clearApiKey(draft)
                            apiKeyDraft = ""
                            keyRevision += 1
                            status = if (
                                draft.preset == ProviderPreset.LITELLM &&
                                draft.endpoint.trim() == MANAGED_LITELLM_ENDPOINT
                            ) {
                                "Pool LiteLLM esvaziado. Perfis individuais foram preservados."
                            } else {
                                "API key removida do cofre."
                            }
                        },
                    ) {
                        Text(
                            if (
                                draft.preset == ProviderPreset.LITELLM &&
                                draft.endpoint.trim() == MANAGED_LITELLM_ENDPOINT
                            ) {
                                "Remover todas as credenciais do pool"
                            } else {
                                "Remover API key"
                            },
                        )
                    }
                }

                Text(
                    text = status,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Contexto permanente", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Precedência: chat › workspace › hard prompt global › memória aprendida.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                InstructionEditor(
                    title = "Hard prompt global",
                    description = "Injetado no início de toda chamada de modelo, inclusive em background.",
                    value = globalPrompt,
                    enabled = globalEnabled,
                    onEnabledChange = { globalEnabled = it },
                    onValueChange = { globalPrompt = it },
                )
                HorizontalDivider()
                InstructionEditor(
                    title = "Instruções deste workspace",
                    description = "Regras do projeto atual, acima do hard prompt global.",
                    value = workspacePrompt,
                    enabled = workspaceEnabled,
                    onEnabledChange = { workspaceEnabled = it },
                    onValueChange = { workspacePrompt = it },
                )
                if (conversationId != null) {
                    HorizontalDivider()
                    InstructionEditor(
                        title = "Instruções deste chat",
                        description = "A camada explícita de maior prioridade desta conversa.",
                        value = chatPrompt,
                        enabled = chatEnabled,
                        onEnabledChange = { chatEnabled = it },
                        onValueChange = { chatPrompt = it },
                    )
                }
                val contextCharacters = globalPrompt.length + workspacePrompt.length + chatPrompt.length
                Text(
                    "≈ ${ContextMemoryRepository.estimateTokens("x".repeat(contextCharacters))} tokens explícitos",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        scope.launch {
                            runCatching {
                                contextMemoryRepository.setInstruction(
                                    InstructionScope.GLOBAL,
                                    ContextMemoryRepository.GLOBAL_SCOPE_ID,
                                    globalPrompt,
                                    globalEnabled,
                                )
                                contextMemoryRepository.setInstruction(
                                    InstructionScope.WORKSPACE,
                                    workspaceId,
                                    workspacePrompt,
                                    workspaceEnabled,
                                )
                                conversationId?.let { id ->
                                    contextMemoryRepository.setInstruction(
                                        InstructionScope.CHAT,
                                        id,
                                        chatPrompt,
                                        chatEnabled,
                                    )
                                }
                            }.onSuccess {
                                contextStatus = "Contexto permanente salvo e ativo."
                            }.onFailure { error ->
                                contextStatus = error.message ?: "Falha ao salvar contexto."
                            }
                        }
                    },
                ) {
                    Text("Salvar instruções")
                }
                Text(contextStatus, style = MaterialTheme.typography.labelMedium)
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Central de memória", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Memórias são aprendizados auditáveis; nunca substituem instruções explícitas.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = memoryDraft,
                    onValueChange = { memoryDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Nova memória manual") },
                    minLines = 2,
                    maxLines = 6,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                        InstructionScope.GLOBAL to "Global",
                        InstructionScope.WORKSPACE to "Workspace",
                        InstructionScope.CHAT to "Chat",
                    ).forEach { (candidate, label) ->
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            enabled = candidate != InstructionScope.CHAT || conversationId != null,
                            onClick = { memoryScope = candidate },
                        ) { Text(if (memoryScope == candidate) "✓ $label" else label) }
                    }
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = memoryDraft.isNotBlank(),
                    onClick = {
                        scope.launch {
                            val scopeId = when (memoryScope) {
                                InstructionScope.GLOBAL -> ContextMemoryRepository.GLOBAL_SCOPE_ID
                                InstructionScope.WORKSPACE -> workspaceId
                                InstructionScope.CHAT -> conversationId ?: return@launch
                            }
                            runCatching {
                                contextMemoryRepository.addMemory(
                                    memoryScope,
                                    scopeId,
                                    "learning",
                                    memoryDraft,
                                    sourceConversationId = conversationId,
                                    confidence = 1.0,
                                    pinned = true,
                                )
                            }.onSuccess {
                                memoryDraft = ""
                                memories = contextMemoryRepository.memories()
                                contextStatus = "Memória salva."
                            }.onFailure { error ->
                                contextStatus = error.message ?: "Memória recusada."
                            }
                        }
                    },
                ) { Text("Adicionar memória") }
                OutlinedTextField(
                    value = memorySearch,
                    onValueChange = { memorySearch = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Buscar memória") },
                    singleLine = true,
                )
                memories.filter { memory ->
                    memorySearch.isBlank() ||
                        memory.body.contains(memorySearch, ignoreCase = true) ||
                        memory.category.contains(memorySearch, ignoreCase = true)
                }.take(30).forEach { memory ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                "${memory.scopeType.lowercase()} · ${memory.category} · ${memory.state}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(memory.body, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "confiança ${"%.2f".format(memory.confidence)} · origem ${memory.sourceConversationId ?: "manual"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            val next = if (memory.state == MemoryState.ACTIVE.name) {
                                                MemoryState.DISABLED
                                            } else {
                                                MemoryState.ACTIVE
                                            }
                                            contextMemoryRepository.updateMemory(
                                                memory,
                                                memory.body,
                                                "Alterado na Central de Memória",
                                                "user",
                                                next,
                                            )
                                            memories = contextMemoryRepository.memories()
                                        }
                                    },
                                ) {
                                    Text(if (memory.state == MemoryState.ACTIVE.name) "Desativar" else "Ativar")
                                }
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            contextMemoryRepository.updateMemory(
                                                memory,
                                                memory.body,
                                                "Excluída na Central de Memória",
                                                "user",
                                                MemoryState.DELETED,
                                            )
                                            memories = contextMemoryRepository.memories()
                                        }
                                    },
                                ) { Text("Excluir") }
                            }
                        }
                    }
                }
            }
        }

        AgentPlatformSettingsCard(
            repository = agentPlatformRepository,
            settings = settings,
            availableSkills = availableSkills,
        )

        DistributionCapabilityPanel()

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Chat contínuo",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "Compacta o contexto e mantém a conversa utilizável por tempo indefinido.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = draft.continuousChat.enabled,
                        onCheckedChange = { enabled ->
                            draft = draft.copy(
                                continuousChat = draft.continuousChat.copy(enabled = enabled),
                            )
                        },
                    )
                }

                if (draft.continuousChat.enabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Troca automática de provider")
                            Text(
                                text = "Se um provider falhar ou limitar a requisição, tenta o próximo configurado.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = draft.continuousChat.automaticProviderSwitching,
                            onCheckedChange = { enabled ->
                                draft = draft.copy(
                                    continuousChat = draft.continuousChat.copy(
                                        automaticProviderSwitching = enabled,
                                    ),
                                )
                            },
                        )
                    }

                    OutlinedTextField(
                        value = draft.continuousChat.contextWindowTokens.toString(),
                        onValueChange = { value ->
                            value.filter(Char::isDigit).toIntOrNull()?.let { tokens ->
                                draft = draft.copy(
                                    continuousChat = draft.continuousChat.copy(
                                        contextWindowTokens = tokens.coerceIn(4_096, 2_000_000),
                                    ),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Janela de contexto do modelo (tokens)") },
                        supportingText = {
                            Text("Use o limite documentado do modelo ativo; padrão conservador: 32768.")
                        },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = draft.continuousChat.compactionThresholdPercent.toString(),
                        onValueChange = { value ->
                            value.filter(Char::isDigit).toIntOrNull()?.let { percent ->
                                draft = draft.copy(
                                    continuousChat = draft.continuousChat.copy(
                                        compactionThresholdPercent = percent.coerceIn(50, 95),
                                    ),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Compactar ao atingir (%)") },
                        supportingText = { Text("Faixa segura: 50–95%. Recomendado: 75%.") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = draft.continuousChat.recentMessagesToKeep.toString(),
                        onValueChange = { value ->
                            value.filter(Char::isDigit).toIntOrNull()?.let { count ->
                                draft = draft.copy(
                                    continuousChat = draft.continuousChat.copy(
                                        recentMessagesToKeep = count.coerceIn(4, 80),
                                    ),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Mensagens recentes preservadas no contexto") },
                        singleLine = true,
                    )

                    if (draft.continuousChat.automaticProviderSwitching) {
                        HorizontalDivider()
                        Text(
                            text = "Pool de continuidade",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = "Somente providers com perfil e credencial válidos entram na rotação.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ProviderPreset.entries
                            .filterNot { it == ProviderPreset.DEMO }
                            .forEach { preset ->
                                val ready = repository.isContinuityProviderReady(
                                    preset = preset,
                                    active = draft,
                                    allowLocalCleartext = allowLocalCleartext,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(preset.displayName)
                                        Text(
                                            text = if (ready) "Configurado" else "Ainda não configurado",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (ready) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        )
                                    }
                                    Switch(
                                        checked = preset in draft.continuousChat.providerPool,
                                        onCheckedChange = { enabled ->
                                            val pool = draft.continuousChat.providerPool.toMutableSet()
                                            if (enabled) pool += preset else pool -= preset
                                            draft = draft.copy(
                                                continuousChat = draft.continuousChat.copy(
                                                    providerPool = pool,
                                                ),
                                            )
                                        },
                                    )
                                }
                            }
                    }
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = dirty,
                    onClick = ::saveDraft,
                ) {
                    Text("Salvar modo contínuo")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Skills",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Instruções especializadas injetadas no contexto do agente.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                availableSkills.forEach { skill ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = skill.name,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                text = skill.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = skill.id in enabledSkillIds,
                            onCheckedChange = { enabled ->
                                onSkillToggled(skill.id, enabled)
                            },
                        )
                    }
                }
                Text(
                    text = "${enabledSkillIds.size} de ${availableSkills.size} skills ativas",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Modo de execução",
                    style = MaterialTheme.typography.titleMedium,
                )
                listOf(
                    ExecutionMode.PLAN to "Planejar",
                    ExecutionMode.BUILD to "Confirmar",
                    ExecutionMode.AUTO to "Auto",
                    ExecutionMode.FULL to "Livre",
                ).chunked(2).forEach { modes ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        modes.forEach { (mode, label) ->
                            if (draft.executionMode == mode) {
                                Button(
                                    modifier = Modifier.weight(1f),
                                    onClick = {},
                                ) {
                                    Text(label)
                                }
                            } else {
                                OutlinedButton(
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        val updated = draft.copy(executionMode = mode)
                                        repository.saveExecutionMode(mode)
                                        draft = updated
                                        onSaved(updated)
                                        status = "Modo ${label.lowercase()} ativo."
                                    },
                                ) {
                                    Text(label)
                                }
                            }
                        }
                    }
                }
                Text(
                    text = when (draft.executionMode) {
                        ExecutionMode.FULL ->
                            "Livre executa ações comuns sem interromper. Shizuku/Android ainda " +
                                "pedem seus próprios consentimentos e comandos críticos do " +
                                "sistema continuam exigindo confirmação."

                        ExecutionMode.AUTO ->
                            "Auto executa leituras e mudanças reversíveis no workspace; " +
                                "cruzamentos de segurança ainda pedem confirmação."

                        ExecutionMode.PLAN ->
                            "Planejar permite investigar e formular um plano, mas bloqueia " +
                                "mudanças até você trocar para um modo de execução."

                        else ->
                            "Confirmar pede sua aprovação antes de escrita, rede sensível e shell."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Segurança",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "API keys são cifradas pelo Android Keystore, ficam no " +
                        "sandbox do app e são vinculadas à origem HTTPS.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = if (allowLocalCleartext) {
                        "O Refrator permite HTTP apenas para endereços locais e nunca envia " +
                            "uma API key por HTTP."
                    } else {
                        "Este build bloqueia transporte HTTP sem TLS."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("HTTP na rede local", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Ative somente para serviços locais sem TLS, como Ollama ou LiteLLM na sua rede.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = allowLocalCleartext,
                        onCheckedChange = onAllowLocalCleartextChanged,
                    )
                }
            }
        }

        if (dirty) {
            Text(
                text = "Há alterações não salvas.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun InstructionEditor(
    title: String,
    description: String,
    value: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onValueChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = onEnabledChange)
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        minLines = 4,
        maxLines = 12,
        label = { Text(title) },
        supportingText = {
            Text("${value.length} caracteres · ≈ ${ContextMemoryRepository.estimateTokens(value)} tokens")
        },
    )
}

@Composable
private fun ModelChoice(
    model: ProviderModelOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = model.name ?: model.id,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = model.id + (model.owner?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
    if (selected) {
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = onClick,
            content = { content() },
        )
    } else {
        TextButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = onClick,
            content = { content() },
        )
    }
}

private const val MAX_VISIBLE_MODELS = 100
