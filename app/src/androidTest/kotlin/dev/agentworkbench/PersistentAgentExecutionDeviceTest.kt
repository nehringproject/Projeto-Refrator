package dev.agentworkbench

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.agentworkbench.core.ChatReducer
import dev.agentworkbench.core.ChatState
import dev.agentworkbench.core.MessagePart
import dev.agentworkbench.core.ProviderMessage
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersistentAgentExecutionDeviceTest {
    @Test
    fun foregroundServiceOwnsAndCompletesDurableTurn() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = ProviderSettingsRepository(context).defaults(ProviderPreset.DEMO)
        val sessions = ChatSessionRepository(context)
        val executions = ExecutionRepository(context)
        val session = sessions.create(settings)
        val turnId = UUID.randomUUID().toString()
        val submitted = ChatReducer.submit(
            state = ChatState(),
            turnId = turnId,
            userMessageId = UUID.randomUUID().toString(),
            assistantMessageId = UUID.randomUUID().toString(),
            text = "confirme a execução persistente",
            providerId = "demo-local",
            providerDisplayName = "Demo local",
            requestedModelId = settings.modelId,
        )
        sessions.save(
            id = session.summary.id,
            settings = settings,
            messages = submitted.messages,
            providerLedger = listOf(
                ProviderMessage(
                    role = "user",
                    parts = listOf(MessagePart.Text("confirme a execução persistente")),
                ),
            ),
            toolActivities = emptyList(),
        )
        val run = executions.beginRun(
            sessionId = session.summary.id,
            summary = "teste persistente local",
            settings = settings,
            goalId = turnId,
        )

        AgentExecutionService.start(context, run.id, run.summary)

        var finished: AgentRunEntity? = null
        repeat(80) {
            val current = executions.loadRun(run.id)
            if (current?.state in setOf(AgentRunState.SUCCEEDED.name, AgentRunState.FAILED.name)) {
                finished = current
                return@repeat
            }
            delay(250)
        }
        assertNotNull("durable run did not reach a terminal state", finished)
        assertEquals(finished?.lastError, AgentRunState.SUCCEEDED.name, finished?.state)
        val result = sessions.load(session.summary.id)
        assertNotNull(result)
        assertTrue(result!!.messages.last().text.contains("Demo local ativo"))
        assertTrue(executions.steps(run.id).all { it.state == AgentStepState.SUCCEEDED.name })
    }
}
