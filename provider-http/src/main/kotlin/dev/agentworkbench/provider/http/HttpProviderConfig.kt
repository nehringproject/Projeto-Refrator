package dev.agentworkbench.provider.http

import dev.agentworkbench.core.ProviderLocality

enum class HttpProviderProtocol {
    OPENAI_RESPONSES,
    OPENAI_COMPATIBLE_CHAT,

    /**
     * Responses API billed to the user's ChatGPT plan (Sign in with ChatGPT). Same stream format,
     * but the route rejects system-role items and several request fields; see buildResponsesBody.
     */
    CHATGPT_PLAN_RESPONSES,
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
    /**
     * Fetched right before each request, for short-lived OAuth tokens that must be refreshed off
     * the main thread. When set it takes precedence over [authorizationToken].
     */
    val tokenProvider: (suspend () -> String?)? = null,
)
