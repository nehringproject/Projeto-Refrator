package dev.agentworkbench

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteContextRedactorTest {
    @Test
    fun removesSecretsOtpAndValidCardsWithoutEatingOrdinaryNumbers() {
        val original = """
            senha: valor-secreto-de-teste
            otp 654321
            api_key=gsk_TEST_FIXTURE_NOT_A_CREDENTIAL_000000
            Authorization: Bearer example-token-value-not-real-123456
            cartão 4111 1111 1111 1111
            build 1234567890123
        """.trimIndent()
        val redacted = RemoteContextRedactor.redactText(original)
        assertFalse(redacted.contains("valor-secreto-de-teste"))
        assertFalse(redacted.contains("654321"))
        assertFalse(redacted.contains("gsk_"))
        assertFalse(redacted.contains("4111 1111 1111 1111"))
        assertTrue(redacted.contains("1234567890123"))
        assertTrue(redacted.contains("[REDACTED_LOCAL]"))
    }

    @Test
    fun removesUnlabelledJwtAndPrivateKeyBlocks() {
        val original = """
            eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0LXVzZXIifQ.c3ludGhldGljLXNpZ25hdHVyZQ
            -----BEGIN PRIVATE KEY-----
            c3ludGhldGljLW5vdC1hLXJlYWwta2V5
            -----END PRIVATE KEY-----
        """.trimIndent()

        val redacted = RemoteContextRedactor.redactText(original)

        assertFalse(redacted.contains("eyJhbGci"))
        assertFalse(redacted.contains("BEGIN PRIVATE KEY"))
        assertTrue(redacted.contains("[REDACTED_LOCAL]"))
    }
}
