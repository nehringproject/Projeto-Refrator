package dev.agentworkbench.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class ConfirmationMethod {
    PASSIVE,
    EXPLICIT,
    STRONG,
}

data class ApprovalChallenge(
    val id: String,
    val requestFingerprint: String,
    val toolRequestId: String,
    val strongConfirmationRequired: Boolean,
    val expiresAt: Instant,
    val explanation: String,
)

class ExecutionPermit internal constructor(
    val challengeId: String,
    val requestFingerprint: String,
    val issuedAt: Instant,
    val method: ConfirmationMethod,
)

class CommandAuthorization internal constructor(
    val request: ToolRequest,
    val permit: ExecutionPermit,
) {
    fun validates(command: CommandSpec): Boolean =
        permit.requestFingerprint == request.fingerprint() &&
            request.payloadFingerprint == command.fingerprint()
}

sealed interface PermitResult {
    data class Issued(val permit: ExecutionPermit) : PermitResult
    data class Rejected(val reason: String) : PermitResult
}

/**
 * Creates short-lived, request-bound execution permits.
 *
 * A permit fingerprints every security-relevant ToolRequest field. Changing
 * arguments or effects after the approval invalidates the permit and prevents a
 * model/provider from turning an approved read into a different operation.
 */
class ApprovalAuthority(
    private val challengeLifetime: Duration = Duration.ofMinutes(2),
) {
    private val challenges = mutableMapOf<String, ApprovalChallenge>()

    @Synchronized
    fun prepare(
        request: ToolRequest,
        decision: PolicyDecision,
        now: Instant,
    ): ApprovalChallenge? {
        if (decision is PolicyDecision.Deny) return null

        val challenge = ApprovalChallenge(
            id = UUID.randomUUID().toString(),
            requestFingerprint = request.fingerprint(),
            toolRequestId = request.id,
            strongConfirmationRequired =
                (decision as? PolicyDecision.Ask)?.strongConfirmation == true,
            expiresAt = now.plus(challengeLifetime),
            explanation = decision.reason,
        )
        challenges[challenge.id] = challenge
        return challenge
    }

    @Synchronized
    fun issue(
        challengeId: String,
        request: ToolRequest,
        method: ConfirmationMethod,
        now: Instant,
    ): PermitResult {
        val challenge = challenges.remove(challengeId)
            ?: return PermitResult.Rejected("Unknown or already consumed challenge")

        if (now.isAfter(challenge.expiresAt)) {
            return PermitResult.Rejected("Approval challenge expired")
        }
        if (request.fingerprint() != challenge.requestFingerprint) {
            return PermitResult.Rejected("Tool request changed after policy evaluation")
        }
        if (
            challenge.strongConfirmationRequired &&
            method != ConfirmationMethod.STRONG
        ) {
            return PermitResult.Rejected("Strong confirmation is required")
        }

        return PermitResult.Issued(
            ExecutionPermit(
                challengeId = challenge.id,
                requestFingerprint = challenge.requestFingerprint,
                issuedAt = now,
                method = method,
            ),
        )
    }

    fun validate(permit: ExecutionPermit, request: ToolRequest): Boolean =
        permit.requestFingerprint == request.fingerprint()

    /**
     * Produces an auditable, request-bound permit only after the policy engine
     * has explicitly allowed the request. This is used by opt-in automatic
     * execution modes; it is not a substitute for an Ask challenge.
     */
    fun issuePolicyPermit(
        request: ToolRequest,
        decision: PolicyDecision,
        now: Instant,
    ): ExecutionPermit? {
        if (decision !is PolicyDecision.Allow) return null
        return ExecutionPermit(
            challengeId = "policy-${UUID.randomUUID()}",
            requestFingerprint = request.fingerprint(),
            issuedAt = now,
            method = ConfirmationMethod.PASSIVE,
        )
    }

    fun bind(
        permit: ExecutionPermit,
        request: ToolRequest,
        command: CommandSpec,
    ): CommandAuthorization? {
        if (!validate(permit, request)) return null
        if (request.payloadFingerprint != command.fingerprint()) return null
        return CommandAuthorization(request, permit)
    }
}

fun ToolRequest.fingerprint(): String {
    val canonical = buildString {
        append(id)
        append('\u0000')
        append(toolName)
        append('\u0000')
        capabilities.map(Capability::name).sorted().forEach {
            append(it)
            append('\u001f')
        }
        append(effect.name)
        append('\u0000')
        append(workspaceScoped)
        append('\u0000')
        append(reversible)
        append('\u0000')
        append(summary)
        append('\u0000')
        append(payloadFingerprint ?: "")
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
