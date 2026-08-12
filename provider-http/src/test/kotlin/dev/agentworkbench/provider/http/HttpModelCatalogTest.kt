package dev.agentworkbench.provider.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HttpModelCatalogTest {
    @Test
    fun `derives models endpoint without changing origin`() {
        assertEquals(
            "https://api.openai.com/v1/models",
            HttpModelCatalog
                .deriveCatalogUri("https://api.openai.com/v1/responses")
                .toString(),
        )
        assertEquals(
            "http://192.168.1.2:11434/v1/models",
            HttpModelCatalog
                .deriveCatalogUri(
                    "http://192.168.1.2:11434/v1/chat/completions",
                )
                .toString(),
        )
        assertNull(HttpModelCatalog.deriveCatalogUri("https://example.com/custom"))
    }

    @Test
    fun `parses and sorts compatible model catalogs`() {
        val models = HttpModelCatalog.parseModels(
            """
                {
                  "data": [
                    {"id": "z-model", "name": "Zulu", "owned_by": "vendor-z"},
                    {"id": "a-model", "name": "Alpha"},
                    {"id": "a-model", "name": "Duplicate"}
                  ]
                }
            """.trimIndent(),
        )

        assertEquals(listOf("a-model", "z-model"), models.map { it.id })
        assertEquals("vendor-z", models.last().owner)
    }
}
