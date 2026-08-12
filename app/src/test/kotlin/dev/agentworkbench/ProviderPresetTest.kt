package dev.agentworkbench

import dev.agentworkbench.provider.http.HttpProviderProtocol
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderPresetTest {
    @Test
    fun `includes the requested hosted providers`() {
        assertEquals(
            mapOf(
                ProviderPreset.OPENCODE_ZEN to "https://opencode.ai/zen/v1/chat/completions",
                ProviderPreset.CEREBRAS to "https://api.cerebras.ai/v1/chat/completions",
                ProviderPreset.MISTRAL to "https://api.mistral.ai/v1/chat/completions",
            ),
            listOf(
                ProviderPreset.OPENCODE_ZEN,
                ProviderPreset.CEREBRAS,
                ProviderPreset.MISTRAL,
            ).associateWith { it.defaultEndpoint },
        )
    }

    @Test
    fun `litellm defaults to the managed in apk binder gateway`() {
        assertEquals(
            MANAGED_LITELLM_ENDPOINT,
            ProviderPreset.LITELLM.defaultEndpoint,
        )
        assertEquals(
            HttpProviderProtocol.OPENAI_COMPATIBLE_CHAT,
            ProviderPreset.LITELLM.protocol,
        )
        assertEquals(MANAGED_LITELLM_AUTO_MODEL, ProviderPreset.LITELLM.defaultModel)
        assertTrue(!ProviderPreset.LITELLM.requiresApiKey)
    }

    @Test
    fun `hosted compatible presets use complete https chat endpoints`() {
        val localPresets = setOf(
            ProviderPreset.DEMO,
            ProviderPreset.LOCAL_GGUF,
            ProviderPreset.OLLAMA,
            ProviderPreset.LITELLM,
        )

        ProviderPreset.entries
            .filterNot { it in localPresets || it.protocol == HttpProviderProtocol.OPENAI_RESPONSES }
            .forEach { preset ->
                val endpoint = URI(preset.defaultEndpoint)
                assertEquals("https", endpoint.scheme, preset.displayName)
                assertTrue(
                    endpoint.path.endsWith("/chat/completions"),
                    "${preset.displayName} must declare a full Chat Completions endpoint",
                )
                assertTrue(preset.defaultModel.isNotBlank(), preset.displayName)
            }
    }
}
