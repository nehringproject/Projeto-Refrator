package dev.agentworkbench

import android.content.Context
import dev.agentworkbench.core.DistributionProfile
import dev.agentworkbench.core.CommandSpec
import dev.agentworkbench.core.ExecutionRunner
import dev.agentworkbench.core.ModelProvider
import dev.agentworkbench.runner.power.PowerRunner
import dev.agentworkbench.runner.safe.AndroidSafeRunner
import java.io.File

object DistributionBindings {
    fun profile(): DistributionProfile = DistributionProfile.power()

    fun runners(workspaceRoot: File): List<ExecutionRunner> = listOf(
        AndroidSafeRunner(workspaceRoot),
        PowerRunner(workspaceRoot),
    )

    fun powerProbe(workspaceRoot: File): Pair<ExecutionRunner, CommandSpec>? {
        val runner = PowerRunner(workspaceRoot)
        return runner to runner.probeCommand("power-shell-probe")
    }

    fun powerCommand(
        workspaceRoot: File,
        commandId: String,
        script: String,
    ): CommandSpec? =
        PowerRunner(workspaceRoot).shellCommand(commandId, script)

    fun privilegedShellBridge(
        @Suppress("UNUSED_PARAMETER") context: Context,
    ): PrivilegedShellBridge = ShizukuShellBridge

    fun shadowDisplayBridge(context: Context): ShadowDisplayBridge =
        ShizukuShadowDisplayBridge.get(context.applicationContext)

    fun termuxBridge(
        @Suppress("UNUSED_PARAMETER") context: Context,
    ): TermuxBridge? = null

    fun accessibilityBridge(context: Context): AccessibilityAutomationBridge =
        AgentAccessibilityController(context.applicationContext)

    fun notificationBridge(context: Context): NotificationAccessBridge =
        AgentNotificationController(context.applicationContext)

    fun pythonRuntime(context: Context, workspaceRoot: File): PythonRuntimeBridge =
        EmbeddedPythonRuntimeBridge(context, workspaceRoot)

    fun runtimePackBridge(context: Context, runtimeRoot: File, workspaceRoot: File): RuntimePackBridge =
        EmbeddedRuntimePackManager(context.applicationContext, runtimeRoot, workspaceRoot)

    fun managedLiteLlmProvider(
        context: Context,
        settings: ProviderSettings,
        apiKey: String?,
        deployments: List<ManagedLiteLlmDeployment>,
    ): ModelProvider? = EmbeddedLiteLlmModelProvider(
        context,
        settings.modelId,
        apiKey,
        deployments,
    )

    suspend fun installRuntimePackage(
        runtime: InternalRuntime,
        request: RuntimePackageRequest,
    ): org.json.JSONObject = PowerRuntimePackageInstaller.install(runtime, request)

    fun startPersistentExecution(
        context: Context,
        runId: String? = null,
        summary: String? = null,
    ) = AgentExecutionService.start(context, runId, summary)

    fun persistentAgentExecutionSupported(): Boolean = true

    suspend fun enqueuePersistentTurn(
        context: Context,
        sessionId: String,
        turnId: String,
        summary: String,
        settings: ProviderSettings,
    ): String {
        val run = ExecutionRepository(context).beginRun(
            sessionId = sessionId,
            summary = summary,
            settings = settings,
            goalId = turnId,
        )
        AgentExecutionService.start(context, run.id, run.summary)
        return run.id
    }

    fun pausePersistentExecution(context: Context, runId: String? = null) =
        AgentExecutionService.pause(context, runId)

    fun stopPersistentExecution(context: Context, runId: String? = null) =
        AgentExecutionService.stop(context, runId)

    fun scheduleExecutionRecovery(context: Context) =
        AgentRecoveryWorker.enqueue(context)
}
