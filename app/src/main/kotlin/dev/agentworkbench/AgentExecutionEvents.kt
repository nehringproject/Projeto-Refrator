package dev.agentworkbench

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Lightweight same-process invalidation; Room remains the durable source of execution state. */
object AgentExecutionEvents {
    private val mutableSessions = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val sessions = mutableSessions.asSharedFlow()

    fun sessionChanged(sessionId: String) {
        mutableSessions.tryEmit(sessionId)
    }
}

data class AgentExecutionDisplay(
    val runId: String,
    val task: String,
    val operation: String,
    val model: String,
) {
    val notificationText: String
        get() = "$task\n$operation · $model"
}

object AgentExecutionStatusBus {
    private val mutableUpdates = MutableSharedFlow<AgentExecutionDisplay>(extraBufferCapacity = 32)
    val updates = mutableUpdates.asSharedFlow()
    fun publish(value: AgentExecutionDisplay) { mutableUpdates.tryEmit(value) }
}
