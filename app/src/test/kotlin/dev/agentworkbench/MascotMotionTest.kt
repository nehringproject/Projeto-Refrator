package dev.agentworkbench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MascotMotionTest {
    @Test
    fun `same time gives the same pose everywhere`() {
        val a = MascotMotion(123_456L, index = 7, seed = 7)
        val b = MascotMotion(123_456L, index = 7, seed = 7)
        assertEquals(a.boing, b.boing)
        assertEquals(a.blink, b.blink)
        assertEquals(a.clock, b.clock)
        assertEquals(a.curious, b.curious)
    }

    @Test
    fun `loop and ping pong stay in range and repeat`() {
        for (ms in 0L..20_000L step 37) {
            val loop = motionLoop(ms, 1_500)
            val ping = motionPingPong(ms, 560)
            assertTrue(loop in 0f..1f)
            assertTrue(ping in 0f..1f)
            assertEquals(loop, motionLoop(ms + 1_500, 1_500))
        }
        assertEquals(0f, motionPingPong(0, 500))
        assertEquals(1f, motionPingPong(500, 500))
    }

    @Test
    fun `keyframes interpolate and hold`() {
        assertEquals(0f, motionKeys(0, 1_000, 0, 200 to 0f, 400 to 1f, 600 to 0f))
        assertEquals(0.5f, motionKeys(300, 1_000, 0, 200 to 0f, 400 to 1f, 600 to 0f))
        assertEquals(1f, motionKeys(400, 1_000, 0, 200 to 0f, 400 to 1f, 600 to 0f))
        assertEquals(0f, motionKeys(900, 1_000, 0, 200 to 0f, 400 to 1f, 600 to 0f))
    }

    @Test
    fun `blink closes the eyes briefly once per cycle`() {
        val closed = (0L until 3_800L step 10).count { MascotMotion(it, index = 0, seed = 0).blink > 0.5f }
        assertTrue(closed in 5..20, "closed frames: $closed")
    }
}
