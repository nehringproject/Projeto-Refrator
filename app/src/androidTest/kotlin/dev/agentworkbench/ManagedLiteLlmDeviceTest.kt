package dev.agentworkbench

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.agentworkbench.core.MessagePart
import dev.agentworkbench.core.ProviderEvent
import dev.agentworkbench.core.ProviderMessage
import dev.agentworkbench.core.ProviderRequest
import dev.agentworkbench.provider.http.DemoModelProvider
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManagedLiteLlmDeviceTest {
    @Test
    fun realRouterStreamsThroughBinderWithoutLanGateway() = runBlocking {
        val server = OpenAiSseServer()
        try {
            val events = invoke(
                ManagedLiteLlmDeployment(
                    alias = "mock::router",
                    displayName = "Mock Router",
                    providerModel = "mock-model",
                    litellmModel = "openai/mock-model",
                    apiBase = "http://127.0.0.1:${server.port}/v1",
                    apiKey = "test-only",
                    fallbackProvider = DemoModelProvider(),
                ),
            )
            val text = events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.value }
            assertTrue(events.toString(), text == "Router local OK")
            assertTrue(events.any { it is ProviderEvent.ModelResolved })
            assertTrue(events.any { it is ProviderEvent.Completed })
            assertFalse(text.contains("Demo local"))
        } finally {
            server.close()
        }
    }

    @Test
    fun unavailableLiteLlmDeploymentFallsBackToKotlinProvider() = runBlocking {
        val events = invoke(
            ManagedLiteLlmDeployment(
                alias = "mock::offline",
                displayName = "Mock Offline",
                providerModel = "mock-offline",
                litellmModel = "openai/mock-offline",
                apiBase = "http://127.0.0.1:1/v1",
                apiKey = "test-only",
                fallbackProvider = DemoModelProvider(),
            ),
        )
        val text = events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.value }
        assertTrue(events.toString(), text.contains("Demo local ativo"))
        assertTrue(events.any { it is ProviderEvent.Completed })
    }

    private suspend fun invoke(deployment: ManagedLiteLlmDeployment): List<ProviderEvent> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val provider = EmbeddedLiteLlmModelProvider(
            context = context,
            configuredModel = deployment.alias,
            apiKey = null,
            deployments = listOf(deployment),
        )
        val events = mutableListOf<ProviderEvent>()
        withTimeout(90_000) {
            provider.stream(
                ProviderRequest(
                    sessionId = UUID.randomUUID().toString(),
                    modelId = deployment.alias,
                    messages = listOf(
                        ProviderMessage("user", listOf(MessagePart.Text("responda localmente"))),
                    ),
                    tools = emptyList(),
                ),
            ).collect(events::add)
        }
        return events
    }

    private class OpenAiSseServer : AutoCloseable {
        private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port: Int = socket.localPort
        private val worker = thread(name = "litellm-test-server", isDaemon = true) {
            socket.accept().use { client ->
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                    }
                }
                repeat(contentLength) { reader.read() }
                val body = listOf(
                    "data: {\"id\":\"chatcmpl-local\",\"object\":\"chat.completion.chunk\",\"model\":\"mock-router\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"Router local \"},\"finish_reason\":null}]}",
                    "data: {\"id\":\"chatcmpl-local\",\"object\":\"chat.completion.chunk\",\"model\":\"mock-router\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"OK\"},\"finish_reason\":null}]}",
                    "data: {\"id\":\"chatcmpl-local\",\"object\":\"chat.completion.chunk\",\"model\":\"mock-router\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}",
                    "data: [DONE]",
                ).joinToString("\n\n", postfix = "\n\n")
                val bytes = body.toByteArray(Charsets.UTF_8)
                client.getOutputStream().apply {
                    write(
                        (
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/event-stream\r\n" +
                                "Content-Length: ${bytes.size}\r\n" +
                                "Connection: close\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII),
                    )
                    write(bytes)
                    flush()
                }
            }
        }

        override fun close() {
            runCatching { socket.close() }
            worker.join(1_000)
        }
    }
}
