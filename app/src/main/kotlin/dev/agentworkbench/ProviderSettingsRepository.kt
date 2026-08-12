package dev.agentworkbench

import android.content.Context
import android.app.ActivityManager
import android.os.Build
import android.util.Base64
import dev.agentworkbench.core.ModelProvider
import dev.agentworkbench.core.ExecutionMode
import dev.agentworkbench.provider.http.DemoModelProvider
import dev.agentworkbench.provider.http.EndpointPolicy
import dev.agentworkbench.provider.http.EndpointValidation
import dev.agentworkbench.provider.http.HttpModelProvider
import dev.agentworkbench.provider.http.HttpModelCatalog
import dev.agentworkbench.provider.http.HttpProviderConfig
import dev.agentworkbench.provider.http.HttpProviderProtocol
import dev.agentworkbench.provider.http.ModelCatalogResult
import dev.agentworkbench.provider.http.ProviderModelOption
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import android.net.Uri

const val MANAGED_LITELLM_ENDPOINT = "managed://litellm"

enum class ProviderPreset(
    val displayName: String,
    val description: String,
    val defaultEndpoint: String,
    val defaultModel: String,
    val protocol: HttpProviderProtocol?,
    val requiresApiKey: Boolean,
) {
    DEMO(
        displayName = "Demo",
        description = "Streaming local de teste; não usa rede nem modelo.",
        defaultEndpoint = "",
        defaultModel = "demo-stream",
        protocol = null,
        requiresApiKey = false,
    ),
    LOCAL_GGUF(
        displayName = "Local GGUF",
        description = "Modelo offline carregado de um arquivo GGUF escolhido em Downloads.",
        defaultEndpoint = "local://gguf",
        defaultModel = "local-gguf",
        protocol = null,
        requiresApiKey = false,
    ),
    OPENAI(
        displayName = "OpenAI",
        description = "Responses API oficial com streaming SSE.",
        defaultEndpoint = "https://api.openai.com/v1/responses",
        defaultModel = "gpt-5.6-sol",
        protocol = HttpProviderProtocol.OPENAI_RESPONSES,
        requiresApiKey = true,
    ),
    OPENROUTER(
        displayName = "OpenRouter",
        description = "API compatível para múltiplas famílias de modelos.",
        defaultEndpoint = "https://openrouter.ai/api/v1/chat/completions",
        defaultModel = "~openai/gpt-latest",
        protocol = HttpProviderProtocol.OPENAI_COMPATIBLE_CHAT,
        requiresApiKey = true,
    ),
    GROQ(
        displayName = "Groq",
        description = "Chat Completions compatível com inferência de baixa latência.",
        defaultEndpoint = "https://api.groq.com/openai/v1/chat/completions",
        defaultModel = "openai/gpt-oss-20b",
        protocol = HttpProviderProtocol.OPENAI_COMPATIBLE_CHAT,
        requiresApiKey = true,
    ),
    NVIDIA(
        displayName = "NVIDIA",
        description = "NVIDIA NIM com Chat Completions compatível e catálogo de modelos.",
        defaultEndpoint = "https://integrate.api.nvidia.com/v1/chat/completions",
        defaultModel = "deepseek-ai/deepseek-v4-pro",
        protocol = HttpProviderProtocol.OPENAI_COMPATIBLE_CHAT,
        requiresApiKey = true,
    ),
    KENARI(
        displayName = "Kenari",
        description = "Gateway OpenAI-compatible com catalogo de modelos e streaming.",
        defaultEndpoint = "https://kenari.id/v1/chat/completions",
        defaultModel = "gpt-4o-mini:free",
        protocol = HttpProviderProtocol.OPENAI_COMPATIBLE_CHAT,
        requiresApiKey = true,
    ),
    OPENCODE_ZEN(
        displayName = "OpenCode Zen",
        description = "Modelos selecionados para agentes de codigo, com opções gratuitas e pagas.",
        defaultEndpoint = "https://opencode.ai/zen/v1/chat/completions",
        defaultModel = "deepseek-v4-flash-free",
        protocol = HttpProviderProtocol.OPENAI_COMPATIBLE_CHAT,
        requiresApiKey = true,
    ),
    CEREBRAS(
        displayName = "Cerebras",
        description = "Inferencia ultrarrapida OpenAI-compatible com streaming e ferramentas.",
        defaultEndpoint = "https://api.cerebras.ai/v1/chat/completions",
        defaultModel = "gpt-oss-120b",
        protocol = HttpProviderProtocol.OPENAI_COMPATIBLE_CHAT,
        requiresApiKey = true,
    ),
    MISTRAL(
        displayName = "Mistral",
        description = "API oficial Mistral para modelos gerais, raciocinio e codigo.",
        defaultEndpoint = "https://api.mistral.ai/v1/chat/completions",
        defaultModel = "mistral-large-latest",
        protocol = HttpProviderProtocol.OPENAI_COMPATIBLE_CHAT,
        requiresApiKey = true,
    ),
    DEEPSEEK(
        displayName = "DeepSeek",
        description = "API oficial DeepSeek com pensamento, streaming e tool calling.",
        defaultEndpoint = "https://api.deepseek.com/v1/chat/completions",
        defaultModel = "deepseek-v4-pro",
        protocol = HttpProviderProtocol.OPENAI_COMPATIBLE_CHAT,
        requiresApiKey = true,
    ),
    TOGETHER(
        displayName = "Together AI",
        description = "Catalogo amplo de modelos abertos com API OpenAI-compatible.",
        defaultEndpoint = "https://api.together.ai/v1/chat/completions",
        defaultModel = "openai/gpt-oss-20b",
        protocol = HttpProviderProtocol.OPENAI_COMPATIBLE_CHAT,
        requiresApiKey = true,
    ),
    FIREWORKS(
        displayName = "Fireworks AI",
        description = "Inferencia otimizada de modelos abertos com streaming e ferramentas.",
        defaultEndpoint = "https://api.fireworks.ai/inference/v1/chat/completions",
        defaultModel = "accounts/fireworks/models/gpt-oss-120b",
        protocol = HttpProviderProtocol.OPENAI_COMPATIBLE_CHAT,
        requiresApiKey = true,
    ),
    XAI(
        displayName = "xAI",
        description = "API Grok oficial compativel com Chat Completions.",
        defaultEndpoint = "https://api.x.ai/v1/chat/completions",
        defaultModel = "grok-4.5",
        protocol = HttpProviderProtocol.OPENAI_COMPATIBLE_CHAT,
        requiresApiKey = true,
    ),
    GEMINI(
        displayName = "Google Gemini",
        description = "Camada OpenAI-compatible oficial da Gemini API.",
        defaultEndpoint = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
        defaultModel = "gemini-2.5-flash",
        protocol = HttpProviderProtocol.OPENAI_COMPATIBLE_CHAT,
        requiresApiKey = true,
    ),
    CUSTOM_OPENAI(
        displayName = "Custom API",
        description = "Qualquer servidor OpenAI-compatible HTTPS com endpoint e Model ID editaveis.",
        defaultEndpoint = "https://example.com/v1/chat/completions",
        defaultModel = "model-id",
        protocol = HttpProviderProtocol.OPENAI_COMPATIBLE_CHAT,
        requiresApiKey = false,
    ),
    LITELLM(
        displayName = "LiteLLM Proxy",
        description = "LiteLLM integrado ao Refrator; também aceita uma REMOTE_URL externa.",
        defaultEndpoint = MANAGED_LITELLM_ENDPOINT,
        defaultModel = MANAGED_LITELLM_AUTO_MODEL,
        protocol = HttpProviderProtocol.OPENAI_COMPATIBLE_CHAT,
        requiresApiKey = false,
    ),
    OLLAMA(
        displayName = "Ollama LAN",
        description = "Servidor local compatível; informe o endereço LAN do servidor.",
        defaultEndpoint = "http://127.0.0.1:11434/v1/chat/completions",
        defaultModel = "gpt-oss:20b",
        protocol = HttpProviderProtocol.OPENAI_COMPATIBLE_CHAT,
        requiresApiKey = false,
    ),
}

