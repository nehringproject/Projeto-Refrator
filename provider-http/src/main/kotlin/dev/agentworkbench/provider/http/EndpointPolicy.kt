package dev.agentworkbench.provider.http

import java.net.URI
import dev.agentworkbench.core.ProviderLocality

sealed interface EndpointValidation {
    data class Allowed(val uri: URI) : EndpointValidation
    data class Rejected(val reason: String) : EndpointValidation
}

object EndpointPolicy {
    fun inferLocality(endpoint: String): ProviderLocality? {
        val uri = runCatching { URI(endpoint.trim()) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        return if (isLocalHost(host)) {
            ProviderLocality.LOCAL_NETWORK
        } else {
            ProviderLocality.REMOTE
        }
    }

    fun validate(config: HttpProviderConfig): EndpointValidation {
        if (config.providerId.isBlank()) return rejected("Provider id is required")
        if (config.modelId.isBlank()) return rejected("Model id is required")
        if (config.modelId.length > MAX_MODEL_ID_LENGTH) {
            return rejected("Model id is too long")
        }
        val token = config.authorizationToken
        if (token != null) {
            if (token.isBlank()) return rejected("API key cannot be blank")
            if (token.length > MAX_TOKEN_LENGTH || token.any { it == '\r' || it == '\n' }) {
                return rejected("API key is invalid")
            }
        }

        val rawEndpoint = config.endpoint.trim()
        if (rawEndpoint.length !in 1..MAX_ENDPOINT_LENGTH) {
            return rejected("Endpoint is invalid")
        }
        val uri = runCatching { URI(rawEndpoint) }
            .getOrElse { return rejected("Endpoint is not a valid URI") }
        val scheme = uri.scheme?.lowercase()
            ?: return rejected("Endpoint scheme is required")
        val host = uri.host?.lowercase()
            ?: return rejected("Endpoint host is required")

        if (uri.userInfo != null) return rejected("Credentials in endpoint URLs are forbidden")
        if (uri.fragment != null) return rejected("Endpoint fragments are forbidden")
        if (uri.query != null) return rejected("Endpoint query strings are forbidden")
        if (uri.port !in setOf(-1) && uri.port !in 1..65_535) {
            return rejected("Endpoint port is invalid")
        }
        if (uri.path.isNullOrBlank() || uri.path == "/") {
            return rejected("Endpoint must include the API path")
        }

        return when (scheme) {
            "https" -> EndpointValidation.Allowed(uri)
            "http" -> {
                when {
                    !config.allowLocalCleartext ->
                        rejected("Cleartext HTTP is disabled in this distribution")

                    !isLocalHost(host) ->
                        rejected("Cleartext HTTP is restricted to local network addresses")

                    token != null ->
                        rejected("API keys cannot be sent over cleartext HTTP")

                    else -> EndpointValidation.Allowed(uri)
                }
            }

            else -> rejected("Only HTTPS and explicitly local HTTP endpoints are supported")
        }
    }

    private fun isLocalHost(rawHost: String): Boolean {
        val host = rawHost.removePrefix("[").removeSuffix("]").lowercase()
        if (host == "localhost" || host.endsWith(".local")) return true
        if (host == "::1") return true
        if (
            host.startsWith("fc") ||
            host.startsWith("fd") ||
            host.startsWith("fe8") ||
            host.startsWith("fe9") ||
            host.startsWith("fea") ||
            host.startsWith("feb")
        ) {
            return host.contains(':')
        }

        val octets = host.split('.').map { part ->
            part.toIntOrNull() ?: return false
        }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        return when {
            octets[0] == 10 -> true
            octets[0] == 127 -> true
            octets[0] == 169 && octets[1] == 254 -> true
            octets[0] == 172 && octets[1] in 16..31 -> true
            octets[0] == 192 && octets[1] == 168 -> true
            else -> false
        }
    }

    private fun rejected(reason: String) = EndpointValidation.Rejected(reason)

    private const val MAX_ENDPOINT_LENGTH = 2_048
    private const val MAX_MODEL_ID_LENGTH = 200
    private const val MAX_TOKEN_LENGTH = 4_096
}
