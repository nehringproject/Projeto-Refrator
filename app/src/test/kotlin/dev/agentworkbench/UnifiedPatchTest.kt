package dev.agentworkbench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UnifiedPatchTest {
    @Test
    fun appliesMultipleHunksAndCountsChanges() {
        val original = "one\ntwo\nthree\nfour\n"
        val patch = """
            @@ -1,2 +1,2 @@
             one
            -two
            +TWO
            @@ -4,1 +4,2 @@
             four
            +five
        """.trimIndent()

        val result = UnifiedPatch.apply(original, patch)

        assertEquals("one\nTWO\nthree\nfour\nfive\n", result.text)
        assertEquals(2, result.added)
        assertEquals(1, result.removed)
    }

    @Test
    fun rejectsPatchWhenContextDoesNotMatch() {
        assertFailsWith<IllegalArgumentException> {
            UnifiedPatch.apply("safe\n", "@@ -1,1 +1,1 @@\n-wrong\n+changed")
        }
    }

    @Test
    fun rejectsOverlappingHunks() {
        val patch = "@@ -1,1 +1,1 @@\n-a\n+A\n@@ -1,1 +1,1 @@\n-a\n+B"
        assertFailsWith<IllegalArgumentException> { UnifiedPatch.apply("a\n", patch) }
    }
}
