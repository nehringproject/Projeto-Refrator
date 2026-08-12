package dev.agentworkbench.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SessionReducerTest {
    private val now = Instant.parse("2026-07-30T12:00:00Z")

    @Test
    fun `execution starts only after plan approval`() {
        val state = SessionReducer.replay(
            listOf(
                AgentEvent.PlanStarted(now),
                AgentEvent.PlanProposed("# Plan", now),
                AgentEvent.PlanApproved(ExecutionMode.BUILD, now),
            ),
        )

        assertEquals(SessionPhase.EXECUTING, state.phase)
        assertEquals(ExecutionMode.BUILD, state.approvedMode)
    }

    @Test
    fun `plan cannot approve back into plan mode`() {
        val awaiting = SessionReducer.replay(
            listOf(
                AgentEvent.PlanStarted(now),
                AgentEvent.PlanProposed("# Plan", now),
            ),
        )

        assertFailsWith<InvalidSessionTransition> {
            SessionReducer.reduce(
                awaiting,
                AgentEvent.PlanApproved(ExecutionMode.PLAN, now),
            )
        }
    }

    @Test
    fun `completion before execution is invalid`() {
        assertFailsWith<InvalidSessionTransition> {
            SessionReducer.reduce(
                SessionState(),
                AgentEvent.ExecutionCompleted("done", now),
            )
        }
    }
}

