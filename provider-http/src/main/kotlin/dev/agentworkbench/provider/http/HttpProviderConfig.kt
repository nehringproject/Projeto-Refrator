package dev.agentworkbench.provider.http

import dev.agentworkbench.core.ProviderLocality

enum class HttpProviderProtocol {
    OPENAI_RESPONSES,
    OPENAI_COMPATIBLE_CHAT,
}

data class HttpProviderConfig(
    val providerId: String,
    val displayName: String,
    val protocol: HttpProviderProtocol,
    val endpoint: String,
    val modelId: String,
    val locality: ProviderLocality,
    val authorizationToken: String?,
    val safetyIdentifier: String?,
    val allowLocalCleartext: Boolean,
    val connectTimeoutMillis: Int = 15_000,
    val readTimeoutMillis: Int = 120_000,
)
