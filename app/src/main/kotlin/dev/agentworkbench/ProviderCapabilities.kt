package dev.agentworkbench

/**
 * What a provider route can do, so the app adapts instead of treating every provider alike:
 * the reasoning selector only appears where the API honours it, the context bar uses a typical
 * window for that family, and image attachments are blocked where the endpoint is text-only.
 * Windows are typical per family (models differ), so the UI labels them as approximate.
 */
data class ProviderCapabilities(
    val reasoningEffort: Boolean,
    val reasoningText: Boolean,
    val images: Boolean,
    val tools: Boolean,
    val contextWindowTokens: Int?,
)

val ProviderPreset.capabilities: ProviderCapabilities
    get() = when (this) {
        ProviderPreset.DEMO -> ProviderCapabilities(false, false, false, false, 8_192)
        ProviderPreset.LOCAL_GGUF -> ProviderCapabilities(false, true, false, true, null)
        ProviderPreset.OPENAI, ProviderPreset.CHATGPT_PLAN ->
            ProviderCapabilities(reasoningEffort = true, reasoningText = true, images = true, tools = true, contextWindowTokens = 200_000)
        ProviderPreset.ANTHROPIC ->
            ProviderCapabilities(reasoningEffort = false, reasoningText = false, images = true, tools = true, contextWindowTokens = 200_000)
        ProviderPreset.GEMINI ->
            ProviderCapabilities(reasoningEffort = true, reasoningText = false, images = true, tools = true, contextWindowTokens = 1_000_000)
        ProviderPreset.XAI ->
            ProviderCapabilities(reasoningEffort = true, reasoningText = true, images = true, tools = true, contextWindowTokens = 128_000)
        ProviderPreset.OPENROUTER ->
            ProviderCapabilities(reasoningEffort = true, reasoningText = true, images = true, tools = true, contextWindowTokens = 128_000)
        ProviderPreset.GROQ ->
            ProviderCapabilities(reasoningEffort = true, reasoningText = true, images = false, tools = true, contextWindowTokens = 128_000)
        ProviderPreset.DEEPSEEK ->
            ProviderCapabilities(reasoningEffort = false, reasoningText = true, images = false, tools = true, contextWindowTokens = 128_000)
        ProviderPreset.OPENCODE_ZEN, ProviderPreset.OPENCODE_GO, ProviderPreset.KENARI, ProviderPreset.CUSTOM_OPENAI, ProviderPreset.LITELLM ->
            ProviderCapabilities(reasoningEffort = true, reasoningText = true, images = true, tools = true, contextWindowTokens = 128_000)
        ProviderPreset.NVIDIA, ProviderPreset.CEREBRAS, ProviderPreset.MISTRAL, ProviderPreset.TOGETHER, ProviderPreset.FIREWORKS ->
            ProviderCapabilities(reasoningEffort = false, reasoningText = true, images = false, tools = true, contextWindowTokens = 128_000)
        ProviderPreset.OLLAMA ->
            ProviderCapabilities(reasoningEffort = false, reasoningText = true, images = false, tools = true, contextWindowTokens = 32_768)
    }

/** Short human summary for the model sheet, e.g. "raciocínio visível · imagens · ~200k". */
fun ProviderCapabilities.summary(): String = buildList {
    if (reasoningEffort) add("nível de raciocínio")
    if (reasoningText) add("raciocínio visível")
    if (images) add("imagens")
    if (!tools) add("sem ferramentas")
    contextWindowTokens?.let { add("~${if (it >= 1_000_000) "${it / 1_000_000}M" else "${it / 1_000}k"} de contexto") }
}.joinToString(" · ")
