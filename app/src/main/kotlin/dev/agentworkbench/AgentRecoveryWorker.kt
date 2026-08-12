package dev.agentworkbench

import android.content.Context
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class AgentRecoveryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runCatching {
        val recoverable = ExecutionRepository(applicationContext)
            .recoverInterrupted()
            .filter { run ->
                run.state == AgentRunState.RECOVERING.name ||
                    run.state == AgentRunState.QUEUED.name ||
                    run.state == AgentRunState.RUNNING.name
            }
        if (recoverable.isNotEmpty()) {
            runCatching {
                AgentExecutionService.start(
                    applicationContext,
                    recoverable.first().id,
                    "Retomando ${recoverable.size} tarefa(s) aprovada(s)",
                )
            }.onFailure {
                publishResumeNotification(recoverable.size)
            }
        }
    }.fold(
        onSuccess = { Result.success() },
        onFailure = { Result.retry() },
    )

    private fun publishResumeNotification(count: Int) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Retomada de tarefas", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            RESUME_NOTIFICATION_ID,
            Notification.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("Tarefa pronta para retomar")
                .setContentText("$count tarefa(s) aguardam o Android liberar o serviço. Toque para abrir.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        private const val UNIQUE_WORK = "agent-execution-recovery"
        private const val CHANNEL_ID = "agent_recovery"
        private const val RESUME_NOTIFICATION_ID = 4102

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<AgentRecoveryWorker>()
                    // O serviço decide por run se precisa de rede. Assim um modelo GGUF local
                    // continua recuperável offline sem criar loop quente para providers remotos.
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build(),
            )
        }
    }
}
