package dev.agentworkbench

import kotlin.test.Test
import kotlin.test.assertEquals

class LocalGgufRuntimePlannerTest {
    private val gib = 1_073_741_824L

    @Test
    fun `four gigabyte phone uses conservative context`() {
        val profile = LocalGgufRuntimePlanner.plan(4 * gib, 1 * gib, 640L * 1_048_576L, 40_960)
        assertEquals(2_048, profile.contextTokens)
    }

    @Test
    fun `better phone unlocks larger context`() {
        val profile = LocalGgufRuntimePlanner.plan(12 * gib, 8 * gib, 700L * 1_048_576L, 32_768)
        assertEquals(16_384, profile.contextTokens)
    }

    @Test
    fun `large model lowers context even on capable phone`() {
        val profile = LocalGgufRuntimePlanner.plan(16 * gib, 10 * gib, 5 * gib, 32_768)
        assertEquals(2_048, profile.contextTokens)
    }

    @Test
    fun `trained context remains a hard ceiling`() {
        val profile = LocalGgufRuntimePlanner.plan(12 * gib, 8 * gib, 700L * 1_048_576L, 4_096)
        assertEquals(4_096, profile.contextTokens)
    }
}
