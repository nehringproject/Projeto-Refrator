package dev.agentworkbench.core

import java.time.Instant

enum class SessionPhase {
    IDLE,
    PLANNING,
    AWAITING_PLAN_APPROVAL,
    EXECUTING,
    PAUSED,
    COMPLETE,
    FAILED,
}

sealed interface AgentEvent {
    val occurredAt: Instant

    data class PlanStarted(
        override val occurredAt: Instant,
    ) : AgentEvent

    data class PlanProposed(
        val markdown: String,
        override val occurredAt: Instant,
    ) : AgentEvent

    data class PlanApproved(
        val executionMode: ExecutionMode,
        override val occurredAt: Instant,
    ) : AgentEvent

    data class ExecutionPaused(
        val reason: String,
        override val occurredAt: Instant,
    ) : AgentEvent

    data class ExecutionCompleted(
        val summary: String,
        override val occurredAt: Instant,
    ) : AgentEvent

    data class ExecutionFailed(
        val reason: String,
        override val occurredAt: Instant,
    ) : AgentEvent
}

data class SessionState(
    val phase: SessionPhase = SessionPhase.IDLE,
    val approvedMode: ExecutionMode? = null,
    val proposedPlan: String? = null,
    val failure: String? = null,
)

class InvalidSessionTransition(message: String) : IllegalStateException(message)

object SessionReducer {
    fun reduce(state: SessionState, event: AgentEvent): SessionState =
        when (event) {
            is AgentEvent.PlanStarted -> {
                requirePhase(state, SessionPhase.IDLE, SessionPhase.PAUSED)
                state.copy(
                    phase = SessionPhase.PLANNING,
                    approvedMode = null,
                    proposedPlan = null,
                    failure = null,
                )
            }

            is AgentEvent.PlanProposed -> {
                requirePhase(state, SessionPhase.PLANNING)
                state.copy(
                    phase = SessionPhase.AWAITING_PLAN_APPROVAL,
                    proposedPlan = event.markdown,
                )
            }

            is AgentEvent.PlanApproved -> {
                requirePhase(state, SessionPhase.AWAITING_PLAN_APPROVAL)
                if (event.executionMode in setOf(ExecutionMode.OBSERVE, ExecutionMode.PLAN)) {
                    throw InvalidSessionTransition(
                        "A plan must be approved into an execution-capable mode",
                    )
                }
                state.copy(
                    phase = SessionPhase.EXECUTING,
                    approvedMode = event.executionMode,
                )
            }

            is AgentEvent.ExecutionPaused -> {
                requirePhase(state, SessionPhase.EXECUTING)
                state.copy(phase = SessionPhase.PAUSED)
            }

            is AgentEvent.ExecutionCompleted -> {
                requirePhase(state, SessionPhase.EXECUTING)
                state.copy(phase = SessionPhase.COMPLETE)
            }

            is AgentEvent.ExecutionFailed -> {
                if (state.phase !in setOf(SessionPhase.PLANNING, SessionPhase.EXECUTING)) {
                    throw InvalidSessionTransition("Cannot fail a session in ${state.phase}")
                }
                state.copy(
                    phase = SessionPhase.FAILED,
                    failure = event.reason,
                )
            }
        }

    fun replay(events: Iterable<AgentEvent>): SessionState =
        events.fold(SessionState(), ::reduce)

    private fun requirePhase(state: SessionState, vararg expected: SessionPhase) {
        if (state.phase !in expected) {
            throw InvalidSessionTransition(
                "Expected ${expected.joinToString()} but session was ${state.phase}",
            )
        }
    }
}

