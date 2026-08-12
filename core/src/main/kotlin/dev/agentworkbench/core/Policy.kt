package dev.agentworkbench.core

enum class Distribution {
    STANDARD,
    POWER,
}

enum class ExecutionMode {
    OBSERVE,
    PLAN,
    BUILD,
    AUTO,
    FULL,
}

enum class EnvironmentTrust {
    ANDROID_APP,
    REMOTE_SHARED,
    REMOTE_ISOLATED,
    POWER_USERSPACE,
}

enum class Capability {
    FILE_READ,
    FILE_WRITE,
    GIT_READ,
    GIT_WRITE,
    PROCESS_INSPECT,
    SHELL_EXECUTE,
    NETWORK_ACCESS,
    SECRET_USE,
    MCP_REMOTE,
    MCP_LOCAL,
    MODEL_LOCAL,
    DOWNLOADED_EXECUTABLE,
    PACKAGE_INSTALL,
    PROOT_ENVIRONMENT,
    UNRESTRICTED_FILESYSTEM,
    EXTERNAL_APP_CONTROL,
    SCREEN_CAPTURE,
    OCR_LOCAL,
    USER_SELECTED_FILES,
}

enum class ToolEffect {
    READ_ONLY,
    WORKSPACE_MUTATION,
    EXTERNAL_MUTATION,
    DESTRUCTIVE,
}

data class DistributionProfile(
    val distribution: Distribution,
    val compiledCapabilities: Set<Capability>,
    val playCompliant: Boolean,
) {
    init {
        if (playCompliant) {
            require(compiledCapabilities.intersect(STANDARD_FORBIDDEN).isEmpty()) {
                "A Play-compliant profile cannot compile power capabilities"
            }
        }
    }

    companion object {
        val STANDARD_FORBIDDEN = setOf(
            Capability.DOWNLOADED_EXECUTABLE,
            Capability.PACKAGE_INSTALL,
            Capability.PROOT_ENVIRONMENT,
            Capability.UNRESTRICTED_FILESYSTEM,
            Capability.EXTERNAL_APP_CONTROL,
            Capability.MCP_LOCAL,
        )

        fun standard(): DistributionProfile = DistributionProfile(
            distribution = Distribution.STANDARD,
            compiledCapabilities = Capability.entries.toSet() - STANDARD_FORBIDDEN,
            playCompliant = true,
        )

        fun power(): DistributionProfile = DistributionProfile(
            distribution = Distribution.POWER,
            compiledCapabilities = Capability.entries.toSet(),
            playCompliant = false,
        )
    }
}

data class ToolRequest(
    val id: String,
    val toolName: String,
    val capabilities: Set<Capability>,
    val effect: ToolEffect,
    val workspaceScoped: Boolean,
    val reversible: Boolean,
    val summary: String,
    val payloadFingerprint: String? = null,
)

data class PolicyContext(
    val distribution: DistributionProfile,
    val mode: ExecutionMode,
    val environmentTrust: EnvironmentTrust,
)

sealed interface PolicyDecision {
    val reason: String

    data class Allow(override val reason: String) : PolicyDecision

    data class Ask(
        override val reason: String,
        val strongConfirmation: Boolean,
    ) : PolicyDecision

    data class Deny(
        override val reason: String,
        val code: String,
    ) : PolicyDecision
}

class PolicyEngine {
    fun evaluate(context: PolicyContext, request: ToolRequest): PolicyDecision {
        val missing = request.capabilities - context.distribution.compiledCapabilities
        if (missing.isNotEmpty()) {
            return PolicyDecision.Deny(
                reason = "The installed distribution does not contain: ${missing.joinToString()}",
                code = "capability_not_compiled",
            )
        }

        if (
            context.distribution.distribution == Distribution.STANDARD &&
            request.capabilities.any { it in DistributionProfile.STANDARD_FORBIDDEN }
        ) {
            return PolicyDecision.Deny(
                reason = "The Standard distribution permanently forbids this capability",
                code = "standard_distribution_boundary",
            )
        }

        return when (context.mode) {
            ExecutionMode.OBSERVE -> evaluateObserve(request)
            ExecutionMode.PLAN -> evaluatePlan(request)
            ExecutionMode.BUILD -> evaluateBuild(request)
            ExecutionMode.AUTO -> evaluateAuto(request)
            ExecutionMode.FULL -> evaluateFull(context, request)
        }
    }

