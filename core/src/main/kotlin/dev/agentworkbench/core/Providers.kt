package dev.agentworkbench.core

enum class ProviderLocality {
    ON_DEVICE,
    LOCAL_NETWORK,
    REMOTE,
}

enum class ModelCapability {
    TEXT,
    VISION,
    AUDIO,
    TOOL_CALLING,
    PARALLEL_TOOLS,
    JSON_SCHEMA,
    REASONING_CONTROL,
    PROMPT_CACHE,
    EMBEDDINGS,
}

data class ProviderDescriptor(
    val id: String,
    val displayName: String,
    val locality: ProviderLocality,
    val capabilities: Set<ModelCapability>,
    val healthy: Boolean,
    val costTier: Int,
    val priority: Int,
)

enum class DataBoundary {
    ON_DEVICE_ONLY,
    LOCAL_NETWORK_ALLOWED,
    REMOTE_ALLOWED,
}

data class RoutingRequest(
    val requiredCapabilities: Set<ModelCapability>,
    val dataBoundary: DataBoundary,
    val preferredProviderId: String? = null,
)

sealed interface RoutingDecision {
    data class Selected(
        val provider: ProviderDescriptor,
        val requiresBoundaryConfirmation: Boolean,
        val reason: String,
    ) : RoutingDecision

    data class NoRoute(val reason: String) : RoutingDecision
}

class ProviderRouter {
    fun route(
        request: RoutingRequest,
        providers: Collection<ProviderDescriptor>,
    ): RoutingDecision {
        val capable = providers
            .asSequence()
            .filter { it.healthy }
            .filter { it.capabilities.containsAll(request.requiredCapabilities) }
            .filter { allowedByBoundary(it.locality, request.dataBoundary) }
            .toList()

        if (capable.isEmpty()) {
            return RoutingDecision.NoRoute(
                "No healthy provider satisfies capabilities and data boundary",
            )
        }

        request.preferredProviderId?.let { preferredId ->
            val preferred = capable.firstOrNull { it.id == preferredId }
            if (preferred != null) {
                return RoutingDecision.Selected(
                    provider = preferred,
                    requiresBoundaryConfirmation = false,
                    reason = "Preferred provider is healthy and eligible",
                )
            }

            val configuredPreferred = providers.firstOrNull { it.id == preferredId }
            if (
                configuredPreferred != null &&
                configuredPreferred.locality != ProviderLocality.REMOTE
            ) {
                val fallback = choose(capable)
                return RoutingDecision.Selected(
                    provider = fallback,
                    requiresBoundaryConfirmation =
                        fallback.locality == ProviderLocality.REMOTE,
                    reason = "Preferred local provider is unavailable; locality changed",
                )
            }
        }

        return RoutingDecision.Selected(
            provider = choose(capable),
            requiresBoundaryConfirmation = false,
            reason = "Best eligible provider selected",
        )
    }

    private fun choose(providers: Collection<ProviderDescriptor>): ProviderDescriptor =
        providers.sortedWith(
            compareBy<ProviderDescriptor> { localityRank(it.locality) }
                .thenBy { it.costTier }
                .thenByDescending { it.priority },
        ).first()

    private fun allowedByBoundary(
        locality: ProviderLocality,
        boundary: DataBoundary,
    ): Boolean =
        when (boundary) {
            DataBoundary.ON_DEVICE_ONLY -> locality == ProviderLocality.ON_DEVICE
            DataBoundary.LOCAL_NETWORK_ALLOWED -> locality != ProviderLocality.REMOTE
            DataBoundary.REMOTE_ALLOWED -> true
        }

    private fun localityRank(locality: ProviderLocality): Int =
        when (locality) {
            ProviderLocality.ON_DEVICE -> 0
            ProviderLocality.LOCAL_NETWORK -> 1
            ProviderLocality.REMOTE -> 2
        }
}