data class ProviderSettings(
    val preset: ProviderPreset,
    val endpoint: String,
    val modelId: String,
    val executionMode: ExecutionMode,
    val continuousChat: ContinuousChatSettings = ContinuousChatSettings(),
)

data class ContinuousChatSettings(
    val enabled: Boolean = false,
    val automaticProviderSwitching: Boolean = true,
    val contextWindowTokens: Int = 32_768,
    val compactionThresholdPercent: Int = 75,
    val recentMessagesToKeep: Int = 14,
    val providerPool: Set<ProviderPreset> = defaultContinuousProviderPool(),
)

data class ProviderRoute(
    val settings: ProviderSettings,
    val provider: ModelProvider,
)

data class ManagedLiteLlmPoolEntry(
    val provider: ProviderPreset,
    val modelId: String,
    val endpointHost: String,
    val credentialFingerprints: List<String>,
    val deploymentCount: Int,
)

sealed interface ProviderBuildResult {
    data class Ready(val provider: ModelProvider) : ProviderBuildResult
    data class Rejected(val reason: String) : ProviderBuildResult
}

class ProviderSettingsRepository(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val secrets = AndroidSecretStore(appContext)
    private val localGguf = LocalGgufRepository(appContext)

    fun load(): ProviderSettings {
        val preset = preferences.getString(KEY_PRESET, null)
            ?.let { runCatching { ProviderPreset.valueOf(it) }.getOrNull() }
            ?: ProviderPreset.DEMO
        val endpoint = preferences.getString(KEY_ENDPOINT, preset.defaultEndpoint)
            ?: preset.defaultEndpoint
        var modelId = preferences.getString(KEY_MODEL, preset.defaultModel)
            ?: preset.defaultModel
        if (
            preset == ProviderPreset.LITELLM &&
            endpoint == MANAGED_LITELLM_ENDPOINT
        ) {
            modelId = MANAGED_LITELLM_AUTO_MODEL
            preferences.edit()
                .putString(KEY_MODEL, modelId)
                .apply()
        }
        return ProviderSettings(
            preset = preset,
            endpoint = endpoint,
            modelId = modelId,
            executionMode = preferences.getString(KEY_EXECUTION_MODE, null)
                ?.let { runCatching { ExecutionMode.valueOf(it) }.getOrNull() }
                ?: if (BuildConfig.DEVELOPER_BUILD) {
                    ExecutionMode.FULL
                } else {
                    ExecutionMode.BUILD
                },
            continuousChat = loadContinuousChatSettings(),
        )
    }

    fun defaults(preset: ProviderPreset): ProviderSettings =
        ProviderSettings(
            preset = preset,
            endpoint = preferences.getString(profileEndpointKey(preset), preset.defaultEndpoint)
                ?: preset.defaultEndpoint,
            modelId = if (preset == ProviderPreset.LITELLM) {
                MANAGED_LITELLM_AUTO_MODEL
            } else {
                normalizedProviderModel(
                    preset,
                    preferences.getString(profileModelKey(preset), preset.defaultModel)
                        ?: preset.defaultModel,
                )
            },
            executionMode = if (BuildConfig.DEVELOPER_BUILD) {
                ExecutionMode.FULL
            } else {
                ExecutionMode.BUILD
            },
            continuousChat = loadContinuousChatSettings(),
        )

    fun save(
        settings: ProviderSettings,
        apiKey: String,
        allowLocalCleartext: Boolean,
        managedPoolPreset: ProviderPreset? = null,
    ): String? {
        val modelId = if (
            settings.preset == ProviderPreset.LITELLM &&
            settings.endpoint.trim() == MANAGED_LITELLM_ENDPOINT
        ) {
            MANAGED_LITELLM_AUTO_MODEL
        } else {
            settings.modelId.trim()
        }
        val validation = validate(
            settings = settings,
            candidateApiKey = apiKey.takeIf { it.isNotBlank() } ?: secret(settings),
            allowLocalCleartext = allowLocalCleartext,
        )
        if (validation != null) return validation
        if (
            apiKey.isNotBlank() &&
            settings.preset == ProviderPreset.LITELLM &&
            settings.endpoint.trim() == MANAGED_LITELLM_ENDPOINT
        ) {
            val poolPreset = managedPoolPreset ?: ProviderPreset.NVIDIA
            validateManagedPoolCredential(poolPreset, apiKey, settings, allowLocalCleartext)
                ?.let { return it }
        }

        preferences.edit()
            .putString(KEY_PRESET, settings.preset.name)
            .putString(KEY_ENDPOINT, settings.endpoint.trim())
            .putString(KEY_MODEL, modelId)
            .putString(KEY_EXECUTION_MODE, settings.executionMode.name)
            .putString(profileEndpointKey(settings.preset), settings.endpoint.trim())
            .putString(profileModelKey(settings.preset), modelId)
            .putBoolean(profileSavedKey(settings.preset), true)
            .putBoolean(KEY_CONTINUOUS_ENABLED, settings.continuousChat.enabled)
            .putBoolean(
                KEY_CONTINUOUS_AUTO_SWITCH,
                settings.continuousChat.automaticProviderSwitching,
            )
            .putInt(
                KEY_CONTINUOUS_CONTEXT_TOKENS,
                settings.continuousChat.contextWindowTokens,
            )
            .putInt(
                KEY_CONTINUOUS_THRESHOLD_PERCENT,
                settings.continuousChat.compactionThresholdPercent,
            )
            .putInt(
                KEY_CONTINUOUS_RECENT_MESSAGES,
                settings.continuousChat.recentMessagesToKeep,
            )
            .putStringSet(
                KEY_CONTINUOUS_PROVIDER_POOL,
                settings.continuousChat.providerPool.mapTo(mutableSetOf()) { it.name },
            )
            .apply()
        if (apiKey.isNotBlank()) {
            if (
                settings.preset == ProviderPreset.LITELLM &&
                settings.endpoint.trim() == MANAGED_LITELLM_ENDPOINT
            ) {
                addManagedPoolCredential(
                    preset = managedPoolPreset ?: ProviderPreset.NVIDIA,
                    apiKey = apiKey,
                    active = settings,
                )
            } else {
                secrets.put(secretSlot(settings), apiKey)
            }
        }
        return null
    }

    fun hasApiKey(settings: ProviderSettings): Boolean = if (
        settings.preset == ProviderPreset.LITELLM &&
        settings.endpoint.trim() == MANAGED_LITELLM_ENDPOINT
    ) {
        managedLiteLlmCredentialCount() > 0
    } else {
        secrets.contains(secretSlot(settings))
    }

    fun clearApiKey(settings: ProviderSettings) {
        if (
            settings.preset == ProviderPreset.LITELLM &&
            settings.endpoint.trim() == MANAGED_LITELLM_ENDPOINT
        ) {
            migrateManagedPoolCredentials(settings)
            managedLiteLlmSupportedPresets().forEach(::clearManagedProviderApiKeys)
            preferences.edit().putStringSet(KEY_MANAGED_LITELLM_PROVIDER_POOL, emptySet()).apply()
        } else {
            secrets.delete(secretSlot(settings))
        }
    }

    fun managedLiteLlmCredentialCount(): Int {
        val active = load()
        migrateManagedPoolCredentials(active)
        return managedPoolPresets().sumOf { managedProviderApiKeys(it).size }
    }

    fun managedLiteLlmPoolSummary(): String {
        migrateManagedPoolCredentials(load())
        val names = buildList {
            managedPoolPresets()
                .filter { managedProviderApiKeys(it).isNotEmpty() }
                .mapTo(this) { it.displayName }
        }.distinct()
        return if (names.isEmpty()) "pool vazio" else names.joinToString()
    }

    /** Visão pública e segura do pool: identifica chaves por hash, nunca por conteúdo. */
    fun managedLiteLlmPoolEntries(active: ProviderSettings): List<ManagedLiteLlmPoolEntry> {
        migrateManagedPoolCredentials(active)
        return buildList {
            managedPoolPresets().forEach { preset ->
                val profile = settingsForPreset(preset, active)
                val keys = managedProviderApiKeys(preset)
                if (keys.isEmpty()) return@forEach
                add(
                    ManagedLiteLlmPoolEntry(
                        provider = preset,
                        modelId = if (preset == ProviderPreset.NVIDIA) {
                            "deepseek-ai/deepseek-v4-pro"
                        } else {
                            profile.modelId
                        },
                        endpointHost = safeEndpointHost(profile.endpoint),
                        credentialFingerprints = keys.map(::credentialFingerprint),
                        deploymentCount = keys.size,
                    ),
                )
            }
        }
    }

    fun removeManagedLiteLlmCredential(
        active: ProviderSettings,
        preset: ProviderPreset,
        fingerprint: String,
    ): Boolean {
        migrateManagedPoolCredentials(active)
        val current = managedProviderApiKeys(preset)
        val index = current.indexOfFirst { credentialFingerprint(it) == fingerprint }
        if (index < 0) return false
        writeManagedProviderApiKeys(
            preset,
            current.filterIndexed { itemIndex, _ -> itemIndex != index },
        )
        if (managedProviderApiKeys(preset).isEmpty()) {
            val pool = managedPoolPresets().toMutableSet().apply { remove(preset) }
            preferences.edit().putStringSet(
                KEY_MANAGED_LITELLM_PROVIDER_POOL,
                pool.mapTo(mutableSetOf()) { it.name },
            ).apply()
        }
        return true
    }

    fun localGgufInfo(): LocalGgufInfo? = localGguf.current()

    fun localGgufRuntimeProfile(model: LocalGgufInfo): LocalGgufRuntimeProfile =
        LocalGgufRuntimePlanner.forDevice(appContext, model)

    fun effectiveContextWindowTokens(settings: ProviderSettings): Int =
        if (settings.preset == ProviderPreset.LOCAL_GGUF) {
            localGguf.current()?.let { LocalGgufRuntimePlanner.forDevice(appContext, it).contextTokens }
                ?: 2_048
        } else {
            settings.continuousChat.contextWindowTokens
        }

    suspend fun importLocalGguf(uri: Uri): LocalGgufInfo = localGguf.import(uri)

    fun saveExecutionMode(mode: ExecutionMode) {
        preferences.edit()
            .putString(KEY_EXECUTION_MODE, mode.name)
            .apply()
    }

    fun buildProviderRoutes(
        active: ProviderSettings,
        allowLocalCleartext: Boolean,
    ): List<ProviderRoute> {
        val presets = buildList {
            add(active.preset)
            if (
                active.continuousChat.enabled &&
                active.continuousChat.automaticProviderSwitching
            ) {
                ProviderPreset.entries
                    .filter { it in active.continuousChat.providerPool }
                    .forEach(::add)
            }
        }.distinct()

        return presets.mapNotNull { preset ->
            val candidate = if (preset == active.preset) {
                active
            } else {
                settingsForPreset(preset, active)
            }
            if (preset != active.preset && !isConfiguredForContinuity(candidate)) {
                return@mapNotNull null
            }
            when (val result = buildProvider(candidate, allowLocalCleartext)) {
                is ProviderBuildResult.Ready -> ProviderRoute(candidate, result.provider)
                is ProviderBuildResult.Rejected -> null
            }
        }
    }

    fun isContinuityProviderReady(
        preset: ProviderPreset,
        active: ProviderSettings,
        allowLocalCleartext: Boolean,
    ): Boolean {
        val candidate = if (preset == active.preset) active else settingsForPreset(preset, active)
        if (preset != active.preset && !isConfiguredForContinuity(candidate)) return false
        return buildProvider(candidate, allowLocalCleartext) is ProviderBuildResult.Ready
    }

    fun buildProvider(
        settings: ProviderSettings,
        allowLocalCleartext: Boolean,
    ): ProviderBuildResult {
        if (settings.preset == ProviderPreset.DEMO) {
            return ProviderBuildResult.Ready(DemoModelProvider())
        }
        if (settings.preset == ProviderPreset.LOCAL_GGUF) {
            if (!localGgufRuntimeSupported()) {
                return ProviderBuildResult.Rejected(
                    "Local GGUF exige um celular arm64-v8a nesta versão; os providers remotos e o LiteLLM continuam disponíveis.",
                )
            }
            val model = localGguf.current()
                ?: return ProviderBuildResult.Rejected("Selecione e importe um arquivo GGUF nas Configuracoes.")
            localModelMemoryWarning(model)?.let { warning ->
                return ProviderBuildResult.Rejected(warning)
            }
            return ProviderBuildResult.Ready(LocalGgufModelProvider(appContext, model))
        }
        val token = secret(settings)
        if (
            settings.preset == ProviderPreset.LITELLM &&
            settings.endpoint.trim() == MANAGED_LITELLM_ENDPOINT
        ) {
            val deployments = managedLiteLlmDeployments(settings, allowLocalCleartext)
            if (deployments.isEmpty()) {
                return ProviderBuildResult.Rejected(
                    "Adicione ao menos uma credencial de provider ao pool LiteLLM.",
                )
            }
            val provider = DistributionBindings.managedLiteLlmProvider(
                appContext,
                settings,
                token,
                deployments,
            ) ?: return ProviderBuildResult.Rejected(
                "LiteLLM integrado não está disponível neste build.",
            )
            return ProviderBuildResult.Ready(remoteSafe(provider))
        }
        validate(settings, token, allowLocalCleartext)?.let {
            return ProviderBuildResult.Rejected(it)
        }
        val protocol = settings.preset.protocol
            ?: return ProviderBuildResult.Rejected("Provider protocol is unavailable")
        val locality = EndpointPolicy.inferLocality(settings.endpoint)
            ?: return ProviderBuildResult.Rejected("Endpoint locality could not be determined")
        val providerId = buildString {
            append(settings.preset.name.lowercase())
            append('-')
            append(digest(settings.endpoint.trim()).take(12))
        }
        return ProviderBuildResult.Ready(
            remoteSafe(
                HttpModelProvider(
                HttpProviderConfig(
                    providerId = providerId,
                    displayName = settings.preset.displayName,
                    protocol = protocol,
                    endpoint = settings.endpoint.trim(),
                    modelId = settings.modelId.trim(),
                    locality = locality,
                    authorizationToken = token,
                    safetyIdentifier = installSafetyIdentifier(),
                    allowLocalCleartext = allowLocalCleartext,
                ),
                ),
            ),
        )
    }

    suspend fun loadModelCatalog(
        settings: ProviderSettings,
        allowLocalCleartext: Boolean,
    ): ModelCatalogResult {
        if (settings.preset == ProviderPreset.DEMO) {
            return ModelCatalogResult.Success(
                listOf(
                    ProviderModelOption(
                        id = settings.preset.defaultModel,
                        name = "Demo stream",
                        owner = "on-device",
                    ),
                ),
            )
        }
        if (settings.preset == ProviderPreset.LOCAL_GGUF) {
            if (!localGgufRuntimeSupported()) {
                return ModelCatalogResult.Failure("Local GGUF exige CPU arm64-v8a nesta versão.")
            }
            val model = localGguf.current()
                ?: return ModelCatalogResult.Failure("Nenhum GGUF foi importado.")
            return ModelCatalogResult.Success(
                listOf(ProviderModelOption(id = model.displayName, name = model.displayName, owner = "on-device")),
            )
        }
        if (
            settings.preset == ProviderPreset.LITELLM &&
            settings.endpoint.trim() == MANAGED_LITELLM_ENDPOINT
        ) {
            val deployments = managedLiteLlmDeployments(settings, allowLocalCleartext)
            if (deployments.isEmpty()) {
                return ModelCatalogResult.Failure(
                    "O pool está vazio. Adicione uma credencial de provider primeiro.",
                )
            }
            return ModelCatalogResult.Success(
                listOf(
                    ProviderModelOption(
                        id = MANAGED_LITELLM_AUTO_MODEL,
                        name = "Automático · rotacionar ${deployments.size} rotas",
                        owner = deployments.joinToString(" · ") { it.displayName },
                    ),
                ),
            )
        }
        val token = secret(settings)
        validate(settings, token, allowLocalCleartext)?.let {
            return ModelCatalogResult.Failure(it)
        }
        val protocol = settings.preset.protocol
            ?: return ModelCatalogResult.Failure("Provider protocol is unavailable")
        val locality = EndpointPolicy.inferLocality(settings.endpoint)
            ?: return ModelCatalogResult.Failure(
                "Endpoint locality could not be determined",
            )
        return HttpModelCatalog.fetch(
            HttpProviderConfig(
                providerId = "catalog-${settings.preset.name.lowercase()}",
                displayName = settings.preset.displayName,
                protocol = protocol,
                endpoint = settings.endpoint.trim(),
                modelId = settings.modelId.trim(),
                locality = locality,
                authorizationToken = token,
                safetyIdentifier = null,
                allowLocalCleartext = allowLocalCleartext,
            ),
        )
    }

    fun validate(
        settings: ProviderSettings,
        candidateApiKey: String?,
        allowLocalCleartext: Boolean,
    ): String? {
        if (settings.preset == ProviderPreset.DEMO) return null
        if (settings.preset == ProviderPreset.LOCAL_GGUF) {
            val model = localGguf.current()
            return when {
                !localGgufRuntimeSupported() ->
                    "Local GGUF exige CPU arm64-v8a nesta versão."
                model == null -> "Selecione um arquivo GGUF em Downloads."
                else -> localModelMemoryWarning(model)
            }
        }
        if (
            settings.preset == ProviderPreset.LITELLM &&
            settings.endpoint.trim() == MANAGED_LITELLM_ENDPOINT
        ) {
            return when {
                !BuildConfig.FULL_CAPABILITIES ->
                    "LiteLLM integrado não está compilado neste build."
                candidateApiKey.isNullOrBlank() && managedLiteLlmCredentialCount() == 0 ->
                    "Adicione ao menos uma credencial de provider ao pool LiteLLM."
                else -> null
            }
        }
        if (settings.preset.requiresApiKey && candidateApiKey.isNullOrBlank()) {
            return "Este provider exige uma API key."
        }
        val protocol = settings.preset.protocol
            ?: return "Provider protocol is unavailable"
        val locality = EndpointPolicy.inferLocality(settings.endpoint)
            ?: dev.agentworkbench.core.ProviderLocality.REMOTE
        val result = EndpointPolicy.validate(
            HttpProviderConfig(
                providerId = "validation",
                displayName = settings.preset.displayName,
                protocol = protocol,
                endpoint = settings.endpoint,
                modelId = settings.modelId,
                locality = locality,
                authorizationToken = candidateApiKey,
                safetyIdentifier = null,
                allowLocalCleartext = allowLocalCleartext,
            ),
        )
        return (result as? EndpointValidation.Rejected)?.reason
    }

    private fun localModelMemoryWarning(model: LocalGgufInfo): String? {
        val memory = ActivityManager.MemoryInfo()
        appContext.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        val conservativeLimit = (memory.totalMem * 40L) / 100L
        return if (model.bytes > conservativeLimit) {
            "Este GGUF ocupa ${model.bytes / 1_048_576} MiB e excede o limite seguro " +
                "para ${memory.totalMem / 1_048_576} MiB de RAM. Escolha uma quantização menor."
        } else {
            null
        }
    }

    private fun secret(settings: ProviderSettings): String? =
        secrets.get(secretSlot(settings))

    fun managedLiteLlmDeployments(
        active: ProviderSettings,
        allowLocalCleartext: Boolean,
    ): List<ManagedLiteLlmDeployment> = buildList {
        migrateManagedPoolCredentials(active)
        val nvidiaKeys = managedNvidiaApiKeys()
        if (nvidiaKeys.isNotEmpty()) {
            val nvidia = settingsForPreset(ProviderPreset.NVIDIA, active)
            // NVIDIA encerrou o v4-flash em 2026-08-07 e agora responde HTTP 410.
            // Manter um deployment morto no pool impede o Router de concluir algumas
            // requisições antes mesmo de alcançar os fallbacks. O pool gerenciado contém
            // somente modelos ativos; novas variantes entram após health check explícito.
            val models = listOf("deepseek-ai/deepseek-v4-pro")
            addAll(nvidiaKeys.flatMapIndexed { keyIndex, apiKey ->
                models.map { model ->
                    val fallback = remoteSafe(
                        HttpModelProvider(
                            HttpProviderConfig(
                                providerId = "managed-nvidia-deepseek-${keyIndex + 1}-${digest(model).take(8)}",
                                displayName = "NVIDIA DeepSeek",
                                protocol = HttpProviderProtocol.OPENAI_COMPATIBLE_CHAT,
                                endpoint = nvidia.endpoint.trim(),
                                modelId = model,
                                locality = dev.agentworkbench.core.ProviderLocality.REMOTE,
                                authorizationToken = apiKey,
                                safetyIdentifier = installSafetyIdentifier(),
                                allowLocalCleartext = allowLocalCleartext,
                            ),
                        ),
                    )
                    ManagedLiteLlmDeployment(
                        alias = "nvidia-deepseek-${keyIndex + 1}-${model.substringAfterLast('/')}",
                        displayName = "NVIDIA #${keyIndex + 1} · ${model.substringAfterLast('/')}",
                        providerModel = model,
                        litellmModel = "nvidia_nim/$model",
                        apiBase = liteLlmApiBase(nvidia.endpoint),
                        apiKey = apiKey,
                        fallbackProvider = fallback,
                    )
                }
            })
        }
        managedPoolPresets().forEach { preset ->
            // Chaves NVIDIA do pool legado/múltiplo já foram adicionadas acima.
            if (preset == ProviderPreset.NVIDIA && nvidiaKeys.isNotEmpty()) return@forEach
            val candidate = settingsForPreset(preset, active)
            val model = candidate.modelId.trim()
            managedProviderApiKeys(preset).forEachIndexed { keyIndex, apiKey ->
                val fallback = managedFallbackProvider(
                    candidate,
                    apiKey,
                    allowLocalCleartext,
                    keyIndex,
                ) ?: return@forEachIndexed
            add(
                ManagedLiteLlmDeployment(
                    alias = "${preset.name.lowercase()}-${keyIndex + 1}-${digest(model).take(8)}",
                    displayName = "${preset.displayName} · $model",
                    providerModel = model,
                    litellmModel = liteLlmModelName(preset, model),
                    apiBase = liteLlmApiBase(candidate.endpoint),
                    apiKey = apiKey,
                    fallbackProvider = fallback,
                ),
            )
            }
        }
    }

    fun managedLiteLlmSupportedPresets(): List<ProviderPreset> = ProviderPreset.entries.filter {
        it.protocol != null && it !in setOf(ProviderPreset.LITELLM, ProviderPreset.OLLAMA)
    }

    private fun validateManagedPoolCredential(
        preset: ProviderPreset,
        apiKey: String,
        active: ProviderSettings,
        allowLocalCleartext: Boolean,
    ): String? {
        if (preset !in managedLiteLlmSupportedPresets()) {
            return "Esse tipo de provider não aceita credencial no pool LiteLLM integrado."
        }
        if (
            preset == ProviderPreset.CUSTOM_OPENAI &&
            !preferences.getBoolean(profileSavedKey(preset), false)
        ) {
            return "Configure e salve primeiro o endpoint e o modelo do provider Custom API."
        }
        if (preset == ProviderPreset.NVIDIA && !apiKey.trim().startsWith("nvapi-")) {
            return "A chave NVIDIA deve começar com nvapi-."
        }
        if (
            apiKey.trim() !in managedProviderApiKeys(preset) &&
            managedProviderApiKeys(preset).size >= MAX_MANAGED_PROVIDER_KEYS
        ) {
            return "O pool ${preset.displayName} atingiu o limite de $MAX_MANAGED_PROVIDER_KEYS credenciais."
        }
        return validate(
            settings = settingsForPreset(preset, active),
            candidateApiKey = apiKey.trim(),
            allowLocalCleartext = allowLocalCleartext,
        )
    }

    private fun addManagedPoolCredential(
        preset: ProviderPreset,
        apiKey: String,
        active: ProviderSettings,
    ) {
        val normalized = apiKey.trim()
        val candidate = settingsForPreset(preset, active)
        migrateManagedPoolCredentials(active)
        val current = managedProviderApiKeys(preset)
        if (normalized !in current) {
            writeManagedProviderApiKeys(preset, current + normalized)
        }
        val pool = managedPoolPresets().toMutableSet().apply { add(preset) }
        preferences.edit()
            .putBoolean(profileSavedKey(preset), true)
            .putString(profileEndpointKey(preset), candidate.endpoint)
            .putString(profileModelKey(preset), candidate.modelId)
            .putStringSet(KEY_MANAGED_LITELLM_PROVIDER_POOL, pool.mapTo(mutableSetOf()) { it.name })
            .apply()
    }

    private fun managedPoolPresets(): Set<ProviderPreset> {
        if (preferences.contains(KEY_MANAGED_LITELLM_PROVIDER_POOL)) {
            return preferences.getStringSet(KEY_MANAGED_LITELLM_PROVIDER_POOL, emptySet())
                .orEmpty()
                .mapNotNullTo(linkedSetOf()) { name ->
                    runCatching { ProviderPreset.valueOf(name) }.getOrNull()
                }
                .filterTo(linkedSetOf()) { it in managedLiteLlmSupportedPresets() }
        }
        // Migração: versões anteriores usavam silenciosamente todos os perfis configurados.
        return managedLiteLlmSupportedPresets().filterTo(linkedSetOf()) { preset ->
            val candidate = settingsForPreset(preset, load())
            isConfiguredForContinuity(candidate)
        }
    }

    @Synchronized
    private fun migrateManagedPoolCredentials(active: ProviderSettings) {
        if (preferences.getBoolean(KEY_MANAGED_PROVIDER_KEYS_MIGRATED, false)) return

        val pool = managedPoolPresets().toMutableSet()
        managedLiteLlmSupportedPresets().forEach { preset ->
            val profile = settingsForPreset(preset, active)
            val legacy = secret(profile)?.takeIf(String::isNotBlank)
            val existing = managedProviderApiKeys(preset)
            val shouldImport = preset in pool || preset == ProviderPreset.NVIDIA
            if (shouldImport && legacy != null && legacy !in existing) {
                writeManagedProviderApiKeys(preset, existing + legacy)
            }
            if (managedProviderApiKeys(preset).isNotEmpty()) pool.add(preset)
        }
        preferences.edit()
            .putStringSet(
                KEY_MANAGED_LITELLM_PROVIDER_POOL,
                pool.mapTo(mutableSetOf()) { it.name },
            )
            .putBoolean(KEY_MANAGED_PROVIDER_KEYS_MIGRATED, true)
            .commit()
    }

    private fun managedProviderApiKeys(preset: ProviderPreset): List<String> {
        if (preset == ProviderPreset.NVIDIA) return managedNvidiaApiKeys()
        val count = preferences.getInt(managedProviderCountKey(preset), 0)
            .coerceIn(0, MAX_MANAGED_PROVIDER_KEYS)
        return (0 until count)
            .mapNotNull { index -> secrets.get(managedProviderSecretId(preset, index)) }
            .distinct()
    }

    private fun writeManagedProviderApiKeys(preset: ProviderPreset, values: List<String>) {
        val normalized = values.map(String::trim).filter(String::isNotBlank).distinct()
        require(normalized.size <= MAX_MANAGED_PROVIDER_KEYS) { "Muitas credenciais no provider." }
        if (preset == ProviderPreset.NVIDIA) {
            val previousCount = preferences.getInt(KEY_MANAGED_NVIDIA_KEY_COUNT, 0)
                .coerceIn(0, MAX_MANAGED_NVIDIA_KEYS)
            normalized.forEachIndexed { index, value ->
                require(value.startsWith("nvapi-")) { "Invalid NVIDIA credential" }
                secrets.put(managedNvidiaSecretId(index), value)
            }
            check(preferences.edit().putInt(KEY_MANAGED_NVIDIA_KEY_COUNT, normalized.size).commit())
            (normalized.size until previousCount).forEach { index ->
                secrets.delete(managedNvidiaSecretId(index))
            }
            return
        }
        val previousCount = preferences.getInt(managedProviderCountKey(preset), 0)
            .coerceIn(0, MAX_MANAGED_PROVIDER_KEYS)
        // Store the complete replacement first, publish its count second, and only then remove
        // obsolete slots. A process death can leave an extra encrypted slot, but never an empty
        // pool or a count which points at credentials that were deliberately deleted first.
        normalized.forEachIndexed { index, value ->
            secrets.put(managedProviderSecretId(preset, index), value)
        }
        preferences.edit().putInt(managedProviderCountKey(preset), normalized.size).commit()
        (normalized.size until previousCount).forEach { index ->
            secrets.delete(managedProviderSecretId(preset, index))
        }
    }

    private fun clearManagedProviderApiKeys(preset: ProviderPreset) {
        if (preset == ProviderPreset.NVIDIA) {
            clearManagedNvidiaApiKeys()
            return
        }
        val count = preferences.getInt(managedProviderCountKey(preset), 0)
            .coerceIn(0, MAX_MANAGED_PROVIDER_KEYS)
        repeat(count) { index -> secrets.delete(managedProviderSecretId(preset, index)) }
        preferences.edit().remove(managedProviderCountKey(preset)).commit()
    }

    private fun managedProviderCountKey(preset: ProviderPreset): String =
        "managed_provider_${preset.name.lowercase()}_key_count_v3"

    private fun managedProviderSecretId(preset: ProviderPreset, index: Int): String =
        "managed-litellm-${preset.name.lowercase()}-v3-$index"

    private fun managedFallbackProvider(
        settings: ProviderSettings,
        apiKey: String,
        allowLocalCleartext: Boolean,
        keyIndex: Int,
    ): ModelProvider? {
        if (validate(settings, apiKey, allowLocalCleartext) != null) return null
        val protocol = settings.preset.protocol ?: return null
        val locality = EndpointPolicy.inferLocality(settings.endpoint)
            ?: dev.agentworkbench.core.ProviderLocality.REMOTE
        return remoteSafe(
            HttpModelProvider(
                HttpProviderConfig(
                    providerId = "managed-${settings.preset.name.lowercase()}-${keyIndex + 1}",
                    displayName = "${settings.preset.displayName} #${keyIndex + 1}",
                    protocol = protocol,
                    endpoint = settings.endpoint.trim(),
                    modelId = settings.modelId.trim(),
                    locality = locality,
                    authorizationToken = apiKey,
                    safetyIdentifier = installSafetyIdentifier(),
                    allowLocalCleartext = allowLocalCleartext,
                ),
            ),
        )
    }

    private fun addManagedNvidiaApiKey(apiKey: String) {
        val normalized = apiKey.trim()
        require(normalized.startsWith("nvapi-")) { "A chave NVIDIA deve começar com nvapi-." }
        val current = managedNvidiaApiKeys()
        if (current.any { it == normalized }) return
        val index = current.size
        secrets.put(managedNvidiaSecretId(index), normalized)
        check(preferences.edit().putInt(KEY_MANAGED_NVIDIA_KEY_COUNT, index + 1).commit())
    }

    private fun managedNvidiaApiKeys(): List<String> {
        val count = preferences.getInt(KEY_MANAGED_NVIDIA_KEY_COUNT, 0)
            .coerceIn(0, MAX_MANAGED_NVIDIA_KEYS)
        return (0 until count).mapNotNull { index ->
            secrets.get(managedNvidiaSecretId(index))
        }
    }

    private fun clearManagedNvidiaApiKeys() {
        val count = preferences.getInt(KEY_MANAGED_NVIDIA_KEY_COUNT, 0)
            .coerceIn(0, MAX_MANAGED_NVIDIA_KEYS)
        repeat(count) { index -> secrets.delete(managedNvidiaSecretId(index)) }
        check(preferences.edit().remove(KEY_MANAGED_NVIDIA_KEY_COUNT).commit())
    }

    private fun managedNvidiaSecretId(index: Int): String =
        "managed-litellm-nvidia-v1-$index"

    private fun liteLlmModelName(preset: ProviderPreset, model: String): String = when (preset) {
        ProviderPreset.OPENAI -> "openai/$model"
        ProviderPreset.OPENROUTER -> "openrouter/$model"
        ProviderPreset.GROQ -> "groq/$model"
        ProviderPreset.NVIDIA -> "nvidia_nim/$model"
        ProviderPreset.CEREBRAS -> "cerebras/$model"
        ProviderPreset.MISTRAL -> "mistral/$model"
        ProviderPreset.DEEPSEEK -> "deepseek/$model"
        ProviderPreset.TOGETHER -> "together_ai/$model"
        ProviderPreset.FIREWORKS -> "fireworks_ai/$model"
        ProviderPreset.XAI -> "xai/$model"
        ProviderPreset.GEMINI -> "gemini/$model"
        ProviderPreset.OLLAMA -> "openai/$model"
        ProviderPreset.KENARI,
        ProviderPreset.OPENCODE_ZEN,
        ProviderPreset.CUSTOM_OPENAI,
        -> "openai/$model"
        ProviderPreset.DEMO,
        ProviderPreset.LOCAL_GGUF,
        ProviderPreset.LITELLM,
        -> model
    }

    private fun liteLlmApiBase(endpoint: String): String = endpoint.trim().trimEnd('/')
        .removeSuffix("/chat/completions")
        .removeSuffix("/responses")

    private fun settingsForPreset(
        preset: ProviderPreset,
        active: ProviderSettings,
    ): ProviderSettings = ProviderSettings(
        preset = preset,
        endpoint = preferences.getString(profileEndpointKey(preset), preset.defaultEndpoint)
            ?: preset.defaultEndpoint,
        modelId = normalizedProviderModel(
            preset,
            preferences.getString(profileModelKey(preset), preset.defaultModel)
                ?: preset.defaultModel,
        ),
        executionMode = active.executionMode,
        continuousChat = active.continuousChat,
    )

    private fun normalizedProviderModel(preset: ProviderPreset, model: String): String = when {
        preset == ProviderPreset.OPENROUTER &&
            model.trim() == "deepseek/deepseek-v4-flash-0731" ->
            "deepseek/deepseek-v4-pro:exacto"
        preset == ProviderPreset.OPENROUTER &&
            model.trim() == "deepseek/deepseek-v4-pro" ->
            "deepseek/deepseek-v4-pro:exacto"
        else -> model.trim()
    }

    private fun isConfiguredForContinuity(settings: ProviderSettings): Boolean = when {
        settings.preset == ProviderPreset.DEMO -> false
        settings.preset == ProviderPreset.LOCAL_GGUF -> localGguf.current() != null
        settings.preset.requiresApiKey -> hasApiKey(settings)
        else -> preferences.getBoolean(profileSavedKey(settings.preset), false)
    }

    private fun loadContinuousChatSettings(): ContinuousChatSettings {
        val pool = preferences.getStringSet(KEY_CONTINUOUS_PROVIDER_POOL, null)
            ?.mapNotNullTo(mutableSetOf()) { name ->
                runCatching { ProviderPreset.valueOf(name) }.getOrNull()
            }
            ?: defaultContinuousProviderPool()
        return ContinuousChatSettings(
            enabled = preferences.getBoolean(KEY_CONTINUOUS_ENABLED, false),
            automaticProviderSwitching = preferences.getBoolean(
                KEY_CONTINUOUS_AUTO_SWITCH,
                true,
            ),
            contextWindowTokens = preferences.getInt(
                KEY_CONTINUOUS_CONTEXT_TOKENS,
                32_768,
            ).coerceIn(MIN_CONTEXT_WINDOW_TOKENS, MAX_CONTEXT_WINDOW_TOKENS),
            compactionThresholdPercent = preferences.getInt(
                KEY_CONTINUOUS_THRESHOLD_PERCENT,
                75,
            ).coerceIn(MIN_COMPACTION_PERCENT, MAX_COMPACTION_PERCENT),
            recentMessagesToKeep = preferences.getInt(
                KEY_CONTINUOUS_RECENT_MESSAGES,
                14,
            ).coerceIn(MIN_RECENT_MESSAGES, MAX_RECENT_MESSAGES),
            providerPool = pool,
        )
    }

    private fun profileEndpointKey(preset: ProviderPreset): String =
        "profile_${preset.name.lowercase()}_endpoint"

    private fun profileModelKey(preset: ProviderPreset): String =
        "profile_${preset.name.lowercase()}_model"

    private fun profileSavedKey(preset: ProviderPreset): String =
        "profile_${preset.name.lowercase()}_saved"

    private fun secretSlot(settings: ProviderSettings): String {
        val origin = runCatching {
            val uri = URI(settings.endpoint.trim())
            "${uri.scheme?.lowercase()}://${uri.host?.lowercase()}:${effectivePort(uri)}"
        }.getOrDefault("invalid")
        return digest("${settings.preset.protocol}|$origin")
    }

    private fun effectivePort(uri: URI): Int =
        when {
            uri.port >= 0 -> uri.port
            uri.scheme.equals("https", ignoreCase = true) -> 443
            else -> 80
        }

    private fun installSafetyIdentifier(): String {
        val installId = preferences.getString(KEY_INSTALL_ID, null)
            ?: UUID.randomUUID().toString().also {
                preferences.edit().putString(KEY_INSTALL_ID, it).apply()
            }
        return digest("${appContext.packageName}|$installId").take(32)
    }

    private fun localGgufRuntimeSupported(): Boolean =
        Build.SUPPORTED_ABIS.any { it.equals("arm64-v8a", ignoreCase = true) }

    private fun remoteSafe(provider: ModelProvider): ModelProvider =
        if (provider.descriptor.locality == dev.agentworkbench.core.ProviderLocality.REMOTE) {
            RedactingModelProvider(provider)
        } else {
            provider
        }

    private fun credentialFingerprint(value: String): String =
        "sha256:${digest(value).take(10)}"

    private fun safeEndpointHost(endpoint: String): String = runCatching {
        val uri = URI(endpoint.trim())
        buildString {
            append(uri.host ?: uri.scheme ?: "endpoint")
            if (uri.port >= 0) append(":${uri.port}")
        }
    }.getOrDefault("endpoint configurado")

    private fun digest(value: String): String =
        Base64.encodeToString(
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING,
        )

    private companion object {
        const val PREFERENCES_NAME = "agent_workbench_provider_settings"
        const val KEY_PRESET = "preset"
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_MODEL = "model"
        const val KEY_EXECUTION_MODE = "execution_mode"
        const val KEY_INSTALL_ID = "install_id"
        const val KEY_CONTINUOUS_ENABLED = "continuous_enabled"
        const val KEY_CONTINUOUS_AUTO_SWITCH = "continuous_auto_switch"
        const val KEY_CONTINUOUS_CONTEXT_TOKENS = "continuous_context_tokens"
        const val KEY_CONTINUOUS_THRESHOLD_PERCENT = "continuous_threshold_percent"
        const val KEY_CONTINUOUS_RECENT_MESSAGES = "continuous_recent_messages"
        const val KEY_CONTINUOUS_PROVIDER_POOL = "continuous_provider_pool"
        const val KEY_MANAGED_NVIDIA_KEY_COUNT = "managed_nvidia_key_count_v1"
        const val KEY_MANAGED_LITELLM_PROVIDER_POOL = "managed_litellm_provider_pool_v2"
        const val KEY_MANAGED_PROVIDER_KEYS_MIGRATED = "managed_provider_keys_migrated_v3"
        const val MAX_MANAGED_NVIDIA_KEYS = 32
        const val MAX_MANAGED_PROVIDER_KEYS = 32
        const val MIN_CONTEXT_WINDOW_TOKENS = 4_096
        const val MAX_CONTEXT_WINDOW_TOKENS = 2_000_000
        const val MIN_COMPACTION_PERCENT = 50
        const val MAX_COMPACTION_PERCENT = 95
        const val MIN_RECENT_MESSAGES = 4
        const val MAX_RECENT_MESSAGES = 80
    }
}

private fun defaultContinuousProviderPool(): Set<ProviderPreset> =
    ProviderPreset.entries
        .filterNot { it == ProviderPreset.DEMO }
        .toSet()
