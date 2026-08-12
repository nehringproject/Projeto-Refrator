package dev.agentworkbench.core

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PolicyEngineTest {
    private val engine = PolicyEngine()

    @Test
    fun `standard profile can never compile power capabilities`() {
        val standard = DistributionProfile.standard()

        assertTrue(
            standard.compiledCapabilities
                .intersect(DistributionProfile.STANDARD_FORBIDDEN)
                .isEmpty(),
        )
    }

    @Test
    fun `plan mode denies workspace mutation`() {
        val decision = engine.evaluate(
            context = PolicyContext(
                distribution = DistributionProfile.standard(),
                mode = ExecutionMode.PLAN,
                environmentTrust = EnvironmentTrust.ANDROID_APP,
            ),
            request = request(
                capabilities = setOf(Capability.FILE_WRITE),
                effect = ToolEffect.WORKSPACE_MUTATION,
            ),
        )

        assertIs<PolicyDecision.Deny>(decision)
    }

    @Test
    fun `standard full mode still denies downloaded executable`() {
        val decision = engine.evaluate(
            context = PolicyContext(
                distribution = DistributionProfile.standard(),
                mode = ExecutionMode.FULL,
                environmentTrust = EnvironmentTrust.REMOTE_ISOLATED,
            ),
            request = request(
                capabilities = setOf(Capability.DOWNLOADED_EXECUTABLE),
                effect = ToolEffect.WORKSPACE_MUTATION,
            ),
        )

        assertIs<PolicyDecision.Deny>(decision)
    }

    @Test
    fun `auto permits reversible scoped workspace edit`() {
        val decision = engine.evaluate(
            context = PolicyContext(
                distribution = DistributionProfile.standard(),
                mode = ExecutionMode.AUTO,
                environmentTrust = EnvironmentTrust.ANDROID_APP,
            ),
            request = request(
                capabilities = setOf(Capability.FILE_WRITE),
                effect = ToolEffect.WORKSPACE_MUTATION,
                reversible = true,
            ),
        )

        assertIs<PolicyDecision.Allow>(decision)
    }

    @Test
    fun `destructive local action always asks even in full mode`() {
        val decision = engine.evaluate(
            context = PolicyContext(
                distribution = DistributionProfile.power(),
                mode = ExecutionMode.FULL,
                environmentTrust = EnvironmentTrust.POWER_USERSPACE,
            ),
            request = request(
                capabilities = setOf(Capability.SHELL_EXECUTE),
                effect = ToolEffect.DESTRUCTIVE,
                reversible = false,
            ),
        )

        val ask = assertIs<PolicyDecision.Ask>(decision)
        assertTrue(ask.strongConfirmation)
    }

    private fun request(
        capabilities: Set<Capability>,
        effect: ToolEffect,
        reversible: Boolean = false,
    ) = ToolRequest(
        id = "tool-1",
        toolName = "test",
        capabilities = capabilities,
        effect = effect,
        workspaceScoped = true,
        reversible = reversible,
        summary = "test request",
    )
}

