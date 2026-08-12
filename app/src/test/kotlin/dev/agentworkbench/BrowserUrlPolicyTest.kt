package dev.agentworkbench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserUrlPolicyTest {
    @Test
    fun `accepts public https and rejects non-browser schemes`() {
        assertTrue(BrowserUrlPolicy.isSyntacticallyAllowed("https://example.com/path?q=1"))
        assertFalse(BrowserUrlPolicy.isSyntacticallyAllowed("http://example.com"))
        assertFalse(BrowserUrlPolicy.isSyntacticallyAllowed("file:///data/local/tmp/file"))
        assertFalse(BrowserUrlPolicy.isSyntacticallyAllowed("javascript:alert(1)"))
        assertFalse(BrowserUrlPolicy.isSyntacticallyAllowed("intent://example.com"))
    }

    @Test
    fun `blocks local private and documentation destinations`() {
        listOf(
            "https://localhost",
            "https://router.local",
            "https://127.0.0.1",
            "https://10.0.0.1",
            "https://192.168.1.1",
            "https://100.64.0.1",
            "https://192.0.2.1",
            "https://198.51.100.1",
            "https://203.0.113.1",
            "https://[::1]",
            "https://[fc00::1]",
            "https://[2001:db8::1]",
        ).forEach { url ->
            assertFailsWith<IllegalArgumentException>(url) {
                BrowserUrlPolicy.requirePublicHttps(url)
            }
        }
    }

    @Test
    fun `keeps path query and fragment for an allowed public address`() {
        val uri = BrowserUrlPolicy.requirePublicHttps("https://8.8.8.8/search?q=test#result")
        assertEquals("/search", uri.path)
        assertEquals("q=test", uri.query)
        assertEquals("result", uri.fragment)
    }

    @Test
    fun `observed URLs remove credentials query fragment and secret path segments`() {
        assertEquals(
            "https://example.com/reset/redacted",
            safeObservedUrl("https://user:pass@example.com/reset/ABCD1234efgh5678IJKL9012?key=secret#fragment"),
        )
        assertEquals(
            "https://example.com/files/redacted",
            safeObservedUrl("https://example.com/files/0123456789abcdef0123456789abcdef"),
        )
        assertEquals(
            "https://example.com/articles/readable-title",
            safeObservedUrl("https://example.com/articles/readable-title"),
        )
    }
}
