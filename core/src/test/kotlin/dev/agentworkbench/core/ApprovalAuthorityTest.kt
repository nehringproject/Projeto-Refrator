package dev.agentworkbench.core

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApprovalAuthorityTest {
    private val now = Instant.parse("2026-07-30T12:00:00Z")

    @Test
    fun `denied decision never creates challenge`() {
        val authority = ApprovalAuthority()
        val challenge = authority.prepare(
            request(),
            PolicyDecision.Deny("no", "denied"),
            now,
        )

        assertNull(challenge)
    }

    @Test
    fun `strong challenge rejects passive confirmation`() {
        val authority = ApprovalAuthority()
        val request = request(effect = ToolEffect.DESTRUCTIVE)
        val challenge = assertNotNull(
            authority.prepare(
                request,
                PolicyDecision.Ask("danger", strongConfirmation = true),
                now,
            ),
        )

        val result = authority.issue(
            challenge.id,
            request,
            ConfirmationMethod.EXPLICIT,
            now,
        )

        assertIs<PermitResult.Rejected>(result)
    }

    @Test
    fun `changed request cannot reuse approval`() {
        val authority = ApprovalAuthority()
        val original = request(summary = "read status")
        val challenge = assertNotNull(
            authority.prepare(
                original,
                PolicyDecision.Ask("review", strongConfirmation = false),
                now,
            ),
        )
        val changed = original.copy(
            effect = ToolEffect.DESTRUCTIVE,
            summary = "delete workspace",
        )

        val result = authority.issue(
            challenge.id,
            changed,
            ConfirmationMethod.STRONG,
            now,
        )

        assertIs<PermitResult.Rejected>(result)
    }

    @Test
    fun `permit is bound to exact request`() {
        val authority = ApprovalAuthority()
        val original = request()
        val challenge = assertNotNull(
            authority.prepare(
                original,
                PolicyDecision.Allow("safe"),
                now,
            ),
        )
        val permit = assertIs<PermitResult.Issued>(
            authority.issue(
                challenge.id,
                original,
                ConfirmationMethod.PASSIVE,
                now,
            ),
        ).permit

        assertTrue(authority.validate(permit, original))
        assertFalse(authority.validate(permit, original.copy(reversible = false)))
    }

    @Test
    fun `expired challenge is rejected`() {
        val authority = ApprovalAuthority(Duration.ofSeconds(30))
        val original = request()
        val challenge = assertNotNull(
            authority.prepare(
                original,
                PolicyDecision.Allow("safe"),
                now,
            ),
        )

        val result = authority.issue(
            challenge.id,
            original,
            ConfirmationMethod.PASSIVE,
            now.plusSeconds(31),
        )

        assertIs<PermitResult.Rejected>(result)
    }

    @Test
    fun `authorization is bound to exact command payload`() {
        val authority = ApprovalAuthority()
        val command = command()
        val request = request().copy(payloadFingerprint = command.fingerprint())
        val challenge = assertNotNull(
            authority.prepare(
                request,
                PolicyDecision.Ask("review", strongConfirmation = true),
                now,
            ),
        )
        val permit = assertIs<PermitResult.Issued>(
            authority.issue(
                challenge.id,
                request,
                ConfirmationMethod.STRONG,
                now,
            ),
        ).permit
        val authorization = assertNotNull(authority.bind(permit, request, command))

        assertTrue(authorization.validates(command))
        assertFalse(
            authorization.validates(
                command.copy(arguments = listOf("-c", "rm -rf files")),
            ),
        )
    }

    @Test
    fun `authorization cannot bind a changed command`() {
        val authority = ApprovalAuthority()
        val command = command()
        val request = request().copy(payloadFingerprint = command.fingerprint())
        val challenge = assertNotNull(
            authority.prepare(
                request,
                PolicyDecision.Ask("review", strongConfirmation = true),
                now,
            ),
        )
        val permit = assertIs<PermitResult.Issued>(
            authority.issue(
                challenge.id,
                request,
                ConfirmationMethod.STRONG,
                now,
            ),
        ).permit

        assertNull(
            authority.bind(
                permit,
                request,
                command.copy(workingDirectory = "/data/local/tmp"),
            ),
        )
    }

    private fun request(
        effect: ToolEffect = ToolEffect.WORKSPACE_MUTATION,
        summary: String = "write file",
    ) = ToolRequest(
        id = "tool-1",
        toolName = "workspace",
        capabilities = setOf(Capability.FILE_WRITE),
        effect = effect,
        workspaceScoped = true,
        reversible = true,
        summary = summary,
    )

    private fun command() = CommandSpec(
        id = "tool-1",
        executable = "/system/bin/sh",
        arguments = listOf("-c", "id"),
        workingDirectory = "/data/data/example/files",
        timeoutMillis = 5_000,
        outputLimitBytes = 8_192,
        idempotent = false,
    )
}
