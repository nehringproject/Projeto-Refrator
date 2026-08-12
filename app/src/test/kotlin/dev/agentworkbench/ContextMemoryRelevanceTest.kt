package dev.agentworkbench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContextMemoryRelevanceTest {
    @Test
    fun `unrelated memory is not injected`() {
        assertEquals(
            0,
            ContextMemoryRepository.memoryRelevanceScore(
                query = "Explique este erro de compilação",
                memory = "O usuário prefere viagens de trem",
                pinned = false,
            ),
        )
    }

    @Test
    fun `related and pinned memories remain eligible`() {
        assertTrue(
            ContextMemoryRepository.memoryRelevanceScore(
                query = "Corrija o erro do compilador Kotlin",
                memory = "Este workspace usa Kotlin e Gradle",
                pinned = false,
            ) > 0,
        )
        assertTrue(
            ContextMemoryRepository.memoryRelevanceScore("", "Preferência fixa", pinned = true) > 0,
        )
    }
}