    private fun evaluateObserve(request: ToolRequest): PolicyDecision =
        if (request.effect == ToolEffect.READ_ONLY && request.capabilities.none(::sensitive)) {
            PolicyDecision.Allow("Observe mode permits non-sensitive reads")
        } else {
            PolicyDecision.Deny(
                reason = "Observe mode cannot mutate state or access sensitive capabilities",
                code = "observe_read_only",
            )
        }

    private fun evaluatePlan(request: ToolRequest): PolicyDecision {
        if (request.effect != ToolEffect.READ_ONLY) {
            return PolicyDecision.Deny(
                reason = "Plan mode cannot mutate state",
                code = "plan_read_only",
            )
        }
        return if (request.capabilities.any(::sensitive)) {
            PolicyDecision.Ask(
                reason = "Plan mode requires approval for sensitive reads or network access",
                strongConfirmation = false,
            )
        } else {
            PolicyDecision.Allow("Plan mode permits local inspection")
        }
    }

    private fun evaluateBuild(request: ToolRequest): PolicyDecision =
        when (request.effect) {
            ToolEffect.READ_ONLY ->
                if (request.capabilities.any(::sensitive)) {
                    PolicyDecision.Ask("Sensitive read requires approval", false)
                } else {
                    PolicyDecision.Allow("Build mode permits inspection")
                }

            ToolEffect.WORKSPACE_MUTATION -> PolicyDecision.Ask(
                reason = "Review the workspace change before execution",
                strongConfirmation = false,
            )

            ToolEffect.EXTERNAL_MUTATION -> PolicyDecision.Ask(
                reason = "This action changes an external system",
                strongConfirmation = true,
            )

            ToolEffect.DESTRUCTIVE -> PolicyDecision.Ask(
                reason = "Destructive actions always require strong confirmation",
                strongConfirmation = true,
            )
        }

    private fun evaluateAuto(request: ToolRequest): PolicyDecision =
        when {
            request.effect == ToolEffect.READ_ONLY && request.capabilities.none(::sensitive) ->
                PolicyDecision.Allow("Auto mode permits non-sensitive reads")

            request.effect == ToolEffect.WORKSPACE_MUTATION &&
                request.workspaceScoped &&
                request.reversible &&
                request.capabilities.none(::sensitive) ->
                PolicyDecision.Allow("Auto mode permits reversible workspace changes")

            request.effect == ToolEffect.DESTRUCTIVE ->
                PolicyDecision.Ask("Destructive action requires strong confirmation", true)

            else -> PolicyDecision.Ask(
                reason = "Auto mode does not silently cross a trust or privacy boundary",
                strongConfirmation = request.effect != ToolEffect.READ_ONLY,
            )
        }

    private fun evaluateFull(
        context: PolicyContext,
        request: ToolRequest,
    ): PolicyDecision {
        if (
            context.environmentTrust != EnvironmentTrust.REMOTE_ISOLATED &&
            request.effect == ToolEffect.DESTRUCTIVE
        ) {
            return PolicyDecision.Ask(
                reason = "Full mode still confirms destructive actions outside real isolation",
                strongConfirmation = true,
            )
        }
        return PolicyDecision.Allow("Full mode is enabled for the compiled distribution")
    }

    private fun sensitive(capability: Capability): Boolean = capability in setOf(
        Capability.NETWORK_ACCESS,
        Capability.SECRET_USE,
        Capability.UNRESTRICTED_FILESYSTEM,
        Capability.EXTERNAL_APP_CONTROL,
        Capability.SCREEN_CAPTURE,
        Capability.USER_SELECTED_FILES,
    )
}
