package dev.agentworkbench

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.agentworkbench.core.ChatReducer
import dev.agentworkbench.core.ChatState
import dev.agentworkbench.core.MessagePart
import dev.agentworkbench.core.ProviderEvent
import dev.agentworkbench.core.ProviderMessage
import dev.agentworkbench.core.ProviderToolCall
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersistentInteractionDeviceTest {
    @Test
    fun durableQuestionResumesSameRunAfterUiResponse() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = ProviderSettingsRepository(context).defaults(ProviderPreset.DEMO)
        val sessions = ChatSessionRepository(context)
        val executions = ExecutionRepository(context)
        val session = sessions.create(settings)
        val turnId = UUID.randomUUID().toString()
        val callId = "question-${UUID.randomUUID()}"
        val arguments = JSONObject()
            .put("question", "Posso continuar?")
            .put("options", org.json.JSONArray(listOf("Sim", "Não")))
            .toString()
        val submitted = ChatReducer.submit(
            ChatState(), turnId, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            "faça uma pergunta", "demo-local", "Demo local", settings.modelId,
        )
        val waitingTranscript = ChatReducer.applyProviderEvent(
            submitted,
            turnId,
            ProviderEvent.Completed("tool_calls"),
        )
        sessions.save(
            session.summary.id,
            settings,
            waitingTranscript.messages,
            listOf(
                ProviderMessage("user", listOf(MessagePart.Text("faça uma pergunta"))),
                ProviderMessage(
                    "assistant",
                    emptyList(),
                    toolCalls = listOf(ProviderToolCall(callId, "ask_user", arguments)),
                ),
            ),
            listOf(
                ToolActivity(
                    callId,
                    "ask_user",
                    "Posso continuar?",
                    ToolActivityStatus.AWAITING_INPUT,
                    waitingTranscript.messages.last().id,
                ),
            ),
        )
        val run = executions.beginRun(session.summary.id, "pergunta durável", settings, turnId)
        executions.waitForInput(
            run.id,
            JSONObject()
                .put("type", "question")
                .put("call_id", callId)
                .put("tool_name", "ask_user")
                .put("arguments", arguments)
                .put("question", "Posso continuar?")
                .toString(),
            "Aguardando resposta",
        )
        assertTrue(
            executions.resumeWithInput(
                run.id,
                JSONObject().put("answer", "Sim").put("declined", false).toString(),
            ),
        )
        AgentExecutionService.start(context, run.id, "Retomando pergunta")

        var terminal: AgentRunEntity? = null
        repeat(100) {
            executions.loadRun(run.id)?.let { current ->
                if (current.state in setOf(AgentRunState.SUCCEEDED.name, AgentRunState.FAILED.name)) {
                    terminal = current
                }
            }
            if (terminal == null) delay(250)
        }
        assertNotNull(terminal)
        assertEquals(terminal?.lastError, AgentRunState.SUCCEEDED.name, terminal?.state)
        val result = sessions.load(session.summary.id)!!
        assertTrue(result.providerLedger.any { message ->
            message.toolCallId == callId && message.parts.filterIsInstance<MessagePart.ToolResult>()
                .any { it.payload.contains("Sim") }
        })
        assertEquals(ToolActivityStatus.COMPLETE, result.toolActivities.single().status)
    }
}
