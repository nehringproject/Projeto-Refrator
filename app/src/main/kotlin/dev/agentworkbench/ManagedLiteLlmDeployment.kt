package dev.agentworkbench

import dev.agentworkbench.core.ModelProvider

const val MANAGED_LITELLM_AUTO_MODEL = "agent-auto"

/** In-memory gateway profile. Deliberately not a data class so secrets never enter generated toString. */
class ManagedLiteLlmDeployment(
    val alias: String,
    val displayName: String,
    val providerModel: String,
    val litellmModel: String,
    val apiBase: String,
    val apiKey: String?,
    val fallbackProvider: ModelProvider,
) {
    override fun toString(): String = "ManagedLiteLlmDeployment(alias=$alias, secret=<redacted>)"
}
