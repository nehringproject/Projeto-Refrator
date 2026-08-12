package dev.agentworkbench.provider.http

import dev.agentworkbench.core.ProviderLocality
import kotlin.test.Test
import kotlin.test.assertIs

class EndpointPolicyTest {
    @Test
    fun `remote https endpoint is allowed`() {
        val result = EndpointPolicy.validate(
            config(endpoint = "https://api.openai.com/v1/responses"),
        )

        assertIs<EndpointValidation.Allowed>(result)
    }

    @Test
    fun `public cleartext endpoint is rejected even in power mode`() {
        val result = EndpointPolicy.validate(
            config(
                endpoint = "http://example.com/v1/chat/completions",
                allowLocalCleartext = true,
            ),
        )

        assertIs<EndpointValidation.Rejected>(result)
    }

    @Test
    fun `private cleartext endpoint requires power mode and no key`() {
        assertIs<EndpointValidation.Rejected>(
            EndpointPolicy.validate(
                config(endpoint = "http://192.168.1.10:11434/v1/chat/completions"),
            ),
        )
        assertIs<EndpointValidation.Allowed>(
            EndpointPolicy.validate(
                config(
                    endpoint = "http://192.168.1.10:11434/v1/chat/completions",
                    allowLocalCleartext = true,
                ),
            ),
        )
        assertIs<EndpointValidation.Rejected>(
            EndpointPolicy.validate(
                config(
                    endpoint = "http://192.168.1.10:11434/v1/chat/completions",
                    allowLocalCleartext = true,
                    authorizationToken = "secret",
                ),
            ),
        )
    }

    @Test
    fun `userinfo query and fragment are rejected`() {
        assertIs<EndpointValidation.Rejected>(
            EndpointPolicy.validate(
                config(endpoint = "https://user:pass@example.com/v1/chat/completions"),
            ),
        )
        assertIs<EndpointValidation.Rejected>(
            EndpointPolicy.validate(
                config(endpoint = "https://example.com/v1/chat/completions?key=value"),
            ),
        )
        assertIs<EndpointValidation.Rejected>(
            EndpointPolicy.validate(
                config(endpoint = "https://example.com/v1/chat/completions#fragment"),
            ),
        )
    }

    private fun config(
        endpoint: String,
        allowLocalCleartext: Boolean = false,
        authorizationToken: String? = null,
    ) = HttpProviderConfig(
        providerId = "test",
        displayName = "Test",
        protocol = HttpProviderProtocol.OPENAI_COMPATIBLE_CHAT,
        endpoint = endpoint,
        modelId = "test-model",
        locality = ProviderLocality.REMOTE,
        authorizationToken = authorizationToken,
        safetyIdentifier = null,
        allowLocalCleartext = allowLocalCleartext,
    )
}
