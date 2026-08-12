package dev.agentworkbench.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProviderRouterTest {
    private val router = ProviderRouter()
    private val local = ProviderDescriptor(
        id = "local",
        displayName = "On-device",
        locality = ProviderLocality.ON_DEVICE,
        capabilities = setOf(ModelCapability.TEXT, ModelCapability.TOOL_CALLING),
        healthy = true,
        costTier = 0,
        priority = 10,
    )
    private val remote = ProviderDescriptor(
        id = "remote",
        displayName = "Remote",
        locality = ProviderLocality.REMOTE,
        capabilities = setOf(ModelCapability.TEXT, ModelCapability.TOOL_CALLING),
        healthy = true,
        costTier = 2,
        priority = 100,
    )

    @Test
    fun `on-device boundary never selects remote provider`() {
        val result = router.route(
            RoutingRequest(
                requiredCapabilities = setOf(ModelCapability.TEXT),
                dataBoundary = DataBoundary.ON_DEVICE_ONLY,
            ),
            listOf(remote),
        )

        assertIs<RoutingDecision.NoRoute>(result)
    }

    @Test
    fun `local provider is preferred when both are eligible`() {
        val result = assertIs<RoutingDecision.Selected>(
            router.route(
                RoutingRequest(
                    requiredCapabilities = setOf(ModelCapability.TOOL_CALLING),
                    dataBoundary = DataBoundary.REMOTE_ALLOWED,
                ),
                listOf(remote, local),
            ),
        )

        assertEquals("local", result.provider.id)
        assertFalse(result.requiresBoundaryConfirmation)
    }

    @Test
    fun `remote fallback from failed preferred local requires confirmation`() {
        val unhealthyLocal = local.copy(healthy = false)
        val result = assertIs<RoutingDecision.Selected>(
            router.route(
                RoutingRequest(
                    requiredCapabilities = setOf(ModelCapability.TEXT),
                    dataBoundary = DataBoundary.REMOTE_ALLOWED,
                    preferredProviderId = "local",
                ),
                listOf(unhealthyLocal, remote),
            ),
        )

        assertEquals("remote", result.provider.id)
        assertTrue(result.requiresBoundaryConfirmation)
    }
}

