package dev.agentworkbench

import dev.agentworkbench.core.ExecutionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExecutionSnapshotTest {
    @Test
    fun providerSettingsRoundTripPreservesExecutionContract() {
        val original = ProviderSettings(
            preset = ProviderPreset.CUSTOM_OPENAI,
            endpoint = "https://example.test/v1/chat/completions",
            modelId = "model-a",
            executionMode = ExecutionMode.FULL,
            continuousChat = ContinuousChatSettings(
                enabled = true,
                automaticProviderSwitching = false,
                contextWindowTokens = 65_536,
                compactionThresholdPercent = 70,
                recentMessagesToKeep = 9,
                providerPool = linkedSetOf(ProviderPreset.GROQ, ProviderPreset.LOCAL_GGUF),
            ),
        )

        assertEquals(original, providerSettingsFromRunSnapshot(original.toRunSnapshotJson()))
    }

    @Test
    fun recoveryNetworkRequirementRecognizesOfflineRoutes() {
        val remote = ProviderSettings(
            preset = ProviderPreset.GROQ,
            endpoint = ProviderPreset.GROQ.defaultEndpoint,
            modelId = ProviderPreset.GROQ.defaultModel,
            executionMode = ExecutionMode.BUILD,
        )
        val local = remote.copy(preset = ProviderPreset.LOCAL_GGUF)
        val mixed = remote.copy(
            continuousChat = remote.continuousChat.copy(
                enabled = true,
                providerPool = setOf(ProviderPreset.GROQ, ProviderPreset.LOCAL_GGUF),
            ),
        )

        assertTrue(remote.requiresNetworkForRecovery())
        assertFalse(local.requiresNetworkForRecovery())
        assertFalse(mixed.requiresNetworkForRecovery())
    }
}
